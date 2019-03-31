;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.prepare
  ""
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection
                     PreparedStatement
                     ResultSet
                     Statement)))

(set! *warn-on-reflection* true)

(defn set-parameters
  ""
  ^java.sql.PreparedStatement
  [^PreparedStatement ps params]
  (when (seq params)
    (loop [[p & more] params i 1]
      (.setObject ps i p)
      (when more
        (recur more (inc i)))))
  ps)

(def ^{:private true
       :doc "Map friendly :concurrency values to ResultSet constants."}
  result-set-concurrency
  {:read-only ResultSet/CONCUR_READ_ONLY
   :updatable ResultSet/CONCUR_UPDATABLE})

(def ^{:private true
       :doc "Map friendly :cursors values to ResultSet constants."}
  result-set-holdability
  {:hold ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close ResultSet/CLOSE_CURSORS_AT_COMMIT})

(def ^{:private true
       :doc "Map friendly :type values to ResultSet constants."}
  result-set-type
  {:forward-only ResultSet/TYPE_FORWARD_ONLY
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive ResultSet/TYPE_SCROLL_SENSITIVE})

(defn- ^{:tag (class (into-array String []))} string-array
  [return-keys]
  (into-array String return-keys))

(defn pre-prepare*
  "Given a some options, return a statement factory -- a function that will
  accept a connection and a SQL string and parameters, and return a
  PreparedStatement representing that."
  [{:keys [return-keys result-type concurrency cursors
           fetch-size max-rows timeout]}]
  (cond->
    (cond
     return-keys
     (do
       (when (or result-type concurrency cursors)
         (throw (IllegalArgumentException.
                 (str ":concurrency, :cursors, and :result-type "
                      "may not be specified with :return-keys."))))
       (if (vector? return-keys)
         (let [key-names (string-array return-keys)]
           (fn [^Connection con ^String sql]
             (try
               (try
                 (.prepareStatement con sql key-names)
                 (catch Exception _
                   ;; assume it is unsupported and try regular generated keys:
                   (.prepareStatement con sql Statement/RETURN_GENERATED_KEYS)))
               (catch Exception _
                 ;; assume it is unsupported and try basic PreparedStatement:
                 (.prepareStatement con sql)))))
         (fn [^Connection con ^String sql]
           (try
             (.prepareStatement con sql Statement/RETURN_GENERATED_KEYS)
             (catch Exception _
               ;; assume it is unsupported and try basic PreparedStatement:
               (.prepareStatement con sql))))))

     (and result-type concurrency)
     (if cursors
       (fn [^Connection con ^String sql]
         (.prepareStatement con sql
                            (get result-set-type result-type result-type)
                            (get result-set-concurrency concurrency concurrency)
                            (get result-set-holdability cursors cursors)))
       (fn [^Connection con ^String sql]
         (.prepareStatement con sql
                            (get result-set-type result-type result-type)
                            (get result-set-concurrency concurrency concurrency))))

     (or result-type concurrency cursors)
     (throw (IllegalArgumentException.
             (str ":concurrency, :cursors, and :result-type "
                  "may not be specified independently.")))
     :else
     (fn [^Connection con ^String sql]
       (.prepareStatement con sql)))
    fetch-size (as-> f
                     (fn [^Connection con ^String sql]
                       (.setFetchSize ^PreparedStatement (f con sql) fetch-size)))
    max-rows (as-> f
                   (fn [^Connection con ^String sql]
                     (.setMaxRows ^PreparedStatement (f con sql) max-rows)))
    timeout (as-> f
                  (fn [^Connection con ^String sql]
                    (.setQueryTimeout ^PreparedStatement (f con sql) timeout)))))

(defn prepare-fn*
  "Given a connection, a SQL statement, its parameters, and a statement factory,
  return a PreparedStatement representing that."
  ^java.sql.PreparedStatement
  [con sql params factory]
  (set-parameters (factory con sql) params))

(extend-protocol p/Preparable
  java.sql.Connection
  (prepare [this sql-params opts]
           (let [[sql & params] sql-params
                 factory        (pre-prepare* opts)]
             (set-parameters (factory this sql) params))))
