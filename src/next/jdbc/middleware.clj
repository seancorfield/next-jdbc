;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.middleware
  "Middleware to wrap SQL operations and result set builders.

  Can wrap a connectable such that you can: supply 'global' options for all
  SQL operations on that connectable; pre-process the SQL and/or parameters
  and/or the options; post-process the result set object (and options);
  post-process each row as it is built; post-process the whole result set.

  The following options can be used to provide those hook functions:
  * :pre-execute-fn  -- pre-process the SQL & parameters and options
                        returns pair of (possibly updated) SQL & parameters
                        and (possibly updated) options
  * (execute SQL)
  * :post-execute-fn -- post-process the result set and options
                        returns pair of (possibly updated) `ResultSet` object
                        and (possibly updated) options
  * :row!-fn         -- post-process each row (and is also passed options)
                        returns (possibly updated) row data
  * :rs!-fn          -- post-process the whole result set (and is also
                        passed options)
                        returns (possibly updated) result set data

  The default for all of these is to simply return data unchanged. For
  `:pre-execute-fn` and `:post-execute-fn`, that means returning a pair of
  `[sql-param options]` and `[rs options]` respectively. For `:row!-fn`,
  that means returning the row data unchanged (and ignoring the options).
  For `:rs!-fn`, that means returning the result set data unchanged (and
  ignoring the options).

  For SQL operations that do not produce a `ResultSet`, the post-process
  hook (`:post-execute-fn`) is called with the update count and options
  instead of the result set (and options) and should return a pair of the
  update count and the options (unchanged).

  For timing middleware, you can pass per-operation timing data through the
  options hash map, so you can measure the timing for the SQL execution, and
  also the time taken to build the full result set (if it is built).

  For logging middleware, you get access to the SQL & parameters prior to
  the execution and the full result set (if it is built).

  You can also transform the SQL & parameters prior to execution and transform
  the rows and/or result set after each is built."
  (:require [next.jdbc.prepare :as prepare]
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs])
  (:import (java.sql PreparedStatement Statement)))

(defn post-processing-adapter
  "Given a builder function (e.g., `as-lower-maps`), return a new builder
  function that post-processes rows and the result set. The options may
  contain post-processing functions that are called on each row and on the
  the result set. The options map is provided as a second parameter to these
  functions, which should include `:next.jdbc/sql-params` (the vector of SQL
  and parameters, in case post-processing needs it):

  * `:row!-fn`         -- called on each row and the options, as the row is
                          fully-realized and returns the (possiblly updated)
                          row data
  * `:rs!-fn`          -- called on the whole result set and the options, as
                          the result set is fully-realized and returns the
                          (possibly updated) result set data

  The results of these functions are returned as the rows/result set."
  [builder-fn]
  (fn [rs opts]
    (let [mrsb      (builder-fn rs opts)
          row!-fn   (get opts :row!-fn (comp first vector))
          rs!-fn    (get opts :rs!-fn  (comp first vector))]
      (reify
        rs/RowBuilder
        (->row [this] (rs/->row mrsb))
        (column-count [this] (rs/column-count mrsb))
        (with-column [this row i] (rs/with-column mrsb row i))
        (row! [this row] (row!-fn (rs/row! mrsb row) opts))
        rs/ResultSetBuilder
        (->rs [this] (rs/->rs mrsb))
        (with-row [this mrs row] (rs/with-row mrsb mrs row))
        (rs! [this mrs] (rs!-fn (rs/rs! mrsb mrs) opts))))))

(defprotocol WrappedExecutable
  "This is an implementation detail for the middleware wrapper."
  (wrapped-execute ^clojure.lang.IReduceInit [this sql-params opts])
  (wrapped-execute-one [this sql-params opts])
  (wrapped-execute-all [this sql-params opts]))

(defn- reduce-stmt
  "Variant of `next.jdbc.result-set/reduce-stmt` that calls the
  `:post-execute-fn` hook on results sets and update counts."
  [^PreparedStatement stmt f init opts]
  (if-let [rs (#'rs/stmt->result-set stmt opts)]
    (let [[rs opts] ((:post-execute-fn opts) rs opts)
          rs-map    (#'rs/mapify-result-set rs opts)]
      (loop [init' init]
        (if (.next rs)
          (let [result (f init' rs-map)]
            (if (reduced? result)
              @result
              (recur result)))
          init')))
    (let [[n _] ((:post-execute-fn opts) (.getUpdateCount stmt) opts)]
      (f init {:next.jdbc/update-count n}))))

(defn- reduce-stmt-sql
  "Variant of `next.jdbc.result-set/reduce-stmt-sql` that calls the
  `:post-execute-fn` hook on results sets and update counts."
  [^Statement stmt sql f init opts]
  (if-let [rs (#'rs/stmt-sql->result-set stmt sql opts)]
    (let [[rs opts] ((:post-execute-fn opts) rs opts)
          rs-map    (#'rs/mapify-result-set rs opts)]
      (loop [init' init]
        (if (.next rs)
          (let [result (f init' rs-map)]
            (if (reduced? result)
              @result
              (recur result)))
          init')))
    (let [[n _] ((:post-execute-fn opts) (.getUpdateCount stmt) opts)]
      (f init {:next.jdbc/update-count n}))))

;; this duplicates the Executable implementations from next.jdbc.result-set
;; but with hooks for calling :post-execute-fn and being able to rely on
;; :builder-fn always being present
(extend-protocol WrappedExecutable
  java.sql.Connection
  (wrapped-execute [this sql-params opts]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (with-open [stmt (prepare/create this
                                               (first sql-params)
                                               (rest sql-params)
                                               opts)]
                (reduce-stmt stmt f init opts)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (wrapped-execute-one [this sql-params opts]
    (with-open [stmt (prepare/create this
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (wrapped-execute-one stmt nil opts)))
  (wrapped-execute-all [this sql-params opts]
    (with-open [stmt (prepare/create this
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (wrapped-execute-all stmt nil opts)))

  javax.sql.DataSource
  (wrapped-execute [this sql-params opts]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (with-open [con  (p/get-connection this opts)
                          stmt (prepare/create con
                                               (first sql-params)
                                               (rest sql-params)
                                               opts)]
                (reduce-stmt stmt f init opts)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (wrapped-execute-one [this sql-params opts]
    (with-open [con  (p/get-connection this opts)
                stmt (prepare/create con
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (wrapped-execute-one stmt nil opts)))
  (wrapped-execute-all [this sql-params opts]
    (with-open [con  (p/get-connection this opts)
                stmt (prepare/create con
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (wrapped-execute-all stmt nil opts)))

  java.sql.PreparedStatement
  ;; we can't tell if this PreparedStatement will return generated
  ;; keys so we pass a truthy value to at least attempt it if we
  ;; do not get a ResultSet back from the execute call
  (wrapped-execute [this _ opts]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (reduce-stmt this f init (assoc opts :return-keys true)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (wrapped-execute-one [this _ opts]
    (if-let [rs (#'rs/stmt->result-set this (assoc opts :return-keys true))]
      (let [[rs opts] ((:post-execute-fn opts) rs opts)
            builder   ((:builder-fn opts) rs opts)]
        (when (.next rs)
          (rs/datafiable-row (#'rs/row-builder builder)
                             (.getConnection this) opts)))
      (let [[n _] ((:post-execute-fn opts) (.getUpdateCount this) opts)]
        {:next.jdbc/update-count n})))
  (wrapped-execute-all [this _ opts]
    (if-let [rs (#'rs/stmt->result-set this opts)]
      (let [[rs opts] ((:post-execute-fn opts) rs opts)]
        (rs/datafiable-result-set rs (.getConnection this) opts))
      (let [[n _] ((:post-execute-fn opts) (.getUpdateCount this) opts)]
        [{:next.jdbc/update-count n}])))

  java.sql.Statement
  ;; we can't tell if this Statement will return generated
  ;; keys so we pass a truthy value to at least attempt it if we
  ;; do not get a ResultSet back from the execute call
  (wrapped-execute [this sql-params opts]
    (assert (= 1 (count sql-params))
            "Parameters cannot be provided when executing a non-prepared Statement")
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (reduce-stmt-sql this (first sql-params) f init (assoc opts :return-keys true)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (wrapped-execute-one [this sql-params opts]
    (assert (= 1 (count sql-params))
            "Parameters cannot be provided when executing a non-prepared Statement")
    (if-let [rs (#'rs/stmt-sql->result-set this (first sql-params) (assoc opts :return-keys true))]
      (let [[rs opts] ((:post-execute-fn opts) rs opts)
            builder   ((:builder-fn opts) rs opts)]
        (when (.next rs)
          (rs/datafiable-row (#'rs/row-builder builder)
                             (.getConnection this) opts)))
      (let [[n _] ((:post-execute-fn opts) (.getUpdateCount this) opts)]
        {:next.jdbc/update-count n})))
  (wrapped-execute-all [this sql-params opts]
    (assert (= 1 (count sql-params))
            "Parameters cannot be provided when executing a non-prepared Statement")
    (if-let [rs (#'rs/stmt-sql->result-set this (first sql-params) opts)]
      (let [[rs opts] ((:post-execute-fn opts) rs opts)]
        (rs/datafiable-result-set rs (.getConnection this) opts))
      (let [[n _] ((:post-execute-fn opts) (.getUpdateCount this) opts)]
        [{:next.jdbc/update-count n}])))

  Object
  (wrapped-execute [this sql-params opts]
    (wrapped-execute (p/get-datasource this) sql-params opts))
  (wrapped-execute-one [this sql-params opts]
    (wrapped-execute-one (p/get-datasource this) sql-params opts))
  (wrapped-execute-all [this sql-params opts]
    (wrapped-execute-all (p/get-datasource this) sql-params opts)))

(defn- execute-wrapper
  [f db global-opts sql-params opts]
  (let [opts (merge {:pre-execute-fn vector :post-execute-fn vector
                     :builder-fn rs/as-maps}
                    global-opts opts)
        ;; rebind both the SQL & parameters and the options
        [sql-params opts] ((:pre-execute-fn opts) sql-params opts)]
    (f db sql-params
       (assoc opts
              :builder-fn (post-processing-adapter (:builder-fn opts))
              :next.jdbc/sql-params sql-params))))

(defrecord JdbcMiddleware [db global-opts]
  p/Executable
  (-execute [this sql-params opts]
    (execute-wrapper wrapped-execute db global-opts sql-params opts))
  (-execute-one [this sql-params opts]
    (execute-wrapper wrapped-execute-one db global-opts sql-params opts))
  (-execute-all [this sql-params opts]
    (execute-wrapper wrapped-execute-all db global-opts sql-params opts)))

(defn wrapper
  "Given a connectable and a hash map of options, return a wrapped connectable
  that will use those options as defaults for any SQL operations and will
  run hooks.

  The following hooks are supported:
  * :pre-execute-fn  -- pre-process the SQL & parameters and options
                        returns pair of (possibly updated) SQL & parameters
                        and (possibly updated) options
  * :post-execute-fn -- post-process the result set and options
                        returns pair of (possibly updated) `ResultSet` object
                        and (possibly updated) options
  * :row!-fn         -- post-process each row (and is also passed options)
                        returns (possibly updated) row data
  * :rs!-fn          -- post-process the whole result set (and is also
                        passed options)
                        returns (possibly updated) result set data

  For SQL operations that do not produce a `ResultSet`, the post-process
  hook (`:post-execute-fn`) is called with the update count and options
  instead of the result set (and options) and should return a pair of the
  update count and the options (unchanged).

  Uses `next.jdbc.middleware/post-processing-adapter for the last two,
  wrapped around whatever `:builder-fn` you supply for each SQL operation."
  ([db]      (JdbcMiddleware. db {}))
  ([db opts] (JdbcMiddleware. db opts)))
