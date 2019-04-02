;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.connection
  "Standard implementations of get-datasource and get-connection."
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection DriverManager)
           (javax.sql DataSource)
           (java.util Properties)))

(set! *warn-on-reflection* true)

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

(defn- ^Properties as-properties
  "Convert any seq of pairs to a java.util.Properties instance.
   Uses as-sql-name to convert both keys and values into strings."
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (name k) (str v)))
    p))

(defn- get-driver-connection
  "Common logic for loading the DriverManager and the designed JDBC driver
  class and obtaining the appropriate Connection object."
  [url etc]
  ;; force DriverManager to be loaded
  (DriverManager/getLoginTimeout)
  (DriverManager/getConnection url (as-properties etc)))

(defn- spec->url+etc
  "Given a database spec, return a JDBC URL and a map of any additional options."
  [{:keys [dbtype dbname host port classname] :as db-spec}]
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
    ;; verify the datasource is loadable
    (if-let [class-name (or classname (classnames subprotocol))]
      (do
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
      (throw (ex-info (str "Unknown dbtype: " dbtype) db-spec)))
    [url etc]))

(defn- string->url+etc
  "Given a JDBC URL, return it with an empty set of options with no parsing."
  [s]
  [s {}])

(defn- url+etc->datasource
  "Given a JDBC URL and a map of options, return a DataSource that can be
  used to obtain a new database connection."
  [[url etc]]
  (reify DataSource
    (getConnection [_]
                   (get-driver-connection url etc))
    (getConnection [_ username password]
                   (get-driver-connection url
                                          (assoc etc
                                                 :username username
                                                 :password password)))))

(defn- make-connection
  "Given a DataSource and a map of options, get a connection and update it
  as specified by the options.

  The options supported are:
  * :auto-commit -- whether the connection should be set to auto-commit or not;
      without this option, the defaut is true -- connections will auto-commit,
  * :read-only -- whether the connection should be set to read-only mode."
  ^Connection
  [^DataSource datasource opts]
  (let [^Connection connection (.getConnection datasource)]
    (when (contains? opts :auto-commit)
      (.setAutoCommit connection (boolean (:auto-commit opts))))
    (when (contains? opts :read-only)
      (.setReadOnly connection (boolean (:read-only opts))))
    connection))

(extend-protocol p/Sourceable
  clojure.lang.Associative
  (get-datasource [this]
                  (url+etc->datasource (spec->url+etc this)))
  javax.sql.DataSource
  (get-datasource [this] this)
  String
  (get-datasource [this]
                  (url+etc->datasource (string->url+etc this))))

(extend-protocol p/Connectable
  javax.sql.DataSource
  (get-connection [this opts] (make-connection this opts))
  Object
  (get-connection [this opts] (p/get-connection (p/get-datasource this) opts)))
