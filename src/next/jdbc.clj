;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  "The public API of the next generation java.jdbc library.

  The basic building blocks are the java.sql/javax.sql classes:
  * DataSource -- something to get connections from,
  * Connection -- an active connection to the database,
  * PreparedStatement -- SQL and parameters combined, from a connection,
  and the following two functions and a macro:
  * reducible! -- given a connectable and SQL + parameters or a statement,
      return a reducible that, when reduced will execute the SQL and consume
      the ResultSet produced,
  * execute! -- given a connectable and SQL + parameters or a statement,
      execute the SQL, consume the ResultSet produced, and return a vector
      of hash maps representing the rows; this can be datafied to allow
      navigation of foreign keys into other tables (either by convention or
      via a schema definition).
  * with-transaction -- execute a series of SQL operations within a transaction.

  The following options are supported where a PreparedStatement is created:
  * :concurrency -- :read-only, :updatable,
  * :cursors -- :close, :hold
  * :fetch-size -- the fetch size value,
  * :max-rows -- the maximum number of rows to return,
  * :result-type -- :forward-only, :scroll-insensitive, :scroll-sensitive,
  * :return-keys -- either true or a vector of key names to return,
  * :timeout -- the query timeout."
  (:require [next.jdbc.connection] ; used to extend protocols
            [next.jdbc.prepare] ; used to extend protocols
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set] ; used to extend protocols
            [next.jdbc.transaction])) ; used to extend protocols

(set! *warn-on-reflection* true)

(defn get-datasource
  "Given some sort of specification of a database, return a DataSource.

  A specification can be a JDBC URL string (which is passed to the JDBC
  driver as-is), or a hash map. For the hash map, these keys are required:
  * :dbtype -- a string indicating the type of the database
  * :dbname -- a string indicating the name of the database to be used

  The following optional keys are commonly used:
  * :user -- the username to authenticate with
  * :password -- the password to authenticate with
  * :host -- the hostname or IP address of the database (default: 127.0.0.1)
  * :port -- the port for the database connection (the default is database-
      specific -- see below)
  * :classname -- if you need to override the default for the :dbtype
      (or you want to use a database that next.jdbc does not know about!)

  Any additional options provided will be passed to the JDBC driver's
  .getConnection call as a java.util.Properties structure.

  Database types supported, and their defaults:
  * derby -- org.apache.derby.jdbc.EmbeddedDriver -- also pass :create true
      if you want the database to be automatically created
  * h2 -- org.h2.Driver -- for an on-disk database
  * h2:mem -- org.h2.Driver -- for an in-memory database
  * hsqldb, hsql -- org.hsqldb.jdbcDriver
  * jtds:sqlserver, jtds -- net.sourceforge.jtds.jdbc.Driver -- 1433
  * mysql -- com.mysql.cj.jdbc.Driver, com.mysql.jdbc.Driver -- 3306
  * oracle:oci -- oracle.jdbc.OracleDriver -- 1521
  * oracle:thin, oracle -- oracle.jdbc.OracleDriver -- 1521
  * oracle:sid -- oracle.jdbc.OracleDriver -- 1521 -- uses the legacy :
      separator for the database name but otherwise behaves like oracle:thin
  * postgresql, postgres -- org.postgresql.Driver -- 5432
  * pgsql -- com.impossibl.postgres.jdbc.PGDriver -- no default port
  * redshift -- com.amazon.redshift.jdbc.Driver -- no default port
  * sqlite -- org.sqlite.JDBC
  * sqlserver, mssql -- com.microsoft.sqlserver.jdbc.SQLServerDriver -- 1433"
  [spec]
  (p/get-datasource spec))

(defn get-connection
  "Given some sort of specification of a database, return a new Connection.

  In general, this should be used via with-open:

  (with-open [con (get-connection spec opts)]
    (run-some-ops con))

  If you call get-connection on a DataSource, it just calls .getConnection
  and applies the :auto-commit and/or :read-only options, if provided.

  If you call get-connection on anything else, it will call get-datasource
  first to try to get a DataSource, and then call get-connection on that."
  ([spec]
   (p/get-connection spec {}))
  ([spec opts]
   (p/get-connection spec opts)))

(defn prepare
  "Given a connection to a database, and a vector containing SQL and any
  parameters it needs, return a new PreparedStatement.

  In general, this should be used via with-open:

  (with-open [stmt (prepare spec sql-params opts)]
    (run-some-ops stmt))

  See the list of options above (in the namespace docstring) for what can
  be passed to prepare."
  ([connection sql-params]
   (p/prepare connection sql-params {}))
  ([connection sql-params opts]
   (p/prepare connection sql-params opts)))

(defn reducible!
  "General SQL execution function.

  Returns a reducible that, when reduced, runs the SQL and yields the result.

  Can be called on a PreparedStatement, a Connection, or something that can
  produce a Connection via a DataSource."
  ([stmt]
   (p/-execute stmt [] {}))
  ([connectable sql-params]
   (p/-execute connectable sql-params {}))
  ([connectable sql-params opts]
   (p/-execute connectable sql-params opts)))

(defn execute!
  "General SQL execution function.

  Invokes 'reducible!' and then reduces that into a vector of hash maps.

  Can be called on a PreparedStatement, a Connection, or something that can
  produce a Connection via a DataSource.

  If it is called on a PreparedStatement, it cannot produce a datafiable
  result (because that requires a connectable instead)."
  ([stmt]
   (p/-execute-all stmt [] {}))
  ([connectable sql-params]
   (p/-execute-all connectable sql-params {}))
  ([connectable sql-params opts]
   (p/-execute-all connectable sql-params opts)))

(defn execute-one!
  "General SQL execution function that returns just the first row of a result.

  Can be called on a PreparedStatement, a Connection, or something that can
  produce a Connection via a DataSource."
  ([stmt]
   (p/-execute-one stmt [] {}))
  ([connectable sql-params]
   (p/-execute-one connectable sql-params {}))
  ([connectable sql-params opts]
   (p/-execute-one connectable sql-params opts)))

(defn transact
  "Given a connectable object and a function (taking a Connection),
  execute the function on a new connection in a transactional manner.

  An options map may be provided before the function."
  ([connectable f]
   (p/-transact connectable f {}))
  ([connectable opts f]
   (p/-transact connectable f opts)))

(defmacro with-transaction
  "Given a connectable object, gets a new connection and binds it to 'sym',
  then executes the 'body' in that context, committing any changes if the body
  completes successfully, otherwise rolling back any changes made.

  The options map supports:
  * isolation -- :none, :read-committed, :read-uncommitted, :repeatable-read,
      :serializable,
  * :read-only -- true / false,
  * :rollback-only -- true / false."
  [[sym connectable opts] & body]
  `(transact ~connectable ~opts (fn [~sym] ~@body)))
