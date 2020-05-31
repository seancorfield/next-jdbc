;; copyright (c) 2018-2020 Sean Corfield, all rights reserved

(ns next.jdbc.datafy
  "This namespace provides datafication of several JDBC object types:

  * `java.sql.Connection` -- datafies as a bean; `:metaData` is navigable
        and produces `java.sql.DatabaseMetaData`.
  * `java.sql.DatabaseMetaData` -- datafies as a bean; five properties
        are navigable to produce fully-realized datafiable result sets.
  * `java.sql.ResultSetMetaData` -- datafies as a vector of column descriptions."
  (:require [clojure.core.protocols :as core-p]
            [next.jdbc.result-set :as rs])
  (:import (java.sql Connection
                     DatabaseMetaData
                     ResultSetMetaData)))

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

(defn- safe-bean [o]
  (try
    ;; ensure we return a basic hash map:
    (merge {} (bean o))
    (catch Throwable t
      {:exception (ex-message t)
       :cause (ex-message (ex-cause t))})))

(extend-protocol core-p/Datafiable
  Connection
  (datafy [this]
    (with-meta (safe-bean this)
      {`core-p/nav (fn [_ k v]
                     (if (= :metaData k)
                       (.getMetaData this)
                       v))}))
  DatabaseMetaData
  (datafy [this]
    (with-meta (safe-bean this)
      {`core-p/nav (fn [_ k v]
                     (condp = k
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
  (datafy [this]
    (mapv #(reduce-kv (fn [m k f] (assoc m k (f this %)))
                      {}
                      column-meta)
          (range 1 (inc (.getColumnCount this))))))
