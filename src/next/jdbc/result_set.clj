;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set
  "An implementation of ResultSet handling functions.

  Defines the following protocols:
  * ReadableColumn -- to read column values by label or index
  * RowBuilder -- for materializing a row
  * ResultSetBuilder -- for materializing a result set
  * DatafiableRow -- for turning a row into something datafiable

  Also provides the default implemenations for Executable and
  the default datafy/nav behavior for rows from a result set."
  (:require [clojure.core.protocols :as core-p]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.protocols :as p])
  (:import (java.sql PreparedStatement
                     ResultSet ResultSetMetaData
                     SQLException)))

(set! *warn-on-reflection* true)

(defn get-column-names
  "Given ResultSetMetaData, return a vector of column names, each qualified by
  the table from which it came."
  [^ResultSetMetaData rsmeta opts]
  (mapv (fn [^Integer i] (keyword (not-empty (.getTableName rsmeta i))
                                  (.getColumnLabel rsmeta i)))
        (range 1 (inc (.getColumnCount rsmeta)))))

(defn get-unqualified-column-names
  "Given ResultSetMetaData, return a vector of unqualified column names."
  [^ResultSetMetaData rsmeta opts]
  (mapv (fn [^Integer i] (keyword (.getColumnLabel rsmeta i)))
        (range 1 (inc (.getColumnCount rsmeta)))))

(defprotocol ReadableColumn
  "Protocol for reading objects from the java.sql.ResultSet. Default
  implementations (for Object and nil) return the argument, and the
  Boolean implementation ensures a canonicalized true/false value,
  but it can be extended to provide custom behavior for special types."
  (read-column-by-label [val label]
    "Function for transforming values after reading them via a column label.")
  (read-column-by-index [val rsmeta idx]
    "Function for transforming values after reading them via a column index."))

(extend-protocol ReadableColumn
  Object
  (read-column-by-label [x _] x)
  (read-column-by-index [x _2 _3] x)

  Boolean
  (read-column-by-label [x _] (if (= true x) true false))
  (read-column-by-index [x _2 _3] (if (= true x) true false))

  nil
  (read-column-by-label [_1 _2] nil)
  (read-column-by-index [_1 _2 _3] nil))

(defprotocol RowBuilder
  "Protocol for building rows in various representations:
  ->row        -- called once per row to create the basis of each row
  column-count -- return the number of columns in each row
  with-column  -- called with the row and the index of the column to be added;
                  this is expected to read the column value from the ResultSet!
  row!         -- called once per row to finalize each row once it is complete

  The default implementation for building hash maps: MapResultSetBuilder"
  (->row [_])
  (column-count [_])
  (with-column [_ row i])
  (row! [_ row]))

(defprotocol ResultSetBuilder
  "Protocol for building result sets in various representations:
  ->rs         -- called to create the basis of the result set
  with-row     -- called with the result set and the row to be added
  rs!          -- called to finalize the result set once it is complete

  Default implementations for building vectors of hash maps and vectors
  of column names and row values: MapResultSetBuilder & ArrayResultSetBuilder"
  (->rs [_])
  (with-row [_ rs row])
  (rs! [_ rs]))

(defrecord MapResultSetBuilder [^ResultSet rs rsmeta cols]
  RowBuilder
  (->row [this] (transient {}))
  (column-count [this] (count cols))
  (with-column [this row i]
    (assoc! row
            (nth cols (dec i))
            (read-column-by-index (.getObject rs ^Integer i) rsmeta i)))
  (row! [this row] (persistent! row))
  ResultSetBuilder
  (->rs [this] (transient []))
  (with-row [this mrs row]
    (conj! mrs row))
  (rs! [this mrs] (persistent! mrs)))

(defn as-maps
  "Given a ResultSet and options, return a RowBuilder / ResultSetBuilder
  that produces bare vectors of hash map rows."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-column-names rsmeta opts)]
    (->MapResultSetBuilder rs rsmeta cols)))

(defn as-unqualified-maps
  "Given a ResultSet and options, return a RowBuilder / ResultSetBuilder
  that produces bare vectors of hash map rows, with simple keys."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-unqualified-column-names rsmeta opts)]
    (->MapResultSetBuilder rs rsmeta cols)))

(defrecord ArrayResultSetBuilder [^ResultSet rs rsmeta cols]
  RowBuilder
  (->row [this] (transient []))
  (column-count [this] (count cols))
  (with-column [this row i]
    (conj! row (read-column-by-index (.getObject rs ^Integer i) rsmeta i)))
  (row! [this row] (persistent! row))
  ResultSetBuilder
  (->rs [this] (transient [cols]))
  (with-row [this ars row]
    (conj! ars row))
  (rs! [this ars] (persistent! ars)))

(defn as-arrays
  "Given a ResulSet and options, return a RowBuilder / ResultSetBuilder
  that produces a vector of column names followed by vectors of row values."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-column-names rsmeta opts)]
    (->ArrayResultSetBuilder rs rsmeta cols)))

(defn as-unqualified-arrays
  "Given a ResulSet and options, return a RowBuilder / ResultSetBuilder
  that produces a vector of simple column names followed by vectors of row
  values."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-unqualified-column-names rsmeta opts)]
    (->ArrayResultSetBuilder rs rsmeta cols)))

(declare navize-row)

(defprotocol DatafiableRow
  "Given a connectable object, return a function that knows how to turn a row
  into a datafiable object that can be 'nav'igated."
  (datafiable-row [this connectable opts]))

(defn- row-builder
  "Given a RowBuilder -- a row materialization strategy -- produce a fully
  materialized row from it."
  [gen]
  (->> (reduce (fn [r i] (with-column gen r i))
               (->row gen)
               (range 1 (inc (column-count gen))))
       (row! gen)))

(defn- mapify-result-set
  "Given a result set, return an object that wraps the current row as a hash
  map. Note that a result set is mutable and the current row will change behind
  this wrapper so operations need to be eager (and fairly limited).

  Supports ILookup (keywords are treated as strings).

  Supports Associative (again, keywords are treated as strings). If you assoc,
  a full row will be realized (via `row-builder` above).

  Supports Seqable which realizes a full row of the data."
  [^ResultSet rs opts]
  (let [gen (delay ((get :gen-fn opts as-maps) rs opts))]
    (reify

      clojure.lang.ILookup
      (valAt [this k]
             (try
               (read-column-by-label (.getObject rs (name k)) (name k))
               (catch SQLException _)))
      (valAt [this k not-found]
             (try
               (read-column-by-label (.getObject rs (name k)) (name k))
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
                 (clojure.lang.MapEntry. k (read-column-by-label
                                            (.getObject rs (name k))
                                            (name k)))
                 (catch SQLException _)))
      (assoc [this k v]
             (assoc (row-builder @gen) k v))

      clojure.lang.Seqable
      (seq [this]
           (seq (row-builder @gen)))

      DatafiableRow
      (datafiable-row [this connectable opts]
                      (with-meta
                        (row-builder @gen)
                        {`core-p/datafy (navize-row connectable opts)})))))

(extend-protocol
  DatafiableRow
  clojure.lang.IObj ; assume we can "navigate" anything that accepts metadata
  ;; in reality, this is going to be over-optimistic and will like cause `nav`
  ;; to fail on attempts to navigate into result sets that are not hash maps
  (datafiable-row [this connectable opts]
                  (with-meta this
                    {`core-p/datafy (navize-row connectable opts)})))

(defn- stmt->result-set
  "Given a PreparedStatement and options, execute it and return a ResultSet
  if possible."
  ^ResultSet
  [^PreparedStatement stmt opts]
  (if (.execute stmt)
    (.getResultSet stmt)
    (when (:return-keys opts)
      (try
        (.getGeneratedKeys stmt)
        (catch Exception _)))))

(defn- reduce-stmt
  "Execute the PreparedStatement, attempt to get either its ResultSet or
  its generated keys (as a ResultSet), and reduce that using the supplied
  function and initial value.

  If the statement yields neither a ResultSet nor generated keys, return
  a hash map containing :next.jdbc/update-count and the number of rows
  updated, with the supplied function and initial value applied."
  [^PreparedStatement stmt f init opts]
  (if-let [rs (stmt->result-set stmt opts)]
    (let [rs-map (mapify-result-set rs opts)]
      (loop [init' init]
        (if (.next rs)
          (let [result (f init' rs-map)]
            (if (reduced? result)
              @result
              (recur result)))
          init')))
    (f init {:next.jdbc/update-count (.getUpdateCount stmt)})))

(extend-protocol p/Executable
  java.sql.Connection
  (-execute [this sql-params opts]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (with-open [stmt (prepare/create this
                                               (first sql-params)
                                               (rest sql-params)
                                               opts)]
                (reduce-stmt stmt f init opts)))))
  (-execute-one [this sql-params opts]
    (with-open [stmt (prepare/create this
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (if-let [rs (stmt->result-set stmt opts)]
        (let [gen-fn (get opts :gen-fn as-maps)
              gen    (gen-fn rs opts)]
          (when (.next rs)
            (datafiable-row (row-builder gen) this opts)))
        {:next.jdbc/update-count (.getUpdateCount stmt)})))
  (-execute-all [this sql-params opts]
    (with-open [stmt (prepare/create this
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (if-let [rs (stmt->result-set stmt opts)]
        (let [gen-fn (get opts :gen-fn as-maps)
              gen    (gen-fn rs opts)]
          (loop [rs' (->rs gen) more? (.next rs)]
            (if more?
              (recur (with-row gen rs'
                       (datafiable-row (row-builder gen) this opts))
                     (.next rs))
              (rs! gen rs'))))
        {:next.jdbc/update-count (.getUpdateCount stmt)})))

  javax.sql.DataSource
  (-execute [this sql-params opts]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (with-open [con (p/get-connection this opts)]
                (with-open [stmt (prepare/create con
                                                 (first sql-params)
                                                 (rest sql-params)
                                                 opts)]
                  (reduce-stmt stmt f init opts))))))
  (-execute-one [this sql-params opts]
    (with-open [con (p/get-connection this opts)]
      (with-open [stmt (prepare/create con
                                       (first sql-params)
                                       (rest sql-params)
                                       opts)]
        (if-let [rs (stmt->result-set stmt opts)]
          (when (.next rs)
            (datafiable-row (row-builder (as-maps rs opts)) this opts))
          {:next.jdbc/update-count (.getUpdateCount stmt)}))))
  (-execute-all [this sql-params opts]
    (with-open [con (p/get-connection this opts)]
      (with-open [stmt (prepare/create con
                                       (first sql-params)
                                       (rest sql-params)
                                       opts)]
        (if-let [rs (stmt->result-set stmt opts)]
          (let [gen-fn (get opts :gen-fn as-maps)
                gen    (gen-fn rs opts)]
            (loop [rs' (->rs gen) more? (.next rs)]
              (if more?
                (recur (with-row gen rs'
                         (datafiable-row (row-builder gen) this opts))
                       (.next rs))
                (rs! gen rs'))))
          {:next.jdbc/update-count (.getUpdateCount stmt)}))))

  java.sql.PreparedStatement
  ;; we can't tell if this PreparedStatement will return generated
  ;; keys so we pass a truthy value to at least attempt it if we
  ;; do not get a ResultSet back from the execute call
  (-execute [this _ opts]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
              (reduce-stmt this f init (assoc opts :return-keys true)))))
  (-execute-one [this _ opts]
    (if-let [rs (stmt->result-set this (assoc opts :return-keys true))]
      (when (.next rs)
        (datafiable-row (row-builder (as-maps rs opts))
                        (.getConnection this) opts))
      {:next.jdbc/update-count (.getUpdateCount this)}))
  (-execute-all [this sql-params opts]
    (if-let [rs (stmt->result-set this opts)]
      (let [gen-fn (get opts :gen-fn as-maps)
            gen    (gen-fn rs opts)]
        (loop [rs' (->rs gen) more? (.next rs)]
          (if more?
            (recur (with-row gen rs'
                     (datafiable-row (row-builder gen)
                                     (.getConnection this) opts))
                   (.next rs))
            (rs! gen rs'))))
      {:next.jdbc/update-count (.getUpdateCount this)}))

  Object
  (-execute [this sql-params opts]
    (p/-execute (p/get-datasource this) sql-params opts))
  (-execute-one [this sql-params opts]
    (p/-execute-one (p/get-datasource this) sql-params opts))
  (-execute-all [this sql-params opts]
    (p/-execute-all (p/get-datasource this) sql-params opts)))

(defn- default-schema
  "The default schema lookup rule for column names.

  If a column name ends with _id or id, it is assumed to be a foreign key
  into the table identified by the first part of the column name."
  [col]
  (let [[_ table] (re-find #"(?i)^(.+)_?id$" (name col))]
    (when table
      [(keyword table) :id])))

(defn- navize-row
  "Given a connectable object, return a function that knows how to turn a row
  into a navigable object.

  A `:schema` option can provide a map from qualified column names
  (`:<table>/<column>`) to tuples that indicate for which table they are a
  foreign key, the name of the key within that table, and (optionality) the
  cardinality of that relationship (`:many`, `:one`).

  If no `:schema` item is provided for a column, the convention of <table>id or
  <table>_id is used, and the assumption is that such columns are foreign keys
  in the <table> portion of their name, the key is called `id`, and the
  cardinality is :one.

  Rows are looked up using `-execute-all` or `-execute-one` and the `:table-fn`
  option, if provided, is applied to both the assumed table name and the
  assumed foreign key column name."
  [connectable opts]
  (fn [row]
    (with-meta row
      {`core-p/nav (fn [coll k v]
                     (try
                       (let [[table fk cardinality] (or (get-in opts [:schema k])
                                                        (default-schema k))]
                         (if fk
                           (let [entity-fn (:table-fn opts identity)
                                 exec-fn!  (if (= :many cardinality)
                                             p/-execute-all
                                             p/-execute-one)]
                             (exec-fn! connectable
                                       [(str "SELECT * FROM "
                                             (entity-fn (name table))
                                             " WHERE "
                                             (entity-fn (name fk))
                                             " = ?")
                                        v]
                                       opts))
                           v))
                       (catch Exception _
                         ;; assume an exception means we just cannot
                         ;; navigate anywhere, so return just the value
                         v)))})))
