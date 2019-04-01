;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set
  ""
  (:require [clojure.core.protocols :as core-p]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.protocols :as p])
  (:import (java.sql PreparedStatement
                     ResultSet ResultSetMetaData
                     SQLException)))

(set! *warn-on-reflection* true)

(defn- get-column-names
  ""
  [^ResultSet rs opts]
  (let [^ResultSetMetaData rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (if-let [ident-fn (:identifiers opts)]
      (mapv (fn [^Integer i]
              (keyword (when-let [qualifier (not-empty (.getTableName rsmeta i))]
                         (ident-fn qualifier))
                       (ident-fn (.getColumnLabel rsmeta i))))
            idxs)
      (mapv (fn [^Integer i]
              (keyword (not-empty (.getTableName rsmeta i))
                       (.getColumnLabel rsmeta i)))
            idxs))))

(defn- mapify-result-set
  "Given a result set, return an object that wraps the current row as a hash
  map. Note that a result set is mutable and the current row will change behind
  this wrapper so operations need to be eager (and fairly limited).

  Supports ILookup (keywords are treated as strings).

  Supports Associative (again, keywords are treated as strings). If you assoc,
  a full row will be realized (via seq/into).

  Supports Seqable which realizes a full row of the data."
  [^ResultSet rs opts]
  (let [cols (delay (get-column-names rs opts))]
    (reify

      clojure.lang.ILookup
      (valAt [this k]
             (try
               (.getObject rs (name k))
               (catch SQLException _)))
      (valAt [this k not-found]
             (try
               (.getObject rs (name k))
               (catch SQLException _
                 not-found)))

      clojure.lang.Associative
      (containsKey [this k]
                   (try
                     (.getObject rs (name k))
                     true
                     (catch SQLException _
                       false)))
      (entryAt [this k]
               (try
                 (clojure.lang.MapEntry. k (.getObject rs (name k)))
                 (catch SQLException _)))
      (assoc [this k v]
             (assoc (into {} (seq this)) k v))

      clojure.lang.Seqable
      (seq [this]
           (seq (mapv (fn [^Integer i]
                        (clojure.lang.MapEntry. (nth @cols (dec i))
                                                (.getObject rs i)))
                      (range 1 (inc (count @cols)))))))))

(defn- reduce-stmt
  ""
  [^PreparedStatement stmt f init opts]
  (if-let [^ResultSet rs (if (.execute stmt)
                           (.getResultSet stmt)
                           (when (:return-keys opts)
                             (try
                               (.getGeneratedKeys stmt)
                               (catch Exception _))))]
    (let [rs-map (mapify-result-set rs opts)]
      (loop [init' init]
        (if (.next rs)
          (let [result (f init' rs-map)]
            (if (reduced? result)
              @result
              (recur result)))
          init')))
    (f init {::update-count (.getUpdateCount stmt)})))

(extend-protocol p/Executable
  java.sql.Connection
  (-execute [this sql-params opts]
    (let [factory (prepare/->factory opts)]
      (reify clojure.lang.IReduceInit
        (reduce [_ f init]
                (with-open [stmt (prepare/create this
                                                 (first sql-params)
                                                 (rest sql-params)
                                                 factory)]
                  (reduce-stmt stmt f init opts))))))
  javax.sql.DataSource
  (-execute [this sql-params opts]
    (let [factory (prepare/->factory opts)]
      (reify clojure.lang.IReduceInit
        (reduce [_ f init]
                (with-open [con (p/get-connection this opts)]
                  (with-open [stmt (prepare/create con
                                                   (first sql-params)
                                                   (rest sql-params)
                                                   factory)]
                    (reduce-stmt stmt f init opts)))))))
  java.sql.PreparedStatement
  (-execute [this _ opts]
    (reify clojure.lang.IReduceInit
      ;; we can't tell if this PreparedStatement will return generated
      ;; keys so we pass a truthy value to at least attempt it if we
      ;; do not get a ResultSet back from the execute call
      (reduce [_ f init]
              (reduce-stmt this f init (assoc opts :return-keys true)))))
  Object
  (-execute [this sql-params opts]
    (p/-execute (p/get-datasource this) sql-params opts)))

(declare navize-row)

(defn datafiable-row
  [connectable opts]
  (fn [row]
    (into (with-meta {} {`core-p/datafy (navize-row connectable opts)}) row)))

(defn execute!
  ""
  [connectable sql-params opts]
  (into []
        (map (or (:row-fn opts) (datafiable-row connectable opts)))
        (p/-execute connectable sql-params opts)))

(defn execute-one!
  ""
  [connectable sql-params opts]
  (let [row-fn (or (:row-fn opts) (datafiable-row connectable opts))]
    (reduce (fn [_ row]
              (reduced (row-fn row)))
            nil
            (p/-execute connectable sql-params opts))))

(defn- default-schema
  "The default schema lookup rule for column names.

  If a column name ends with _id or id, it is assumed to be a foreign key
  into the table identified by the first part of the column name."
  [col]
  (let [[_ table] (re-find #"(?i)^(.+)_?id$" (name col))]
    (when table
      [(keyword table) :id])))

(defn- navize-row
  ""
  [connectable opts]
  (fn [row]
    (with-meta row
      {`core-p/nav (fn [coll k v]
                     (let [[table fk cardinality] (or (get-in opts [:schema k])
                                                      (default-schema k))]
                       (if fk
                         (try
                           (let [entity-fn (:entities opts identity)
                                 exec-fn!  (if (= :many cardinality)
                                             execute!
                                             execute-one!)]
                             (exec-fn! connectable
                                       [(str "SELECT * FROM "
                                             (entity-fn (name table))
                                             " WHERE "
                                             (entity-fn (name fk))
                                             " = ?")
                                        v]
                                       opts))
                           (catch Exception _
                             ;; assume an exception means we just cannot
                             ;; navigate anywhere, so return just the value
                             v))
                         v)))})))
