;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  ""
  (:require [next.jdbc.connection] ; used to extend protocols
            [next.jdbc.prepare :as prepare] ; used to extend protocols
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [next.jdbc.transaction])) ; used to extend protocols

(set! *warn-on-reflection* true)

(defn get-datasource [spec] (p/get-datasource spec))

(defn get-connection [spec opts] (p/get-connection spec opts))

(defn prepare [spec sql-params opts] (p/prepare spec sql-params opts))

(defn reducible!
  "General SQL execution function.

  Returns a reducible that, when reduced, runs the SQL and yields the result."
  ([stmt] (p/-execute stmt [] {}))
  ([connectable sql-params & [opts]]
   (p/-execute connectable sql-params opts)))

(defn execute!
  ""
  ([stmt]
   (rs/execute! stmt [] {}))
  ([connectable sql-params]
   (rs/execute! connectable sql-params {}))
  ([connectable sql-params opts]
   (rs/execute! connectable sql-params opts)))

(defn execute-one!
  ""
  ([stmt]
   (rs/execute-one! stmt [] {}))
  ([connectable sql-params]
   (rs/execute-one! connectable sql-params {}))
  ([connectable sql-params opts]
   (rs/execute-one! connectable sql-params opts)))

(defmacro with-transaction
  [[sym connectable opts] & body]
  `(p/-transact ~connectable (fn [~sym] ~@body) ~opts))

(defn insert!
  ""
  ([connectable table key-map]
   (rs/execute! connectable
                (sql/for-insert table key-map {})
                {:return-keys true}))
  ([connectable table key-map opts]
   (rs/execute! connectable
                (sql/for-insert table key-map opts)
                (merge {:return-keys true} opts))))

(defn insert-multi!
  ""
  ([connectable table cols rows]
   (rs/execute! connectable
                (sql/for-insert-multi table cols rows {})
                {:return-keys true}))
  ([connectable table cols rows opts]
   (rs/execute! connectable
                (sql/for-insert-multi table cols rows opts)
                (merge {:return-keys true} opts))))

(defn find-by-keys
  ""
  ([connectable table key-map]
   (rs/execute! connectable (sql/for-query table key-map {}) {}))
  ([connectable table key-map opts]
   (rs/execute! connectable (sql/for-query table key-map opts) opts)))

(defn get-by-id
  ""
  ([connectable table pk]
   (rs/execute-one! connectable (sql/for-query table {:id pk} {}) {}))
  ([connectable table pk opts]
   (rs/execute-one! connectable (sql/for-query table {:id pk} opts) opts))
  ([connectable table pk pk-name opts]
   (rs/execute-one! connectable (sql/for-query table {pk-name pk} opts) opts)))

(defn update!
  ""
  ([connectable table key-map where-params]
   (rs/execute! connectable (sql/for-update table key-map where-params {}) {}))
  ([connectable table key-map where-params opts]
   (rs/execute! connectable (sql/for-update table key-map where-params opts) opts)))

(defn delete!
  ""
  ([connectable table where-params]
   (rs/execute! connectable (sql/for-delete table where-params {}) {}))
  ([connectable table where-params opts]
   (rs/execute! connectable (sql/for-delete table where-params opts) opts)))
