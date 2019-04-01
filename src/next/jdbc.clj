;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  ""
  (:require [next.jdbc.connection] ; used to extend protocols
            [next.jdbc.prepare :as prepare] ; used to extend protocols
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.transaction :as tx])) ; used to extend protocols

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
