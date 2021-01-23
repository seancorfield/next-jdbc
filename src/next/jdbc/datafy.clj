;; copyright (c) 2020 Sean Corfield, all rights reserved

(ns next.jdbc.datafy
  "This namespace provides datafication of several JDBC object types,
  all within the `java.sql` package:

  * `Connection` -- datafies as a bean.
  * `DatabaseMetaData` -- datafies as a bean; six properties
        are navigable to produce fully-realized datafiable result sets.
  * `ParameterMetaData` -- datafies as a vector of parameter descriptions.
  * `ResultSet` -- datafies as a bean; if the `ResultSet` has an associated
        `Statement` and that in turn has an associated `Connection` then an
        additional key of `:rows` is provided which is a datafied result set,
        from `next.jdbc.result-set/datafiable-result-set` with default options.
        This is provided as a convenience, purely for datafication of other
        JDBC data types -- in normal `next.jdbc` usage, result sets are
        datafied under full user control.
  * `ResultSetMetaData` -- datafies as a vector of column descriptions.
  * `Statement` -- datafies as a bean.

  Because different database drivers may throw `SQLException` for various
  unimplemented or unavailable properties on objects in various states,
  the default behavior is to return those exceptions using the `:qualify`
  option for `clojure.java.data/from-java-shallow`, so for a property
  `:foo`, if its corresponding getter throws an exception, it would instead
  be returned as `:foo/exception`. This behavior can be overridden by
  `binding` `next.jdbc.datafy/*datafy-failure*` to any of the other options
  supported: `:group`, `:omit`, or `:return`. See the `clojure.java.data`
  documentation for more details."
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

(def ^:dynamic *datafy-failure*
  "How datafication failures should be handled, based on `clojure.java.data`.

  Defaults to `:qualify`, but can be `:group`, `:omit`, `:qualify`, or `:return`."
  :qualify)

(defn- safe-bean [o opts]
  (try
    (j/from-java-shallow o (assoc opts :add-class true :exceptions *datafy-failure*))
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
                   ;; add an opaque object that nav will "replace"
                   (assoc :all-tables (Object.))))
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
  ResultSetMetaData
  (datafy [this] (datafy-result-set-meta-data this))
  Statement
  (datafy [this] (safe-bean this {:omit #{:moreResults}})))
