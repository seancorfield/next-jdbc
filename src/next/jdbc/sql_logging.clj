;; copyright (c) 2021 Sean Corfield, all rights reserved

(ns ^:no-doc next.jdbc.sql-logging
  "Implementation of sql-logging logic."
  (:require [next.jdbc.protocols :as p]))

(set! *warn-on-reflection* true)

(defrecord SQLLogging [connectable logger])

(extend-protocol p/Sourceable
  SQLLogging
  (get-datasource [this]
                  (p/get-datasource (:connectable this))))

(extend-protocol p/Connectable
  SQLLogging
  (get-connection [this opts]
                  (p/get-connection (:connectable this)
                                    (merge (:options this) opts))))

(extend-protocol p/Executable
  SQLLogging
  (-execute [this sql-params opts]
            ((:logger this) 'plan sql-params)
            (p/-execute (:connectable this) sql-params
                        (merge (:options this) opts)))
  (-execute-one [this sql-params opts]
                ((:logger this) 'execute-one! sql-params)
                (p/-execute-one (:connectable this) sql-params
                                (merge (:options this) opts)))
  (-execute-all [this sql-params opts]
                ((:logger this) 'execute! sql-params)
                (p/-execute-all (:connectable this) sql-params
                                (merge (:options this) opts))))

(extend-protocol p/Preparable
  SQLLogging
  (prepare [this sql-params opts]
           ((:logger this) 'prepare sql-params)
           (p/prepare (:connectable this) sql-params
                      (merge (:options this) opts))))

(extend-protocol p/Transactable
  SQLLogging
  (-transact [this body-fn opts]
             (p/-transact (:connectable this) body-fn
                          (merge (:options this) opts))))
