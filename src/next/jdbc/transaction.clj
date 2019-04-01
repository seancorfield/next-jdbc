;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.transaction
  ""
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection
                     SQLException)))

(set! *warn-on-reflection* true)

(def ^:private isolation-levels
  "Transaction isolation levels."
  {:none             Connection/TRANSACTION_NONE
   :read-committed   Connection/TRANSACTION_READ_COMMITTED
   :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED
   :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
   :serializable     Connection/TRANSACTION_SERIALIZABLE})

(defn- transact*
  ""
  [^Connection con f opts]
  (let [{:keys [isolation read-only rollback-only]} opts
        old-autocommit (.getAutoCommit con)
        old-isolation  (.getTransactionIsolation con)
        old-readonly   (.isReadOnly con)]
    (io!
     (when isolation
       (.setTransactionIsolation con (isolation isolation-levels)))
     (when read-only
       (.setReadOnly con true))
     (.setAutoCommit con false)
     (try
       (let [result (f con)]
         (if rollback-only
           (.rollback con)
           (.commit con))
         result)
       (catch Throwable t
         (try
           (.rollback con)
           (catch Throwable rb
             ;; combine both exceptions
             (throw (ex-info (str "Rollback failed handling \""
                                  (.getMessage t)
                                  "\"")
                             {:rollback rb
                              :handling t}))))
         (throw t))
       (finally ; tear down
         ;; the following can throw SQLExceptions but we do not
         ;; want those to replace any exception currently being
         ;; handled -- and if the connection got closed, we just
         ;; want to ignore exceptions here anyway
         (try
           (.setAutoCommit con old-autocommit)
           (catch Exception _))
         (when isolation
           (try
             (.setTransactionIsolation con old-isolation)
             (catch Exception _)))
         (when read-only
           (try
             (.setReadOnly con old-readonly)
             (catch Exception _))))))))

(extend-protocol p/Transactable
  java.sql.Connection
  (-transact [this body-fn opts]
             (transact* this body-fn opts))
  javax.sql.DataSource
  (-transact [this body-fn opts]
             (with-open [con (p/get-connection this opts)]
               (transact* con body-fn opts)))
  Object
  (-transact [this body-fn opts]
             (p/-transact (p/get-datasource this) body-fn opts)))
