;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  ""
  (:require [clojure.set :as set])
  (:import (java.sql Connection DriverManager ResultSet ResultSetMetaData
                     SQLException)
           (javax.sql DataSource)
           (java.util Properties)))

(set! *warn-on-reflection* true)

(defprotocol Closeable
  (close [this]))
(defprotocol Connectable
  (get-connection [this opts]))
(defprotocol Preparable
  (->jdbc-connection [this])
  (get-statement [this db-spec sql-params opts]))
(defprotocol Transactable
  (transact [this f opts]))

(defn- get-statement*
  [db-spec con sql-params opts]
  (.prepareStatement con (first sql-params)))

(def ^:private isolation-levels
  "Transaction isolation levels."
  {:none             java.sql.Connection/TRANSACTION_NONE
   :read-committed   java.sql.Connection/TRANSACTION_READ_COMMITTED
   :read-uncommitted java.sql.Connection/TRANSACTION_READ_UNCOMMITTED
   :repeatable-read  java.sql.Connection/TRANSACTION_REPEATABLE_READ
   :serializable     java.sql.Connection/TRANSACTION_SERIALIZABLE})

(def ^:private isolation-kws
  "Map transaction isolation constants to our keywords."
  (set/map-invert isolation-levels))

(defn get-isolation-level
  "Given an actual JDBC connection, return the current transaction
  isolation level, if known. Return :unknown if we do not recognize
  the isolation level."
  [^Connection jdbc]
  (isolation-kws (.getTransactionIsolation jdbc) :unknown))

(defn committable! [con commit?]
  (when-let [state (:transacted con)]
    (reset! state commit?))
  con)

(defn- transact*
  ""
  [db-spec con transacted f opts]
  (let [{:keys [isolation read-only? rollback-only?]}
        (merge (when (map? db-spec) db-spec) opts)
        committable? (not rollback-only?)]
    (if transacted
      ;; should check isolation level; maybe implement save points?
      (f con)
      (with-open [t-con (assoc (get-connection con opts)
                               :transacted (atom committable?))]
        (let [^Connection jdbc (->jdbc-connection t-con)
              old-autocommit   (.getAutoCommit jdbc)
              old-isolation    (.getTransactionIsolation jdbc)
              old-readonly     (.isReadOnly jdbc)]
          (io!
           (when isolation
             (.setTransactionIsolation jdbc (isolation isolation-levels)))
           (when read-only?
             (.setReadOnly jdbc true))
           (.setAutoCommit jdbc false)
           (try
             (let [result (f t-con)]
               (if @(:transacted t-con)
                 (.commit jdbc)
                 (.rollback jdbc))
               result)
             (catch Throwable t
               (try
                 (.rollback jdbc)
                 (catch Throwable rb
                   ;; combine both exceptions
                   (throw (ex-info (str "Rollback failed handling \""
                                        (.getMessage t)
                                        "\"")
                                   {:rollback rb
                                    :handling t}))))
               (throw t))
             (finally ; tear down
               (committable! t-con committable?)
               ;; the following can throw SQLExceptions but we do not
               ;; want those to replace any exception currently being
               ;; handled -- and if the connection got closed, we just
               ;; want to ignore exceptions here anyway
               (try
                 (.setAutoCommit jdbc old-autocommit)
                 (catch Exception _))
               (when isolation
                 (try
                   (.setTransactionIsolation jdbc old-isolation)
                   (catch Exception _)))
               (when read-only?
                 (try
                   (.setReadOnly jdbc old-readonly)
                   (catch Exception _)))))))))))

(defrecord NestedConnection [db-spec con transacted opts]
  Connectable
  (get-connection [this opts']
    (->NestedConnection db-spec con transacted (merge opts opts')))
  Preparable
  (->jdbc-connection [this] con)
  (get-statement [this db-spec sql-params opts']
    (get-statement* db-spec con sql-params (merge opts opts')))
  Closeable
  (close [this])
  Transactable
  (transact [this f opts']
    (transact* db-spec con transacted f (merge opts opts'))))

(defrecord Connected [db-spec con transacted opts]
  Connectable
  (get-connection [this opts']
    (->NestedConnection db-spec con transacted (merge opts opts')))
  Preparable
  (->jdbc-connection [this] con)
  (get-statement [this db-spec sql-params opts']
    (get-statement* db-spec con sql-params (merge opts opts')))
  Closeable
  (close [this] (.close ^Connection con))
  Transactable
  (transact [this f opts']
    (transact* db-spec con transacted f (merge opts opts'))))

(defmacro in-transaction
  [[sym con opts] & body]
  `(transact ~con (fn [~sym] ~@body) ~opts))

(def ^:private classnames
  "Map of subprotocols to classnames. dbtype specifies one of these keys.

  The subprotocols map below provides aliases for dbtype.

  Most databases have just a single class name for their driver but we
  support a sequence of class names to try in order to allow for drivers
  that change their names over time (e.g., MySQL)."
  {"derby"          "org.apache.derby.jdbc.EmbeddedDriver"
   "h2"             "org.h2.Driver"
   "h2:mem"         "org.h2.Driver"
   "hsqldb"         "org.hsqldb.jdbcDriver"
   "jtds:sqlserver" "net.sourceforge.jtds.jdbc.Driver"
   "mysql"          ["com.mysql.cj.jdbc.Driver"
                     "com.mysql.jdbc.Driver"]
   "oracle:oci"     "oracle.jdbc.OracleDriver"
   "oracle:thin"    "oracle.jdbc.OracleDriver"
   "postgresql"     "org.postgresql.Driver"
   "pgsql"          "com.impossibl.postgres.jdbc.PGDriver"
   "redshift"       "com.amazon.redshift.jdbc.Driver"
   "sqlite"         "org.sqlite.JDBC"
   "sqlserver"      "com.microsoft.sqlserver.jdbc.SQLServerDriver"})

(def ^:private aliases
  "Map of schemes to subprotocols. Used to provide aliases for dbtype."
  {"hsql"       "hsqldb"
   "jtds"       "jtds:sqlserver"
   "mssql"      "sqlserver"
   "oracle"     "oracle:thin"
   "oracle:sid" "oracle:thin"
   "postgres"   "postgresql"})

(def ^:private host-prefixes
  "Map of subprotocols to non-standard host-prefixes.
  Anything not listed is assumed to use //."
  {"oracle:oci"  "@"
   "oracle:thin" "@"})

(def ^:private ports
  "Map of subprotocols to ports."
  {"jtds:sqlserver" 1433
   "mysql"          3306
   "oracle:oci"     1521
   "oracle:sid"     1521
   "oracle:thin"    1521
   "postgresql"     5432
   "sqlserver"      1433})

(def ^:private dbname-separators
  "Map of schemes to separators. The default is / but a couple are different."
  {"mssql"      ";DATABASENAME="
   "sqlserver"  ";DATABASENAME="
   "oracle:sid" ":"})

(defn- modify-connection
  "Given a database connection and a map of options, update the connection
  as specified by the options."
  ^java.sql.Connection
  [^java.sql.Connection connection opts]
  (when (and connection (contains? opts :auto-commit?))
    (.setAutoCommit connection (boolean (:auto-commit? opts))))
  (when (and connection (contains? opts :read-only?))
    (.setReadOnly connection (boolean (:read-only? opts))))
  connection)

(defn- ^Properties as-properties
  "Convert any seq of pairs to a java.utils.Properties instance.
   Uses as-sql-name to convert both keys and values into strings."
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (name k) (str v)))
    p))

(defn- get-driver-connection
  "Common logic for loading the DriverManager and the designed JDBC driver
  class and obtaining the appropriate Connection object."
  [classname subprotocol db-spec url etc opts error-msg]
  (if-let [class-name (or classname (classnames subprotocol))]
    (do
      ;; force DriverManager to be loaded
      (DriverManager/getLoginTimeout)
      (if (string? class-name)
        (clojure.lang.RT/loadClassForName class-name)
        (loop [[clazz & more] class-name]
          (when-let [load-failure
                     (try
                       (clojure.lang.RT/loadClassForName clazz)
                       nil
                       (catch Exception e
                         e))]
            (if (seq more)
              (recur more)
              (throw load-failure))))))
    (throw (ex-info error-msg db-spec)))
  (-> (DriverManager/getConnection url (as-properties etc))
      (modify-connection opts)))

(defn- spec->connection
  ""
  [{:keys [dbtype dbname host port classname] :as db-spec} opts]
  (let [;; allow aliases for dbtype
        subprotocol (aliases dbtype dbtype)
        host (or host "127.0.0.1")
        port (or port (ports subprotocol))
        db-sep (dbname-separators dbtype "/")
        url (cond (= "h2:mem" dbtype)
                  (str "jdbc:" subprotocol ":" dbname ";DB_CLOSE_DELAY=-1")
                  (#{"derby" "h2" "hsqldb" "sqlite"} subprotocol)
                  (str "jdbc:" subprotocol ":" dbname)
                  :else
                  (str "jdbc:" subprotocol ":"
                       (host-prefixes subprotocol "//")
                       host
                       (when port (str ":" port))
                       db-sep dbname))
        etc (dissoc db-spec :dbtype :dbname)]
    (get-driver-connection classname subprotocol db-spec
                           url etc opts
                           (str "Unknown dbtype: " dbtype))))

(defn- string->spec
  ""
  [s]
  {})

(extend-protocol Connectable
  clojure.lang.Associative
  (get-connection [this opts]
    (->Connected this (spec->connection this opts) nil opts))
  Connection
  (get-connection [this opts]
    (->Connected {} this nil opts))
  DataSource
  (get-connection [this opts]
    (->Connected {} (modify-connection (.getConnection this) opts) nil opts))
  String
  (get-connection [this opts]
    (get-connection (string->spec this) opts)))

(comment
  (get-connection {:dbtype "derby" :dbname "clojure_test" :create true} {})
  (-> "jdbc:some:stuff" (get-connection {}) (get-connection {})))

(defn- get-column-names
  ""
  [^ResultSet rs]
  (let [^ResultSetMetaData rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (mapv (fn [^Integer i]
            (keyword (.getTableName rsmeta i) (.getColumnLabel rsmeta i)))
          idxs)))

(defn- mapify-result-set
  "Given a result set, return an object that wraps the current row as a hash
  map. Note that a result set is mutable and the current row will change behind
  this wrapper so operations need to be eager (and fairly limited).
  Supports ILookup (keywords are treated as strings).
  Supports Associative for lookup only (again, keywords are treated as strings).
  Later we may realize a new hash map when assoc (and other, future, operations
  are performed on the result set row)."
  [^ResultSet rs]
  (let [cols (delay (get-column-names rs))]
    (reify
      clojure.lang.ILookup
      (valAt [this k]
             (try
               (.getObject rs (name k))
               (catch SQLException _)))
      (valAt [this k not-found]
             (try
               (.getObject rs (name k))
               (catch SQLException _
                 not-found)))
      clojure.lang.Associative
      (containsKey [this k]
                   (try
                     (.getObject rs (name k))
                     true
                     (catch SQLException _
                       false)))
      (entryAt [this k]
               (try
                 (clojure.lang.MapEntry. k (.getObject rs (name k)))
                 (catch SQLException _)))
      (assoc [this _ _]
             (throw (ex-info "assoc not supported on raw result set" {})))
      clojure.lang.Seqable
      (seq [this]
        (seq (mapv (fn [^Integer i]
                     (clojure.lang.MapEntry. (nth @cols i)
                                             (.getObject rs (inc i))))
                   (range (count @cols))))))))

(defn execute!
  "General SQL execution function. Returns a reducible that, when reduced,
  runs the SQL and yields the result."
  [db-spec sql-params opts]
  (reify clojure.lang.IReduceInit
    (reduce [this f init]
      (with-open [con (get-connection db-spec opts)]
        (with-open [stmt (get-statement con db-spec sql-params opts)]
          (if (.execute stmt)
            (let [rs     (.getResultSet stmt)
                  rs-map (mapify-result-set rs)]
              (loop [init' init]
                (if (.next rs)
                  (let [result (f init' rs-map)]
                    (if (reduced? result)
                      @result
                      (recur result)))
                  init')))
            (.getUpdateCount stmt)))))))

(comment
  (def db-spec {:dbtype "mysql" :dbname "worldsingles" :user "root" :password "visual"})
  (reduce (fn [rs m] (conj rs (into {} m)))
          []
          (execute! db-spec ["select * from status"] {})))
