;; copyright (c) 2021 Sean Corfield, all rights reserved

(ns ^:no-doc next.jdbc.sql-logging
  "Implementation of sql-logging logic."
  (:require [next.jdbc.protocols :as p]))

(set! *warn-on-reflection* true)

(defrecord SQLLogging [connectable sql-logger result-logger])

(extend-protocol p/Sourceable
  SQLLogging
  (get-datasource [this]
                  (p/get-datasource (:connectable this))))

(extend-protocol p/Connectable
  SQLLogging
  (get-connection [this opts]
                  (p/get-connection (:connectable this)
                                    (merge (:options this) opts))))

(defn- result-logger-helper [result this sym state]
  (when-let [logger (:result-logger this)]
    (logger sym state result)))

(extend-protocol p/Executable
  SQLLogging
  (-execute [this sql-params opts]
            ;; no result-logger call possible:
            ((:sql-logger this) 'next.jdbc/plan sql-params)
            (p/-execute (:connectable this) sql-params
                        (merge (:options this) opts)))
  (-execute-one [this sql-params opts]
                (let [state ((:sql-logger this) 'next.jdbc/execute-one! sql-params)]
                  (try
                    (doto (p/-execute-one (:connectable this) sql-params
                                          (merge (:options this) opts))
                      (result-logger-helper this 'next.jdbc/execute-one! state))
                    (catch Throwable t
                      (result-logger-helper t this 'next.jdbc/execut-one! state)
                      (throw t)))))
  (-execute-all [this sql-params opts]
                (let [state ((:sql-logger this) 'next.jdbc/execute! sql-params)]
                  (try
                    (doto (p/-execute-all (:connectable this) sql-params
                                          (merge (:options this) opts))
                      (result-logger-helper this 'next.jdbc/execute! state))
                    (catch Throwable t
                      (result-logger-helper t this 'next.jdbc/execut-one! state)
                      (throw t))))))

(extend-protocol p/Preparable
  SQLLogging
  (prepare [this sql-params opts]
           ;; no result-logger call possible:
           ((:sql-logger this) 'next.jdbc/prepare sql-params)
           (p/prepare (:connectable this) sql-params
                      (merge (:options this) opts))))

(extend-protocol p/Transactable
  SQLLogging
  (-transact [this body-fn opts]
             (p/-transact (:connectable this) body-fn
                          (merge (:options this) opts))))
