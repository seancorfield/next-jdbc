;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.optional
  "Builders that treat NULL SQL values as 'optional' and omit the
  corresponding keys from the Clojure hash maps for the rows."
  (:require [next.jdbc.result-set :as rs])
  (:import (java.sql ResultSet)))

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

(defn as-lower-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with lower-case keys and nil
  columns omitted."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (rs/get-lower-column-names rsmeta opts)]
    (->MapResultSetOptionalBuilder rs rsmeta cols)))

(defn as-unqualified-lower-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple, lower-case keys
  and nil columns omitted."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (rs/get-unqualified-lower-column-names rsmeta opts)]
    (->MapResultSetOptionalBuilder rs rsmeta cols)))
