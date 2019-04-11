;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.prepare
  "Mostly an implementation namespace for how PreparedStatement objects are
  created by the next generation java.jdbc library.

  set-parameters is public and may be useful if you have a PreparedStatement
  that you wish to reuse and (re)set the parameters on it."
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection
                     PreparedStatement
                     ResultSet
                     Statement)))

(set! *warn-on-reflection* true)

(defprotocol SettableParameter :extend-via-metadata true
  "Protocol for setting SQL parameters in statement objects, which
  can convert from Clojure values. The default implementation just
  calls .setObject on the parameter value. It can be extended to use other
  methods of PreparedStatement to convert and set parameter values."
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
  "Given a PreparedStatement and a vector of parameter values, update the
  PreparedStatement with those parameters and return it.

  Currently uses .setObject with no possibility of an override."
  ^java.sql.PreparedStatement
  [^PreparedStatement ps params]
  (when (seq params)
    (loop [[p & more] params i 1]
      (set-parameter p ps i)
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

(defn create
  "Given a connection, a SQL string, some parameters, and some options,
  return a PreparedStatement representing that."
  ^java.sql.PreparedStatement
  [^Connection con ^String sql params
   {:keys [return-keys result-type concurrency cursors
           fetch-size max-rows timeout]}]
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
    (when fetch-size
      (.setFetchSize ps fetch-size))
    (when max-rows
      (.setMaxRows ps max-rows))
    (when timeout
      (.setQueryTimeout ps timeout))
    (set-parameters ps params)))

(extend-protocol p/Preparable
  java.sql.Connection
  (prepare [this sql-params opts]
           (create this (first sql-params) (rest sql-params) opts)))
