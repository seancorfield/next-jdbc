;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.optional
  "Builders that treat NULL SQL values as 'optional' and omit the
  corresponding keys from the Clojure hash maps for the rows."
  (:require [next.jdbc.result-set :as rs])
  (:import (java.sql ResultSet)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(defrecord MapResultSetOptionalBuilder [^ResultSet rs rsmeta cols]
  rs/RowBuilder
  (->row [this] (transient {}))
  (column-count [this] (count cols))
  (with-column [this row i]
    (let [v (.getObject rs ^Integer i)]
      (if (nil? v)
        row
        (assoc! row
                (nth cols (dec i))
                (rs/read-column-by-index v rsmeta i)))))
  (row! [this row] (persistent! row))
  rs/ResultSetBuilder
  (->rs [this] (transient []))
  (with-row [this mrs row]
    (conj! mrs row))
  (rs! [this mrs] (persistent! mrs)))

(defn as-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with nil columns omitted."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (rs/get-column-names rsmeta opts)]
    (->MapResultSetOptionalBuilder rs rsmeta cols)))

(defn as-unqualified-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple keys and nil
  columns omitted."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (rs/get-unqualified-column-names rsmeta opts)]
    (->MapResultSetOptionalBuilder rs rsmeta cols)))

(defn as-modified-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with modified keys and nil
  columns omitted.

  Requires both the `:qualifier-fn` and `:label-fn` options."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (rs/get-modified-column-names rsmeta opts)]
    (->MapResultSetOptionalBuilder rs rsmeta cols)))

(defn as-unqualified-modified-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple, modified keys
  and nil columns omitted.

  Requires the `:label-fn` option."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (rs/get-unqualified-modified-column-names rsmeta opts)]
    (->MapResultSetOptionalBuilder rs rsmeta cols)))

(defn- lower-case
  "Converts a string to lower case in the US locale to avoid problems in
  locales where the lower case version of a character is not a valid SQL
  entity name (e.g., Turkish)."
  [^String s]
  (.toLowerCase s (Locale/US)))

(defn as-lower-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with lower-case keys and nil
  columns omitted."
  [rs opts]
  (as-modified-maps rs (assoc opts
                              :qualifier-fn lower-case
                              :label-fn lower-case)))

(defn as-unqualified-lower-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple, lower-case keys
  and nil columns omitted."
  [rs opts]
  (as-unqualified-modified-maps rs (assoc opts :label-fn lower-case)))

(defn as-maps-adapter
  "Given a map builder function (e.g., `as-lower-maps`) and a column reading
  function, return a new builder function that uses that column reading
  function instead of `.getObject` so you can override the default behavior.

  This adapter omits SQL NULL values.

  The default column-reader behavior would be equivalent to:

      (defn default-column-reader
        [^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
        (.getObject rs i))

  Your column-reader can use the result set metadata to determine whether
  to call `.getObject` or some other method to read the column's value.

  `read-column-by-index` is still called on the result of that read, if
  it is not `nil`."
  [builder-fn column-reader]
  (fn [rs opts]
    (let [mrsb (builder-fn rs opts)]
      (reify
        rs/RowBuilder
        (->row [this] (rs/->row mrsb))
        (column-count [this] (rs/column-count mrsb))
        (with-column [this row i]
          (let [v (column-reader rs (:rsmeta mrsb) i)]
            (if (nil? v)
              row
              (assoc! row
                      (nth (:cols mrsb) (dec i))
                      (rs/read-column-by-index v (:rsmeta mrsb) i)))))
        (row! [this row] (rs/row! mrsb row))
        rs/ResultSetBuilder
        (->rs [this] (rs/->rs mrsb))
        (with-row [this mrs row] (rs/with-row mrsb mrs row))
        (rs! [this mrs] (rs/rs! mrsb mrs))))))
