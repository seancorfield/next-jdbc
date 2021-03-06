;; copyright (c) 2018-2021 Sean Corfield, all rights reserved

(ns next.jdbc.prepare
  "Mostly an implementation namespace for how `PreparedStatement` objects are
  created by the next generation java.jdbc library.

  `set-parameters` is public and may be useful if you have a `PreparedStatement`
  that you wish to reuse and (re)set the parameters on it.

  Defines the `SettableParameter` protocol for converting Clojure values
  to database-specific values.

  See also https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc.date-time
  for implementations of `SettableParameter` that provide automatic
  conversion of Java Time objects to SQL data types.

  See also https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc.types
  for `as-xxx` functions that provide per-instance implementations of
  `SettableParameter` for each of the standard `java.sql.Types` values."
  (:require [clojure.java.data :as j]
            [next.jdbc.protocols :as p])
  (:import (java.sql Connection
                     PreparedStatement
                     ResultSet
                     Statement)))

(set! *warn-on-reflection* true)

(defprotocol SettableParameter :extend-via-metadata true
  "Protocol for setting SQL parameters in statement objects, which
  can convert from Clojure values. The default implementation just
  calls `.setObject` on the parameter value. It can be extended to
  use other methods of `PreparedStatement` to convert and set parameter
  values. Extension via metadata is supported."
  (set-parameter [val stmt ix]
    "Convert a Clojure value into a SQL value and store it as the ix'th
    parameter in the given SQL statement object."))

(extend-protocol SettableParameter
  Object
  (set-parameter [v ^PreparedStatement s ^long i]
    (.setObject s i v))

  nil
  (set-parameter [_ ^PreparedStatement s ^long i]
    (.setObject s i nil)))

(defn set-parameters
  "Given a `PreparedStatement` and a vector of parameter values, update the
  `PreparedStatement` with those parameters and return it."
  ^java.sql.PreparedStatement
  [^PreparedStatement ps params]
  (when (seq params)
    (loop [[p & more] params i 1]
      (set-parameter p ps i)
      (when more
        (recur more (inc i)))))
  ps)

(def ^{:private true
       :doc "Map friendly `:concurrency` values to `ResultSet` constants."}
  result-set-concurrency
  {:read-only ResultSet/CONCUR_READ_ONLY
   :updatable ResultSet/CONCUR_UPDATABLE})

(def ^{:private true
       :doc "Map friendly `:cursors` values to `ResultSet` constants."}
  result-set-holdability
  {:hold ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close ResultSet/CLOSE_CURSORS_AT_COMMIT})

(def ^{:private true
       :doc "Map friendly `:type` values to `ResultSet` constants."}
  result-set-type
  {:forward-only ResultSet/TYPE_FORWARD_ONLY
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive ResultSet/TYPE_SCROLL_SENSITIVE})

(defn- ^{:tag (class (into-array String []))} string-array
  [return-keys]
  (into-array String (map name return-keys)))

(defn create
  "This is an implementation detail -- use `next.jdbc/prepare` instead.

  Given a `Connection`, a SQL string, some parameters, and some options,
  return a `PreparedStatement` representing that."
  ^java.sql.PreparedStatement
  [^Connection con ^String sql params
   {:keys [return-keys result-type concurrency cursors
           fetch-size max-rows timeout]
    :as opts}]
  (let [^PreparedStatement ps
        (cond
         return-keys
         (do
           (when (or result-type concurrency cursors)
             (throw (IllegalArgumentException.
                     (str ":concurrency, :cursors, and :result-type "
                          "may not be specified with :return-keys."))))
           (if (vector? return-keys)
             (let [key-names (string-array return-keys)]
               (try
                 (try
                   (.prepareStatement con sql key-names)
                   (catch Exception _
                     ;; assume it is unsupported and try regular generated keys:
                     (.prepareStatement con sql Statement/RETURN_GENERATED_KEYS)))
                 (catch Exception _
                   ;; assume it is unsupported and try basic PreparedStatement:
                   (.prepareStatement con sql))))
             (try
               (.prepareStatement con sql Statement/RETURN_GENERATED_KEYS)
               (catch Exception _
                 ;; assume it is unsupported and try basic PreparedStatement:
                 (.prepareStatement con sql)))))

         (and result-type concurrency)
         (if cursors
           (.prepareStatement con sql
                              (get result-set-type result-type result-type)
                              (get result-set-concurrency concurrency concurrency)
                              (get result-set-holdability cursors cursors))
           (.prepareStatement con sql
                              (get result-set-type result-type result-type)
                              (get result-set-concurrency concurrency concurrency)))

         (or result-type concurrency cursors)
         (throw (IllegalArgumentException.
                 (str ":concurrency, :cursors, and :result-type "
                      "may not be specified independently.")))
         :else
         (.prepareStatement con sql))]
    ;; fast, specific option handling:
    (when fetch-size
      (.setFetchSize ps fetch-size))
    (when max-rows
      (.setMaxRows ps max-rows))
    (when timeout
      (.setQueryTimeout ps timeout))
    ;; slow, general-purpose option handling:
    (when-let [props (:statement opts)]
      (j/set-properties ps props))
    (set-parameters ps params)))

(extend-protocol p/Preparable
  java.sql.Connection
  (prepare [this sql-params opts]
           (create this (first sql-params) (rest sql-params) opts)))

(defn statement
  "Given a `Connection` and some options, return a `Statement`."
  (^java.sql.Statement
    [con] (statement con {}))
  (^java.sql.Statement
    [^Connection con
     {:keys [result-type concurrency cursors
             fetch-size max-rows timeout]
      :as opts}]
   (let [^Statement stmt
         (cond
          (and result-type concurrency)
          (if cursors
            (.createStatement con
                              (get result-set-type result-type result-type)
                              (get result-set-concurrency concurrency concurrency)
                              (get result-set-holdability cursors cursors))
            (.createStatement con
                              (get result-set-type result-type result-type)
                              (get result-set-concurrency concurrency concurrency)))

          (or result-type concurrency cursors)
          (throw (IllegalArgumentException.
                  (str ":concurrency, :cursors, and :result-type "
                       "may not be specified independently.")))
          :else
          (.createStatement con))]
     ;; fast, specific option handling:
     (when fetch-size
       (.setFetchSize stmt fetch-size))
     (when max-rows
       (.setMaxRows stmt max-rows))
     (when timeout
       (.setQueryTimeout stmt timeout))
     ;; slow, general-purpose option handling:
     (when-let [props (:statement opts)]
       (j/set-properties stmt props))
     stmt)))

(def ^:private d-r-s (volatile! nil))

(defn ^:no-doc execute-batch!
  "Deprecated in favor of `next.jdbc/execute-batch!`."
  ([ps param-groups]
   (execute-batch! ps param-groups {}))
  ([^PreparedStatement ps param-groups opts]
   (let [gen-ks (when (:return-generated-keys opts)
                  (when-let [drs @d-r-s]
                    #(drs (.getGeneratedKeys ^PreparedStatement %)
                          (p/get-connection ps {})
                          opts)))
         params (if-let [n (:batch-size opts)]
                  (if (and (number? n) (pos? n))
                    (partition-all n param-groups)
                    (throw (IllegalArgumentException.
                            ":batch-size must be positive")))
                  [param-groups])]
     (into []
           (mapcat (fn [group]
                     (run! #(.addBatch (set-parameters ps %)) group)
                     (let [result (if (:large opts)
                                    (.executeLargeBatch ps)
                                    (.executeBatch ps))]
                       (if gen-ks (gen-ks ps) result))))
           params))))
