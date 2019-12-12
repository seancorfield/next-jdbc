;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns ^:no-doc next.jdbc.transaction
  "Implementation of SQL transaction logic."
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection)))

(set! *warn-on-reflection* true)

(def ^:private isolation-levels
  "Transaction isolation levels."
  {:none             Connection/TRANSACTION_NONE
   :read-committed   Connection/TRANSACTION_READ_COMMITTED
   :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED
   :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
   :serializable     Connection/TRANSACTION_SERIALIZABLE})

(defn- transact*
  "Run the given function inside a transaction created on the given connection.

  Isolation level options can be provided, as well as marking the transaction
  as read-only and/or rollback-only (so it will automatically rollback
  instead of committing any changes)."
  [^Connection con f opts]
  (let [isolation      (:isolation opts)
        read-only      (:read-only opts)
        rollback-only  (:rollback-only opts)
        old-autocommit (.getAutoCommit con)
        old-isolation  (.getTransactionIsolation con)
        old-readonly   (.isReadOnly con)
        restore-ac?    (volatile! true)]
    (io!
     (when isolation
       (.setTransactionIsolation con (isolation isolation-levels)))
     (when read-only
       (.setReadOnly con true))
     (.setAutoCommit con false)
     (try
       (let [result (f con)]
         (if rollback-only
           (do
             ;; per #80: if the rollback operation fails, we do not
             ;; want to try to restore auto-commit...
             (vreset! restore-ac? false)
             (.rollback con)
             (vreset! restore-ac? true))
           (.commit con))
         result)
       (catch Throwable t
         (try
           ;; per #80: if the rollback operation fails, we do not
           ;; want to try to restore auto-commit...
           (vreset! restore-ac? false)
           (.rollback con)
           (vreset! restore-ac? true)
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
         (when @restore-ac?
           (try ; only restore auto-commit if rollback did not fail
             (.setAutoCommit con old-autocommit)
             (catch Exception _)))
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
