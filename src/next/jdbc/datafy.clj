;; copyright (c) 2020 Sean Corfield, all rights reserved

(ns next.jdbc.datafy
  "This namespace provides datafication of several JDBC object types:

  * `java.sql.Connection` -- datafies as a bean; `:metaData` is navigable
        and produces `java.sql.DatabaseMetaData`.
  * `java.sql.DatabaseMetaData` -- datafies as a bean; five properties
        are navigable to produce fully-realized datafiable result sets.
  * `java.sql.ResultSetMetaData` -- datafies as a vector of column descriptions."
  (:require [clojure.core.protocols :as core-p]
            [clojure.java.data :as j]
            [next.jdbc.result-set :as rs])
  (:import (java.sql Connection
                     DatabaseMetaData
                     ParameterMetaData
                     ResultSet ResultSetMetaData
                     Statement)))

(set! *warn-on-reflection* true)

(def ^:private column-meta
  {:catalog   (fn [^ResultSetMetaData o i] (.getCatalogName o i))
   :class     (fn [^ResultSetMetaData o i] (.getColumnClassName o i))
   :display-size (fn [^ResultSetMetaData o i] (.getColumnDisplaySize o i))
   :label     (fn [^ResultSetMetaData o i] (.getColumnLabel o i))
   :name      (fn [^ResultSetMetaData o i] (.getColumnName  o i))
   :precision (fn [^ResultSetMetaData o i] (.getPrecision   o i))
   :scale     (fn [^ResultSetMetaData o i] (.getScale       o i))
   :schema    (fn [^ResultSetMetaData o i] (.getSchemaName  o i))
   :table     (fn [^ResultSetMetaData o i] (.getTableName   o i))
   :type      (fn [^ResultSetMetaData o i] (.getColumnTypeName o i))
   ;; the is* fields:
   :nullability (fn [^ResultSetMetaData o i]
                  (condp = (.isNullable o i)
                         ResultSetMetaData/columnNoNulls  :not-null
                         ResultSetMetaData/columnNullable :null
                         :unknown))
   :auto-increment (fn [^ResultSetMetaData o i] (.isAutoIncrement o i))
   :case-sensitive (fn [^ResultSetMetaData o i] (.isCaseSensitive o i))
   :currency       (fn [^ResultSetMetaData o i] (.isCurrency      o i))
   :definitely-writable (fn [^ResultSetMetaData o i] (.isDefinitelyWritable o i))
   :read-only      (fn [^ResultSetMetaData o i] (.isReadOnly      o i))
   :searchable     (fn [^ResultSetMetaData o i] (.isSearchable    o i))
   :signed         (fn [^ResultSetMetaData o i] (.isSigned        o i))
   :writable       (fn [^ResultSetMetaData o i] (.isWritable      o i))})

(def ^:private parameter-meta
  {:class     (fn [^ParameterMetaData o i] (.getParameterClassName o i))
   :mode      (fn [^ParameterMetaData o i]
                (condp = (.getParameterMode o i)
                       ParameterMetaData/parameterModeIn    :in
                       ParameterMetaData/parameterModeInOut :in-out
                       ParameterMetaData/parameterModeOut   :out
                       :unknown))
   :precision (fn [^ParameterMetaData o i] (.getPrecision   o i))
   :scale     (fn [^ParameterMetaData o i] (.getScale       o i))
   :type      (fn [^ParameterMetaData o i] (.getParameterTypeName o i))
   ;; the is* fields:
   :nullability (fn [^ParameterMetaData o i]
                  (condp = (.isNullable o i)
                         ParameterMetaData/parameterNoNulls  :not-null
                         ParameterMetaData/parameterNullable :null
                         :unknown))
   :signed         (fn [^ParameterMetaData o i] (.isSigned        o i))})

(defn- safe-bean [o opts]
  (try
    (j/from-java-shallow o (assoc opts :add-class true))
    (catch Throwable t
      (let [dex   (juxt type (comp str ex-message))
            cause (ex-cause t)]
        (with-meta (cond-> {:exception (dex t)}
                     cause (assoc :cause (dex cause)))
          {:exception t})))))

(defn- datafy-result-set-meta-data
  [^ResultSetMetaData this]
  (mapv #(reduce-kv (fn [m k f] (assoc m k (f this %)))
                    {}
                    column-meta)
        (range 1 (inc (.getColumnCount this)))))

(defn- datafy-parameter-meta-data
  [^ParameterMetaData this]
  (mapv #(reduce-kv (fn [m k f] (assoc m k (f this %)))
                    {}
                    parameter-meta)
        (range 1 (inc (.getParameterCount this)))))

(extend-protocol core-p/Datafiable
  Connection
  (datafy [this] (safe-bean this {}))
  DatabaseMetaData
  (datafy [this]
    (with-meta (let [data (safe-bean this {})]
                 (cond-> data
                   (not (:exception (meta data)))
                   (assoc :all-tables [])))
      {`core-p/nav (fn [_ k v]
                     (condp = k
                       :all-tables
                       (rs/datafiable-result-set (.getTables this nil nil nil nil)
                                                 (.getConnection this)
                                                 {})
                       :catalogs
                       (rs/datafiable-result-set (.getCatalogs this)
                                                 (.getConnection this)
                                                 {})
                       :clientInfoProperties
                       (rs/datafiable-result-set (.getClientInfoProperties this)
                                                 (.getConnection this)
                                                 {})
                       :schemas
                       (rs/datafiable-result-set (.getSchemas this)
                                                 (.getConnection this)
                                                 {})
                       :tableTypes
                       (rs/datafiable-result-set (.getTableTypes this)
                                                 (.getConnection this)
                                                 {})
                       :typeInfo
                       (rs/datafiable-result-set (.getTypeInfo this)
                                                 (.getConnection this)
                                                 {})
                       v))}))
  ResultSetMetaData
  (datafy [this] (datafy-result-set-meta-data this))
  ParameterMetaData
  (datafy [this] (datafy-parameter-meta-data this))
  ResultSet
  (datafy [this]
    ;; SQLite has a combination ResultSet/Metadata object...
    (if (instance? ResultSetMetaData this)
      (datafy-result-set-meta-data this)
      (let [s (.getStatement this)
            c (when s (.getConnection s))]
        (cond-> (safe-bean this {})
          c (assoc :rows (rs/datafiable-result-set this c {}))))))
  Statement
  (datafy [this] (safe-bean this {:omit #{:moreResults}})))
