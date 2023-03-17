;; copyright (c) 2018-2021 Sean Corfield, all rights reserved

(ns next.jdbc.transaction
  "Implementation of SQL transaction logic.

  In general, you cannot nest transactions. `clojure.java.jdbc` would
  ignore any attempt to create a nested transaction, even tho' some
  databases do support it. `next.jdbc` allows you to call `with-transaction`
  even while you are inside an active transaction, but the behavior may
  vary across databases and the commit or rollback boundaries may not be
  what you expect. In order to avoid two transactions constructed on the
  same connection from interfering with each other, `next.jdbc` locks on
  the `Connection` object (this prevents concurrent transactions on separate
  threads from interfering but will cause deadlock on a single thread --
  so beware).

  Consequently, this namespace exposes a dynamic variable, `*nested-tx*`,
  which can be used to vary the behavior when an attempt is made to start
  a transaction when you are already inside a transaction."
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection)))

(set! *warn-on-reflection* true)

(defonce ^:dynamic
  ^{:doc "Controls the behavior when a nested transaction is attempted.

  Possible values are:
  * `:allow` -- the default: assumes you know what you are doing!
  * `:ignore` -- the same behavior as `clojure.java.jdbc`: the nested
      transaction is simply ignored and any SQL operations inside it are
      executed in the context of the outer transaction.
  * `:prohibit` -- any attempt to create a nested transaction will throw
      an exception: this is the safest but most restrictive approach so
      that you can make sure you don't accidentally have any attempts
      to create nested transactions (since that might be a bug in your code)."}
  *nested-tx*
  :allow)

(defonce ^:private ^:dynamic ^{:doc "Used to detect nested transactions."}
  *active-tx* false)

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
    (cond
      (and (not *active-tx*) (= :ignore *nested-tx*))
      ;; #245 do not lock when in c.j.j compatibility mode:
      (binding [*active-tx* true]
        (transact* this body-fn opts))
      (or (not *active-tx*) (= :allow *nested-tx*))
      (locking this
        (binding [*active-tx* true]
          (transact* this body-fn opts)))
      (= :ignore *nested-tx*)
      (body-fn this)
      (= :prohibit *nested-tx*)
      (throw (IllegalStateException. "Nested transactions are prohibited"))
      :else
      (throw (IllegalArgumentException.
              (str "*nested-tx* ("
                   *nested-tx*
                   ") was not :allow, :ignore, or :prohibit")))))
  javax.sql.DataSource
  (-transact [this body-fn opts]
    (cond (or (not *active-tx*) (= :allow *nested-tx*))
      (binding [*active-tx* true]
        (with-open [con (p/get-connection this opts)]
          (transact* con body-fn opts)))
      (= :ignore *nested-tx*)
      (with-open [con (p/get-connection this opts)]
        (body-fn con))
      (= :prohibit *nested-tx*)
      (throw (IllegalStateException. "Nested transactions are prohibited"))
      :else
      (throw (IllegalArgumentException.
              (str "*nested-tx* ("
                   *nested-tx*
                   ") was not :allow, :ignore, or :prohibit")))))
  Object
  (-transact [this body-fn opts]
    (p/-transact (p/get-datasource this) body-fn opts)))
