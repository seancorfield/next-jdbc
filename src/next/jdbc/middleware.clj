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

  For timing middleware, you can pass per-operation timing data through the
  options hash map, so you can measure the timing for the SQL execution, and
  also the time taken to build the full result set (if it is built).

  For logging middleware, you get access to the SQL & parameters prior to
  the execution and the full result set (if it is built).

  You can also transform the SQL & parameters prior to execution and transform
  the rows and/or result set after each is built."
  (:require [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]))

(defn post-processing-adapter
  "Given a builder function (e.g., `as-lower-maps`), return a new builder
  function that post-processes rows and the result set. The options may
  contain post-processing functions that are called on each row and on the
  the result set. The options map is provided as a second parameter to these
  functions, which should include `:next.jdbc/sql-params` (the vector of SQL
  and parameters, in case post-processing needs it):

  * `:post-execute-fn` -- called on the `ResultSet` object and the options
                          immediately after the SQL operation completes
                          returns a pair of a (possibly updated) `ResultSet`
                          object and (possibly updated) options
  * `:row!-fn`         -- called on each row and the options, as the row is
                          fully-realized and returns the (possiblly updated)
                          row data
  * `:rs!-fn`          -- called on the whole result set and the options, as
                          the result set is fully-realized and returns the
                          (possibly updated) result set data

  The results of these functions are returned as the rows/result set."
  [builder-fn]
  (fn [rs opts]
    (let [exec-fn   (get opts :post-execute-fn vector)
          ;; rebind both the ResultSet object and the options
          [rs opts] (exec-fn rs opts)
          mrsb      (builder-fn rs opts)
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

(defrecord JdbcMiddleware [db global-opts]
  p/Executable
  (-execute [this sql-params opts]
    (let [opts              (merge global-opts opts)
          pre-execute-fn    (get opts :pre-execute-fn vector)
          ;; rebind both the SQL & parameters and the options
          [sql-params opts] (pre-execute-fn sql-params opts)
          builder-fn        (get opts :builder-fn rs/as-maps)]
      (p/-execute db sql-params
                  (assoc opts
                         :builder-fn (post-processing-adapter builder-fn)
                         :next.jdbc/sql-params sql-params))))
  (-execute-one [this sql-params opts]
    (let [opts              (merge global-opts opts)
          pre-execute-fn    (get opts :pre-execute-fn vector)
          ;; rebind both the SQL & parameters and the options
          [sql-params opts] (pre-execute-fn sql-params opts)
          builder-fn        (get opts :builder-fn rs/as-maps)]
      (p/-execute-one db sql-params
                      (assoc opts
                             :builder-fn (post-processing-adapter builder-fn)
                             :next.jdbc/sql-params sql-params))))
  (-execute-all [this sql-params opts]
    (let [opts              (merge global-opts opts)
          pre-execute-fn    (get opts :pre-execute-fn vector)
          ;; rebind both the SQL & parameters and the options
          [sql-params opts] (pre-execute-fn sql-params opts)
          builder-fn        (get opts :builder-fn rs/as-maps)]
      (p/-execute-all db sql-params
                      (assoc opts
                             :builder-fn (post-processing-adapter builder-fn)
                             :next.jdbc/sql-params sql-params)))))

(defn wrapper
  "Given a connectable, return a wrapped connectable that will run hooks.

  Given a connectable and a hash map of options, return a wrapped connectable
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

  Uses `next.jdbc.middleware/post-processing-adapter for the last three,
  wrapped around whatever `:builder-fn` you supply for each SQL operation."
  ([db]      (JdbcMiddleware. db {}))
  ([db opts] (JdbcMiddleware. db opts)))
