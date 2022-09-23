;; copyright (c) 2018-2021 Sean Corfield, all rights reserved

(ns next.jdbc.result-set
  "An implementation of `ResultSet` handling functions.

  Defines the following protocols:
  * `DatafiableRow` -- for turning a row into something datafiable
  * `ReadableColumn` -- to read column values by label or index
  * `RowBuilder` -- for materializing a row
  * `ResultSetBuilder` -- for materializing a result set

  A broad range of result set builder implementation functions are provided.

  Also provides the default implementations for `Executable` and
  the default `datafy`/`nav` behavior for rows from a result set.

  See also https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc.date-time
  for implementations of `ReadableColumn` that provide automatic
  conversion of some SQL data types to Java Time objects."
  (:require [camel-snake-kebab.core :refer [->kebab-case]]
            [clojure.core.protocols :as core-p]
            [clojure.core.reducers :as r]
            [clojure.datafy :as d]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.protocols :as p])
  (:import (java.sql Clob
                     PreparedStatement
                     ResultSet ResultSetMetaData
                     Statement
                     SQLException)
           (java.util Locale)
           (java.util.concurrent ForkJoinPool ForkJoinTask)))

(set! *warn-on-reflection* true)

(defn- get-table-name
  "No, if it isn't supported, you're supposed to return
  an empty string. That's what the JDBC docs say!"
  [^ResultSetMetaData rsmeta ^Integer i]
  (try
    (.getTableName rsmeta i)
    (catch java.sql.SQLFeatureNotSupportedException _
      "")))

(defn get-column-names
  "Given `ResultSetMetaData`, return a vector of column names, each qualified by
  the table from which it came."
  [^ResultSetMetaData rsmeta _]
  (mapv (fn [^Integer i]
          (if-let [q (not-empty (get-table-name rsmeta i))]
            (keyword q (.getColumnLabel rsmeta i))
            (keyword (.getColumnLabel rsmeta i))))
        (range 1 (inc (if rsmeta (.getColumnCount rsmeta) 0)))))

(defn get-unqualified-column-names
  "Given `ResultSetMetaData`, return a vector of unqualified column names."
  [^ResultSetMetaData rsmeta _]
  (mapv (fn [^Integer i] (keyword (.getColumnLabel rsmeta i)))
        (range 1 (inc (if rsmeta (.getColumnCount rsmeta) 0)))))

(defn get-modified-column-names
  "Given `ResultSetMetaData`, return a vector of modified column names, each
  qualified by the table from which it came.

  Requires both the `:qualifier-fn` and `:label-fn` options."
  [^ResultSetMetaData rsmeta opts]
  (let [qf (:qualifier-fn opts)
        lf (:label-fn opts)]
    (assert qf ":qualifier-fn is required")
    (assert lf ":label-fn is required")
    (mapv (fn [^Integer i]
            (if-let [q (some-> (get-table-name rsmeta i) (qf) (not-empty))]
              (keyword q (-> (.getColumnLabel rsmeta i) (lf)))
              (keyword (-> (.getColumnLabel rsmeta i) (lf)))))
          (range 1 (inc (if rsmeta (.getColumnCount rsmeta) 0))))))

(defn get-unqualified-modified-column-names
  "Given `ResultSetMetaData`, return a vector of unqualified modified column
  names.

  Requires the `:label-fn` option."
  [^ResultSetMetaData rsmeta opts]
  (let [lf (:label-fn opts)]
    (assert lf ":label-fn is required")
    (mapv (fn [^Integer i] (keyword (lf (.getColumnLabel rsmeta i))))
          (range 1 (inc (if rsmeta (.getColumnCount rsmeta) 0))))))

(defn- lower-case
  "Converts a string to lower case in the US locale to avoid problems in
  locales where the lower case version of a character is not a valid SQL
  entity name (e.g., Turkish)."
  [^String s]
  (.toLowerCase s (Locale/US)))

(defn get-lower-column-names
  "Given `ResultSetMetaData`, return a vector of lower-case column names, each
  qualified by the table from which it came."
  [rsmeta opts]
  (get-modified-column-names rsmeta (assoc opts
                                           :qualifier-fn lower-case
                                           :label-fn lower-case)))

(defn get-unqualified-lower-column-names
  "Given `ResultSetMetaData`, return a vector of unqualified column names."
  [rsmeta opts]
  (get-unqualified-modified-column-names rsmeta
                                         (assoc opts :label-fn lower-case)))

(defprotocol ReadableColumn
  "Protocol for reading objects from the `java.sql.ResultSet`. Default
  implementations (for `Object` and `nil`) return the argument, and the
  `Boolean` implementation ensures a canonicalized `true`/`false` value,
  but it can be extended to provide custom behavior for special types.
  Extension via metadata is supported."
  :extend-via-metadata true
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
  "Protocol for building rows in various representations.

  The default implementation for building hash maps: `MapResultSetBuilder`"
  (->row [_]
    "Called once per row to create the basis of each row.")
  (column-count [_]
    "Return the number of columns in each row.")
  (with-column [_ row i]
    "Called with the row and the index of the column to be added;
    this is expected to read the column value from the `ResultSet`!")
  (with-column-value [_ row col v]
    "Called with the row, the column name, and the value to be added;
    this is a low-level function, typically used by `with-column`.")
  (row! [_ row]
    "Called once per row to finalize each row once it is complete."))

(defprotocol ResultSetBuilder
  "Protocol for building result sets in various representations.

  Default implementations for building vectors of hash maps and vectors of
  column names and row values: `MapResultSetBuilder` & `ArrayResultSetBuilder`"
  (->rs [_]
    "Called to create the basis of the result set.")
  (with-row [_ rs row]
    "Called with the result set and the row to be added.")
  (rs! [_ rs]
    "Called to finalize the result set once it is complete."))

(defn builder-adapter
  "Given any builder function (e.g., `as-lower-maps`) and a column reading
  function, return a new builder function that uses that column reading
  function instead of `.getObject` and `read-column-by-index` so you can
  override the default behavior.

  The default column-by-index-fn behavior would be equivalent to:

      (defn default-column-by-index-fn
        [builder ^ResultSet rs ^Integer i]
        (read-column-by-index (.getObject rs i) (:rsmeta builder) i))

  Your column-by-index-fn can use the result set metadata `(:rsmeta builder)`
  and/or the (processed) column name `(nth (:cols builder) (dec i))` to
  determine whether to call `.getObject` or some other method to read the
  column's value, and can choose whether or not to use the `ReadableColumn`
  protocol-based value processor (and could add metadata to the value to
  satisfy that protocol on a per-instance basis)."
  [builder-fn column-by-index-fn]
  (fn [rs opts]
    (let [builder (builder-fn rs opts)]
      (reify
        RowBuilder
        (->row [this] (->row builder))
        (column-count [this] (column-count builder))
        (with-column [this row i]
          (with-column-value this row (nth (:cols builder) (dec i))
            (column-by-index-fn builder rs i)))
        (with-column-value [this row col v]
          (with-column-value builder row col v))
        (row! [this row] (row! builder row))
        ResultSetBuilder
        (->rs [this] (->rs builder))
        (with-row [this mrs row] (with-row builder mrs row))
        (rs! [this mrs] (rs! builder mrs))
        clojure.lang.ILookup
        (valAt [this k] (get builder k))
        (valAt [this k not-found] (get builder k not-found))))))

(defrecord MapResultSetBuilder [^ResultSet rs rsmeta cols]
  RowBuilder
  (->row [this] (transient {}))
  (column-count [this] (count cols))
  (with-column [this row i]
    (with-column-value this row (nth cols (dec i))
      (read-column-by-index (.getObject rs ^Integer i) rsmeta i)))
  (with-column-value [this row col v]
    (assoc! row col v))
  (row! [this row] (persistent! row))
  ResultSetBuilder
  (->rs [this] (transient []))
  (with-row [this mrs row]
    (conj! mrs row))
  (rs! [this mrs] (persistent! mrs)))

(defn as-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows.

  This is the default `:builder-fn` option."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-column-names rsmeta opts)]
    (->MapResultSetBuilder rs rsmeta cols)))

(defn as-unqualified-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple keys."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-unqualified-column-names rsmeta opts)]
    (->MapResultSetBuilder rs rsmeta cols)))

(defn as-modified-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with modified keys.

  Requires both the `:qualifier-fn` and `:label-fn` options."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-modified-column-names rsmeta opts)]
    (->MapResultSetBuilder rs rsmeta cols)))

(defn as-unqualified-modified-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple, modified keys.

  Requires the `:label-fn` option."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-unqualified-modified-column-names rsmeta opts)]
    (->MapResultSetBuilder rs rsmeta cols)))

(defn as-lower-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with lower-case keys."
  [rs opts]
  (as-modified-maps rs (assoc opts
                              :qualifier-fn lower-case
                              :label-fn lower-case)))

(defn as-unqualified-lower-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple, lower-case keys."
  [rs opts]
  (as-unqualified-modified-maps rs (assoc opts :label-fn lower-case)))

(defn as-kebab-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with kebab-case keys."
  [rs opts]
  (as-modified-maps rs (assoc opts
                              :qualifier-fn ->kebab-case
                              :label-fn ->kebab-case)))

(defn as-unqualified-kebab-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple, kebab-case keys."
  [rs opts]
  (as-unqualified-modified-maps rs (assoc opts :label-fn ->kebab-case)))

(defn as-maps-adapter
  "Given a map builder function (e.g., `as-lower-maps`) and a column reading
  function, return a new builder function that uses that column reading
  function instead of `.getObject` so you can override the default behavior.

  The default column-reader behavior would be equivalent to:

      (defn default-column-reader
        [^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
        (.getObject rs i))

  Your column-reader can use the result set metadata to determine whether
  to call `.getObject` or some other method to read the column's value.

  `read-column-by-index` is still called on the result of that read.

  Note: this is different behavior to `builder-adapter`'s `column-by-index-fn`."
  [builder-fn column-reader]
  (builder-adapter builder-fn
                   (fn [builder rs i]
                     (let [^ResultSetMetaData rsmeta (:rsmeta builder)]
                       (read-column-by-index (column-reader rs rsmeta i)
                                             rsmeta
                                             i)))))

(defn clob->string
  "Given a CLOB column value, read it as a string."
  [^Clob clob]
  (with-open [rdr (.getCharacterStream clob)]
    (slurp rdr)))

(defn clob-column-reader
  "An example column-reader that still uses `.getObject` but expands CLOB
  columns into strings."
  [^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (when-let [value (.getObject rs i)]
    (cond-> value
      (instance? Clob value)
      (clob->string))))

(defrecord ArrayResultSetBuilder [^ResultSet rs rsmeta cols]
  RowBuilder
  (->row [this] (transient []))
  (column-count [this] (count cols))
  (with-column [this row i]
    (with-column-value this row nil
      (read-column-by-index (.getObject rs ^Integer i) rsmeta i)))
  (with-column-value [this row _ v]
    (conj! row v))
  (row! [this row] (persistent! row))
  ResultSetBuilder
  (->rs [this] (transient [cols]))
  (with-row [this ars row]
    (conj! ars row))
  (rs! [this ars] (persistent! ars)))

(defn as-arrays
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces a vector of column names followed by vectors of row values."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-column-names rsmeta opts)]
    (->ArrayResultSetBuilder rs rsmeta cols)))

(defn as-unqualified-arrays
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces a vector of simple column names followed by vectors of row
  values."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-unqualified-column-names rsmeta opts)]
    (->ArrayResultSetBuilder rs rsmeta cols)))

(defn as-modified-arrays
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces a vector of modified column names followed by vectors of
  row values.

  Requires both the `:qualifier-fn` and `:label-fn` options."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-modified-column-names rsmeta opts)]
    (->ArrayResultSetBuilder rs rsmeta cols)))

(defn as-unqualified-modified-arrays
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces a vector of simple, modified column names followed by
  vectors of row values.

  Requires the `:label-fn` option."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-unqualified-modified-column-names rsmeta opts)]
    (->ArrayResultSetBuilder rs rsmeta cols)))

(defn as-lower-arrays
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces a vector of lower-case column names followed by vectors of
  row values."
  [rs opts]
  (as-modified-arrays rs (assoc opts
                                :qualifier-fn lower-case
                                :label-fn lower-case)))

(defn as-unqualified-lower-arrays
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces a vector of simple, lower-case column names followed by
  vectors of row values."
  [rs opts]
  (as-unqualified-modified-arrays rs (assoc opts :label-fn lower-case)))

(defn as-arrays-adapter
  "Given an array builder function (e.g., `as-unqualified-arrays`) and a column
  reading function, return a new builder function that uses that column reading
  function instead of `.getObject` so you can override the default behavior.

  The default column-reader behavior would be equivalent to:

      (defn default-column-reader
        [^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
        (.getObject rs i))

  Your column-reader can use the result set metadata to determine whether
  to call `.getObject` or some other method to read the column's value.

  `read-column-by-index` is still called on the result of that read.

  Note: this is different behavior to `builder-adapter`'s `column-by-index-fn`."
  [builder-fn column-reader]
  (builder-adapter builder-fn
                   (fn [builder rs i]
                     (let [^ResultSetMetaData rsmeta (:rsmeta builder)]
                       (read-column-by-index (column-reader rs rsmeta i)
                                             rsmeta
                                             i)))))

(declare navize-row)
(declare navable-row)

(defprotocol DatafiableRow
  "Protocol for making rows datafiable and therefore navigable.

  The default implementation just adds metadata so that `datafy` can be
  called on the row, which will produce something that `nav` can be called
  on, to lazily navigate through foreign key relationships into other tables.

  If `datafiable-row` is called when reducing the result set produced by
  `next.jdbc/plan`, the row is fully-realized from the `ResultSet`
  first, using the `:builder-fn` (or `as-maps` by default)."
  (datafiable-row [this connectable opts]
    "Produce a datafiable representation of a row from a `ResultSet`."))

(defn- row-builder
  "Given a `RowBuilder` -- a row materialization strategy -- produce a fully
  materialized row from it."
  [builder]
  (->> (reduce (fn [r i] (with-column builder r i))
               (->row builder)
               (range 1 (inc (column-count builder))))
       (row! builder)))

(definterface MapifiedResultSet)

(defprotocol InspectableMapifiedResultSet
  "Protocol for exposing aspects of the (current) result set via functions.

  The intent here is to expose information that is associated with either
  the (current row of the) result set or the result set metadata, via
  functions that can be called inside a reducing function being used over
  `next.jdbc/plan`, including situations where the reducing function has
  to realize a row by calling `datafiable-row` but still wants to call
  these functions on the (realized) row."
  :extend-via-metadata true
  (row-number [this]
    "Return the current 1-based row number, if available.

    Should not cause any row realization.")
  (column-names [this]
    "Return a vector of the column names from the result set.

    Reifies the result builder, in order to construct column names,
    but should not cause any row realization.")
  (metadata [this]
    "Return the raw `ResultSetMetaData` object from the result set.

    Should not cause any row realization.

    If `next.jdbc.datafy` has been required, this metadata will be
    fully-realized as a Clojure data structure, otherwise this should
    not be allowed to 'leak' outside of the reducing function as it may
    depend on the connection remaining open, in order to be valid."))

(defn- mapify-result-set
  "Given a `ResultSet`, return an object that wraps the current row as a hash
  map. Note that a result set is mutable and the current row will change behind
  this wrapper so operations need to be eager (and fairly limited).

  Supports `IPersistentMap` in full. Any operation that requires a full hash
  map (`assoc`, `dissoc`, `cons`, `seq`, etc) will cause a full row to be
  realized (via `row-builder` above). The result will be a regular map: if
  you want the row to be datafiable/navigable, use `datafiable-row` to
  realize the full row explicitly before performing other
  (metadata-preserving) operations on it."
  [^ResultSet rs opts]
  (let [builder (delay ((get opts :builder-fn as-maps) rs opts))
        name-fn (if (contains? opts :column-fn)
                  (comp (get opts :column-fn) name)
                  name)]
    (reify

      MapifiedResultSet
      ;; marker, just for printing resolution

      InspectableMapifiedResultSet
      (row-number   [this] (.getRow rs))
      (column-names [this] (:cols @builder))
      (metadata     [this] (d/datafy (.getMetaData rs)))

      clojure.lang.IPersistentMap
      (assoc [this k v]
        (assoc (row-builder @builder) k v))
      (assocEx [this k v]
        (.assocEx ^clojure.lang.IPersistentMap (row-builder @builder) k v))
      (without [this k]
        (dissoc (row-builder @builder) k))

      java.lang.Iterable ; Java 7 compatible: no forEach / spliterator
      (iterator [this]
        (.iterator ^java.lang.Iterable (row-builder @builder)))

      clojure.lang.Associative
      (containsKey [this k]
        (try
          (.getObject rs ^String (name-fn k))
          true
          (catch SQLException _
            false)))
      (entryAt [this k]
        (try
          (clojure.lang.MapEntry. k (read-column-by-label
                                     (.getObject rs ^String (name-fn k))
                                     ^String (name-fn k)))
          (catch SQLException _)))

      clojure.lang.Counted
      (count [this]
        (column-count @builder))

      clojure.lang.IPersistentCollection
      (cons [this obj]
        (let [row (row-builder @builder)]
          (conj row obj)))
      (empty [this]
        {})
      (equiv [this obj]
        (.equiv ^clojure.lang.IPersistentCollection (row-builder @builder) obj))

      ;; we support get with a numeric key for array-based builders:
      clojure.lang.ILookup
      (valAt [this k]
        (try
          (if (number? k)
            (let [^Integer i (inc k)]
              (read-column-by-index (.getObject rs i) (:rsmeta @builder) i))
            (read-column-by-label (.getObject rs ^String (name-fn k)) ^String (name-fn k)))
          (catch SQLException _)))
      (valAt [this k not-found]
        (try
          (if (number? k)
            (let [^Integer i (inc k)]
              (read-column-by-index (.getObject rs i) (:rsmeta @builder) i))
            (read-column-by-label (.getObject rs ^String (name-fn k)) ^String (name-fn k)))
          (catch SQLException _
            not-found)))

      ;; we support nth for array-based builders (i is primitive int here!):
      clojure.lang.Indexed
      (nth [this i]
        (try
          (let [i (inc i)]
            (read-column-by-index (.getObject rs i) (:rsmeta @builder) i))
          (catch SQLException _)))
      (nth [this i not-found]
        (try
          (let [i (inc i)]
            (read-column-by-index (.getObject rs i) (:rsmeta @builder) i))
          (catch SQLException _
            not-found)))

      clojure.lang.Seqable
      (seq [this]
        (seq (row-builder @builder)))

      DatafiableRow
      (datafiable-row [this connectable opts]
        ;; since we have to call these eagerly, we trap any exceptions so
        ;; that they can be thrown when the actual functions are called
        (let [row  (try (.getRow rs)     (catch Throwable t t))
              cols (try (:cols @builder) (catch Throwable t t))
              meta (try (d/datafy (.getMetaData rs)) (catch Throwable t t))]
          (vary-meta
           (row-builder @builder)
           assoc
           `core-p/datafy
           (navize-row connectable opts)
           `core-p/nav
           (navable-row connectable opts)
           `row-number
           (fn [_] (if (instance? Throwable row) (throw row) row))
           `column-names
           (fn [_] (if (instance? Throwable cols) (throw cols) cols))
           `metadata
           (fn [_] (if (instance? Throwable meta) (throw meta) meta)))))

      (toString [_]
        (try
          (str (row-builder @builder))
          (catch Throwable _
            "{row} from `plan` -- missing `map` or `reduce`?"))))))

(defmethod print-dup MapifiedResultSet [rs ^java.io.Writer w]
  (.write w (str rs)))

(prefer-method print-dup MapifiedResultSet clojure.lang.IPersistentMap)

(defmethod print-method MapifiedResultSet [rs ^java.io.Writer w]
  (.write w (str rs)))

(prefer-method print-method MapifiedResultSet clojure.lang.IPersistentMap)

(extend-protocol
  DatafiableRow
  ResultSet
  (datafiable-row [this connectable opts]
    (let [mapped (mapify-result-set this opts)]
      (datafiable-row mapped connectable opts))))

(extend-protocol
  DatafiableRow
  clojure.lang.IObj ; assume we can "navigate" anything that accepts metadata
  ;; in reality, this is going to be over-optimistic and will like cause `nav`
  ;; to fail on attempts to navigate into result sets that are not hash maps
  (datafiable-row [this connectable opts]
                  (vary-meta
                   this
                   assoc
                   `core-p/datafy (navize-row  connectable opts)
                   `core-p/nav    (navable-row connectable opts))))

(defn datafiable-result-set
  "Given a ResultSet, a connectable, and an options hash map, return a fully
  realized, datafiable result set per the `:builder-fn` option passed in.
  If no `:builder-fn` option is provided, `as-maps` is used as the default.

  The connectable and the options can both be omitted. If connectable is
  omitted, `nil` is used and no foreign key navigation will be available
  for any datafied result. If you want to pass a hash map as the connectable,
  you must also pass an options hash map.

  This can be used to process regular result sets or metadata result sets."
  ([rs]
   (datafiable-result-set rs nil {}))
  ([rs conn-or-opts]
   (datafiable-result-set
    rs
    (if (map? conn-or-opts) nil conn-or-opts)
    (if (map? conn-or-opts) conn-or-opts {})))
  ([^java.sql.ResultSet rs connectable opts]
   (let [builder-fn (get opts :builder-fn as-maps)
         builder    (builder-fn rs opts)]
     (loop [rs' (->rs builder) more? (.next rs)]
       (if more?
         (recur (with-row builder rs'
                  (datafiable-row (row-builder builder) connectable opts))
                (.next rs))
         (rs! builder rs'))))))

;; make it available to next.jdbc.prepare
(vreset! @#'prepare/d-r-s datafiable-result-set)

(defn- stmt->result-set
  "Given a `PreparedStatement` and options, execute it and return a `ResultSet`
  if possible."
  ^ResultSet
  [^PreparedStatement stmt opts]
  (if (.execute stmt)
    (.getResultSet stmt)
    (when (:return-keys opts)
      (try
        (.getGeneratedKeys stmt)
        (catch Exception _)))))

(defn- stmt->result-set-update-count
  "Given a connectable, a `Statement`, a flag indicating there might
  be a result set, and options, return a (datafiable) result set if possible
  (either from the statement or from generated keys). If no result set is
  available, return a 'fake' result set containing the update count.

  If no update count is available either, return nil."
  [connectable ^Statement stmt go opts]
  (if-let [^ResultSet
           rs (if go
                (.getResultSet stmt)
                (when (:return-keys opts)
                  (try
                    (.getGeneratedKeys stmt)
                    (catch Exception _))))]
    (datafiable-result-set rs connectable opts)
    (let [n (.getUpdateCount stmt)]
      (when-not (= -1 n)
        [{:next.jdbc/update-count n}]))))

(defn- reduce-result-set
  "Given a `ResultSet`, a reducing function, an initial value, and options,
  perform a reduction of the result set. This is used internally by higher
  level `reduce-*` functions."
  [^ResultSet rs f init opts]
  (let [rs-map (mapify-result-set rs opts)]
    (loop [init' init]
      (if (.next rs)
        (let [result (f init' rs-map)]
          (if (reduced? result)
            @result
            (recur result)))
        init'))))

(defn- reduce-stmt
  "Execute the `PreparedStatement`, attempt to get either its `ResultSet` or
  its generated keys (as a `ResultSet`), and reduce that using the supplied
  function and initial value.

  If the statement yields neither a `ResultSet` nor generated keys, return
  a hash map containing `:next.jdbc/update-count` and the number of rows
  updated, with the supplied function and initial value applied."
  [^PreparedStatement stmt f init opts]
  (if-let [rs (stmt->result-set stmt opts)]
    (reduce-result-set rs f init opts)
    (f init {:next.jdbc/update-count (.getUpdateCount stmt)})))

;; ForkJoinTask wrappers copied in from clojure.core.reducers to avoid
;; relying on private functionality that might possibly change over time

(defn- fjtask [^Callable f]
  (ForkJoinTask/adapt f))

(defn- fjinvoke
  "For now, this still relies on clojure.core.reducers/pool which is
  public but undocumented."
  [f]
  (if (ForkJoinTask/inForkJoinPool)
    (f)
    (.invoke ^ForkJoinPool @r/pool ^ForkJoinTask (fjtask f))))

(defn- fjfork [task] (.fork ^ForkJoinTask task))

(defn- fjjoin [task] (.join ^ForkJoinTask task))

(defn- fold-result-set
  "Given a `ResultSet`, a batch size, combining and reducing functions,
  a connectable, and options, perform a fold of the result set. This is
  used internally by higher level `fold-*` functions."
  [^ResultSet rs n combinef reducef connectable opts]
  (let [rs-map  (mapify-result-set rs opts)
        chunk   (fn [batch] (fjtask #(r/reduce reducef (combinef) batch)))
        realize (fn [row] (datafiable-row row connectable opts))]
    (loop [batch [] task nil]
      (if (.next rs)
        (if (= n (count batch))
          (recur [(realize rs-map)]
                 (let [t (fjfork (chunk batch))]
                   (if task
                     (fjfork
                      (fjtask #(combinef (fjjoin task)
                                         (fjjoin t))))
                     t)))
          (recur (conj batch (realize rs-map)) task))
        (if (seq batch)
          (let [t (fjfork (chunk batch))]
            (fjinvoke
             #(combinef (if task (fjjoin task) (combinef))
                        (fjjoin t))))
          (if task
            (fjinvoke
             #(combinef (combinef) (fjjoin task)))
            (combinef)))))))

(defn reducible-result-set
  "Given a `ResultSet`, return an `IReduceInit` that can be reduced. An
  options hash map may be provided.

  You are responsible for ensuring the `Connection` for this `ResultSet`
  remains open until the reduction is complete!"
  ([rs]
   (reducible-result-set rs {}))
  ([rs opts]
   (reify
     clojure.lang.IReduceInit
     (reduce [_ f init]
       (reduce-result-set rs f init opts)))))

(defn foldable-result-set
  "Given a `ResultSet` and an optional connectable, return an `r/CollFold`
  that can be folded. An options hash map may be provided.

  You are responsible for ensuring the `Connection` for this `ResultSet`
  and the connectable both remain open until the fold is complete!

  If the connectable is omitted, no foreign key navigation would be
  available in any datafied result. If you want to pass a hash map as the
  connectable, you must also pass an options hash map."
  ([rs]
   (foldable-result-set rs nil {}))
  ([rs conn-or-opts]
   (foldable-result-set
    rs
    (if (map? conn-or-opts) nil conn-or-opts)
    (if (map? conn-or-opts) conn-or-opts {})))
  ([rs connectable opts]
   (reify
     r/CollFold
     (coll-fold [_ n combinef reducef]
       (fold-result-set rs n combinef reducef connectable opts)))))

(defn- fold-stmt
  "Execute the `PreparedStatement`, attempt to get either its `ResultSet` or
  its generated keys (as a `ResultSet`), and fold that using the supplied
  batch size, combining function, and reducing function.

  If the statement yields neither a `ResultSet` nor generated keys, produce
  a hash map containing `:next.jdbc/update-count` and the number of rows
  updated, and fold that as a single element collection."
  [^PreparedStatement stmt n combinef reducef connectable opts]
  (if-let [rs (stmt->result-set stmt opts)]
    (fold-result-set rs n combinef reducef connectable opts)
    (reducef (combinef) {:next.jdbc/update-count (.getUpdateCount stmt)})))

(defn- stmt-sql->result-set
  "Given a `Statement`, a SQL command, execute it and return a
  `ResultSet` if possible. We always attempt to return keys."
  ^ResultSet
  [^Statement stmt ^String sql]
  (if (.execute stmt sql)
    (.getResultSet stmt)
    (try
      (.getGeneratedKeys stmt)
      (catch Exception _))))

(defn- reduce-stmt-sql
  "Execute the SQL command on the given `Statement`, attempt to get either
  its `ResultSet` or its generated keys (as a `ResultSet`), and reduce
  that using the supplied function and initial value.

  If the statement yields neither a `ResultSet` nor generated keys, return
  a hash map containing `:next.jdbc/update-count` and the number of rows
  updated, with the supplied function and initial value applied."
  [^Statement stmt sql f init opts]
  (if-let [rs (stmt-sql->result-set stmt sql)]
    (reduce-result-set rs f init opts)
    (f init {:next.jdbc/update-count (.getUpdateCount stmt)})))

(defn- fold-stmt-sql
  "Execute the SQL command on the given `Statement`, attempt to get either
  its `ResultSet` or its generated keys (as a `ResultSet`), and fold that
  using the supplied batch size, combining function, and reducing function.

  If the statement yields neither a `ResultSet` nor generated keys, produce
  a hash map containing `:next.jdbc/update-count` and the number of rows
  updated, and fold that as a single element collection."
  [^Statement stmt sql n combinef reducef connectable opts]
  (if-let [rs (stmt-sql->result-set stmt sql)]
    (fold-result-set rs n combinef reducef connectable opts)
    (reducef (combinef) {:next.jdbc/update-count (.getUpdateCount stmt)})))

(extend-protocol p/Executable
  java.sql.Connection
  (-execute [this sql-params opts]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init]
        (with-open [stmt (prepare/create this
                                         (first sql-params)
                                         (rest sql-params)
                                         opts)]
          (reduce-stmt stmt f init opts)))
      r/CollFold
      (coll-fold [_ n combinef reducef]
        (with-open [stmt (prepare/create this
                                         (first sql-params)
                                         (rest sql-params)
                                         opts)]
          (fold-stmt stmt n combinef reducef this opts)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (-execute-one [this sql-params opts]
    (with-open [stmt (prepare/create this
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (if-let [rs (stmt->result-set stmt opts)]
        (let [builder-fn (get opts :builder-fn as-maps)
              builder    (builder-fn rs opts)]
          (when (.next rs)
            (datafiable-row (row-builder builder) this opts)))
        {:next.jdbc/update-count (.getUpdateCount stmt)})))
  (-execute-all [this sql-params opts]
    (with-open [stmt (prepare/create this
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (if (:multi-rs opts)
        (loop [go (.execute stmt) acc []]
          (if-let [rs (stmt->result-set-update-count this stmt go opts)]
            (recur (.getMoreResults stmt) (conj acc rs))
            acc))
        (if-let [rs (stmt->result-set stmt opts)]
          (datafiable-result-set rs this opts)
          [{:next.jdbc/update-count (.getUpdateCount stmt)}]))))

  javax.sql.DataSource
  (-execute [this sql-params opts]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init]
        (with-open [con  (p/get-connection this opts)
                    stmt (prepare/create con
                                         (first sql-params)
                                         (rest sql-params)
                                         opts)]
            (reduce-stmt stmt f init opts)))
      r/CollFold
      (coll-fold [_ n combinef reducef]
        (with-open [con  (p/get-connection this opts)
                    stmt (prepare/create con
                                         (first sql-params)
                                         (rest sql-params)
                                         opts)]
          (fold-stmt stmt n combinef reducef this opts)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (-execute-one [this sql-params opts]
    (with-open [con  (p/get-connection this opts)
                stmt (prepare/create con
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
        (if-let [rs (stmt->result-set stmt opts)]
          (let [builder-fn (get opts :builder-fn as-maps)
                builder    (builder-fn rs opts)]
            (when (.next rs)
                  (datafiable-row (row-builder builder) this opts)))
          {:next.jdbc/update-count (.getUpdateCount stmt)})))
  (-execute-all [this sql-params opts]
    (with-open [con  (p/get-connection this opts)
                stmt (prepare/create con
                                     (first sql-params)
                                     (rest sql-params)
                                     opts)]
      (if (:multi-rs opts)
        (loop [go (.execute stmt) acc []]
          (if-let [rs (stmt->result-set-update-count this stmt go opts)]
            (recur (.getMoreResults stmt) (conj acc rs))
            acc))
        (if-let [rs (stmt->result-set stmt opts)]
          (datafiable-result-set rs this opts)
          [{:next.jdbc/update-count (.getUpdateCount stmt)}]))))

  java.sql.PreparedStatement
  ;; we can't tell if this PreparedStatement will return generated
  ;; keys so we pass a truthy value to at least attempt it if we
  ;; do not get a ResultSet back from the execute call
  (-execute [this _ opts]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init]
        (reduce-stmt this f init (assoc opts :return-keys true)))
      r/CollFold
      (coll-fold [_ n combinef reducef]
        (fold-stmt this n combinef reducef (.getConnection this)
                   (assoc opts :return-keys true)))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (-execute-one [this _ opts]
    (if-let [rs (stmt->result-set this (assoc opts :return-keys true))]
      (let [builder-fn (get opts :builder-fn as-maps)
            builder    (builder-fn rs opts)]
        (when (.next rs)
          (datafiable-row (row-builder builder)
                          (.getConnection this) opts)))
      {:next.jdbc/update-count (.getUpdateCount this)}))
  (-execute-all [this _ opts]
    (if (:multi-rs opts)
      (loop [go (.execute this) acc []]
        (if-let [rs (stmt->result-set-update-count
                     (.getConnection this) this go (assoc opts :return-keys true))]
          (recur (.getMoreResults this) (conj acc rs))
          acc))
      (if-let [rs (stmt->result-set this (assoc opts :return-keys true))]
        (datafiable-result-set rs (.getConnection this) opts)
        [{:next.jdbc/update-count (.getUpdateCount this)}])))

  java.sql.Statement
  (-execute [this sql-params opts]
    (assert (= 1 (count sql-params))
            "Parameters cannot be provided when executing a non-prepared Statement")
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init]
        (reduce-stmt-sql this (first sql-params) f init opts))
      r/CollFold
      (coll-fold [_ n combinef reducef]
        (fold-stmt-sql this (first sql-params) n combinef reducef
                       (.getConnection this) opts))
      (toString [_] "`IReduceInit` from `plan` -- missing reduction?")))
  (-execute-one [this sql-params opts]
    (assert (= 1 (count sql-params))
            "Parameters cannot be provided when executing a non-prepared Statement")
    (if-let [rs (stmt-sql->result-set this (first sql-params))]
      (let [builder-fn (get opts :builder-fn as-maps)
            builder    (builder-fn rs opts)]
        (when (.next rs)
          (datafiable-row (row-builder builder)
                          (.getConnection this) opts)))
      {:next.jdbc/update-count (.getUpdateCount this)}))
  (-execute-all [this sql-params opts]
    (assert (= 1 (count sql-params))
            "Parameters cannot be provided when executing a non-prepared Statement")
    (if (:multi-rs opts)
      (loop [go (.execute this (first sql-params)) acc []]
        (if-let [rs (stmt->result-set-update-count
                     (.getConnection this) this go (assoc opts :return-keys true))]
          (recur (.getMoreResults this) (conj acc rs))
          acc))
      (if-let [rs (stmt-sql->result-set this (first sql-params))]
        (datafiable-result-set rs (.getConnection this) opts)
        [{:next.jdbc/update-count (.getUpdateCount this)}])))

  Object
  (-execute [this sql-params opts]
    (p/-execute (p/get-datasource this) sql-params opts))
  (-execute-one [this sql-params opts]
    (p/-execute-one (p/get-datasource this) sql-params opts))
  (-execute-all [this sql-params opts]
    (p/-execute-all (p/get-datasource this) sql-params opts)))

(defn- default-schema
  "The default schema lookup rule for column names.

  If a column name ends with `_id` or `id`, it is assumed to be a foreign key
  into the table identified by the first part of the column name."
  [col]
  (let [[_ table] (re-find #"(?i)^(.+?)_?id$" (name col))]
    (when table
      [(keyword table) :id])))

(defn- expand-schema
  "Given a (possibly nil) schema entry, return it expanded to a triple of:

  [table fk cardinality]

  Possibly schema entry input formats are:
  * [table fk] => cardinality :one
  * [table fk cardinality] -- no change
  * :table/fk => [:table :fk :one]
  * [:table/fk] => [:table :fk :many]"
  [k entry]
  (when entry
    (if-let [mapping
             (cond
              (keyword? entry)
              [(keyword (namespace entry)) (keyword (name entry)) :one]

              (coll? entry)
              (let [[table fk cardinality] entry]
                (cond (and table fk cardinality)
                      entry

                      (and table fk)
                      [table fk :one]

                      (keyword? table)
                      [(keyword (namespace table)) (keyword (name table)) :many])))]

      mapping
      (throw (ex-info (str "Invalid schema entry for: " (name k)) {:entry entry})))))

(comment
  (expand-schema :user/statusid nil)
  (expand-schema :user/statusid :status/id)
  (expand-schema :user/statusid [:status :id])
  (expand-schema :user/email    [:deliverability :email :many])
  (expand-schema :user/email    [:deliverability/email]))

(defn- navize-row
  "Given a connectable object, return a function that knows how to turn a row
  into a `nav`igable object.

  A `:schema` option can provide a map from qualified column names
  (`:<table>/<column>`) to tuples that indicate for which table they are a
  foreign key, the name of the key within that table, and (optionality) the
  cardinality of that relationship (`:many`, `:one`).

  If no `:schema` item is provided for a column, the convention of `<table>id` or
  `<table>_id` is used, and the assumption is that such columns are foreign keys
  in the `<table>` portion of their name, the key is called `id`, and the
  cardinality is `:one`.

  Rows are looked up using `-execute-all` or `-execute-one`, and the `:table-fn`
  option, if provided, is applied to the assumed table name and `:column-fn` if
  provided to the assumed foreign key column name."
  [connectable opts]
  (fn [row]
    (vary-meta
     row
     assoc
     `core-p/nav (fn [_ k v]
                   (try
                     (let [[table fk cardinality]
                           (expand-schema k (or (get-in opts [:schema k])
                                                (default-schema k)))]
                       (if (and fk connectable)
                         (let [table-fn  (:table-fn opts identity)
                               column-fn (:column-fn opts identity)
                               exec-fn!  (if (= :many cardinality)
                                           p/-execute-all
                                           p/-execute-one)]
                           (exec-fn! connectable
                                     [(str "SELECT * FROM "
                                           (table-fn (name table))
                                           " WHERE "
                                           (column-fn (name fk))
                                           " = ?")
                                      v]
                                     opts))
                         v))
                     (catch Exception _
                       ;; assume an exception means we just cannot
                       ;; navigate anywhere, so return just the value
                       v))))))

(defn- navable-row
  "Given a connectable object, return a function that knows how to `nav`
  into a row.

  A `:schema` option can provide a map from qualified column names
  (`:<table>/<column>`) to tuples that indicate for which table they are a
  foreign key, the name of the key within that table, and (optionality) the
  cardinality of that relationship (`:many`, `:one`).

  If no `:schema` item is provided for a column, the convention of `<table>id` or
  `<table>_id` is used, and the assumption is that such columns are foreign keys
  in the `<table>` portion of their name, the key is called `id`, and the
  cardinality is `:one`.

  Rows are looked up using `-execute-all` or `-execute-one`, and the `:table-fn`
  option, if provided, is applied to the assumed table name and `:column-fn` if
  provided to the assumed foreign key column name."
  [connectable opts]
  (fn [_ k v]
    (try
      (let [[table fk cardinality]
            (expand-schema k (or (get-in opts [:schema k])
                                 (default-schema k)))]
        (if (and fk connectable)
          (let [table-fn  (:table-fn opts identity)
                column-fn (:column-fn opts identity)
                exec-fn!  (if (= :many cardinality)
                            p/-execute-all
                            p/-execute-one)]
            (exec-fn! connectable
                      [(str "SELECT * FROM "
                            (table-fn (name table))
                            " WHERE "
                            (column-fn (name fk))
                            " = ?")
                       v]
                      opts))
          v))
      (catch Exception _
                       ;; assume an exception means we just cannot
                       ;; navigate anywhere, so return just the value
        v))))
