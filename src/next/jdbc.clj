;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  "The public API of the next generation java.jdbc library.

  The basic building blocks are the `java.sql`/`javax.sql` classes:
  * `DataSource` -- something to get connections from,
  * `Connection` -- an active connection to the database,
  * `PreparedStatement` -- SQL and parameters combined, from a connection,

  and the following functions and a macro:
  * `plan` -- given a connectable and SQL + parameters or a statement,
      return a reducible that, when reduced will execute the SQL and consume
      the `ResultSet` produced,
  * `execute!` -- given a connectable and SQL + parameters or a statement,
      execute the SQL, consume the `ResultSet` produced, and return a vector
      of hash maps representing the rows (@1); this can be datafied to allow
      navigation of foreign keys into other tables (either by convention or
      via a schema definition),
  * `execute-one!` -- given a connectable and SQL + parameters or a statement,
      execute the SQL, consume the first row of the `ResultSet` produced, and
      return a hash map representing that row; this can be datafied to allow
      navigation of foreign keys into other tables (either by convention or
      via a schema definition),
  * `prepare` -- given a `Connection` and SQL + parameters, construct a new
      `PreparedStatement`; in general this should be used with `with-open`,
  * `transact` -- the functional implementation of `with-transaction`,
  * `with-transaction` -- execute a series of SQL operations within a transaction.

  @1 result sets are built, by default, as vectors of hash maps, containing
      qualified keywords as column names, but the row builder and result set
      builder machinery is open and alternatives are provided to produce
      unqualified keywords as column names, and to produce a vector the
      column names followed by vectors of column values for each row, and
      lower-case variants of each.

  The following options are supported wherever a `PreparedStatement` is created:
  * `:concurrency` -- `:read-only`, `:updatable`,
  * `:cursors` -- `:close`, `:hold`
  * `:fetch-size` -- the fetch size value,
  * `:max-rows` -- the maximum number of rows to return,
  * `:result-type` -- `:forward-only`, `:scroll-insensitive`, `:scroll-sensitive`,
  * `:return-keys` -- either `true` or a vector of key names to return,
  * `:timeout` -- the query timeout."
  (:require [next.jdbc.connection]
            [next.jdbc.prepare]
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set]
            [next.jdbc.transaction]))

(set! *warn-on-reflection* true)

(defn get-datasource
  "Given some sort of specification of a database, return a `DataSource`.

  A specification can be a JDBC URL string (which is passed to the JDBC
  driver as-is), or a hash map. For the hash map, these keys are required:
  * `:dbtype` -- a string indicating the type of the database
  * `:dbname` -- a string indicating the name of the database to be used

  The following optional keys are commonly used:
  * `:user` -- the username to authenticate with
  * `:password` -- the password to authenticate with
  * `:host` -- the hostname or IP address of the database (default: `127.0.0.1`);
      can be `:none` which means the host/port segment of the JDBC URL should
      be omitted entirely (for 'local' databases)
  * `:port` -- the port for the database connection (the default is database-
      specific -- see below)
  * `:classname` -- if you need to override the default for the `:dbtype`
      (or you want to use a database that next.jdbc does not know about!)

  The following optional keys can be used to control how JDBC URLs are
  assembled. This may be needed for `:dbtype` values that `next.jdbc`
  does not recognize:
  * `:dbname-separator` -- override the `/` or `:` that normally precedes
      the database name in the JDBC URL
  * `:host-prefix` -- override the `//` that normally precedes the IP
      address or hostname in the JDBC URL

  Any additional options provided will be passed to the JDBC driver's
  `.getConnection` call as a `java.util.Properties` structure.

  Database types supported, and their defaults:
  * `derby` -- `org.apache.derby.jdbc.EmbeddedDriver` -- also pass `:create true`
      if you want the database to be automatically created
  * `h2` -- `org.h2.Driver` -- for an on-disk database
  * `h2:mem` -- `org.h2.Driver` -- for an in-memory database
  * `hsqldb`, `hsql` -- `org.hsqldb.jdbcDriver`
  * `jtds:sqlserver`, `jtds` -- `net.sourceforge.jtds.jdbc.Driver` -- `1433`
  * `mysql` -- `com.mysql.cj.jdbc.Driver`, `com.mysql.jdbc.Driver` -- `3306`
  * `oracle:oci` -- `oracle.jdbc.OracleDriver` -- `1521`
  * `oracle:thin`, `oracle` -- `oracle.jdbc.OracleDriver` -- `1521`
  * `oracle:sid` -- `oracle.jdbc.OracleDriver` -- `1521` -- uses the legacy `:`
      separator for the database name but otherwise behaves like `oracle:thin`
  * `postgresql`, `postgres` -- `org.postgresql.Driver` -- `5432`
  * `pgsql` -- `com.impossibl.postgres.jdbc.PGDriver` -- no default port
  * `redshift` -- `com.amazon.redshift.jdbc.Driver` -- no default port
  * `sqlite` -- `org.sqlite.JDBC`
  * `sqlserver`, `mssql` -- `com.microsoft.sqlserver.jdbc.SQLServerDriver` -- `1433`
  * `timesten:client` -- `com.timesten.jdbc.TimesTenClientDriver`
  * `timesten:direct` -- `com.timesten.jdbc.TimesTenDriver`"
  ^javax.sql.DataSource
  [spec]
  (p/get-datasource spec))

(defn get-connection
  "Given some sort of specification of a database, return a new `Connection`.

  In general, this should be used via `with-open`:

```clojure
  (with-open [con (get-connection spec opts)]
    (run-some-ops con))
```

  If you call `get-connection` on a `DataSource`, it just calls `.getConnection`
  and applies the `:auto-commit` and/or `:read-only` options, if provided.

  If you call `get-connection` on anything else, it will call `get-datasource`
  first to try to get a `DataSource`, and then call `get-connection` on that."
  (^java.sql.Connection
    [spec]
    (p/get-connection spec {}))
  (^java.sql.Connection
    [spec opts]
    (p/get-connection spec opts)))

(defn prepare
  "Given a connection to a database, and a vector containing SQL and any
  parameters it needs, return a new `PreparedStatement`.

  In general, this should be used via `with-open`:

```clojure
  (with-open [stmt (prepare spec sql-params opts)]
    (run-some-ops stmt))
```

  See the list of options above (in the namespace docstring) for what can
  be passed to prepare."
  (^java.sql.PreparedStatement
    [connection sql-params]
    (p/prepare connection sql-params {}))
  (^java.sql.PreparedStatement
    [connection sql-params opts]
    (p/prepare connection sql-params opts)))

(defn plan
  "General SQL execution function.

  Returns a reducible that, when reduced, runs the SQL and yields the result.
  See the Getting Started guide for examples and details.

  Can be called on a `PreparedStatement`, a `Connection`, or something that can
  produce a `Connection` via a `DataSource`."
  (^clojure.lang.IReduceInit
    [stmt]
    (p/-execute stmt [] {}))
  (^clojure.lang.IReduceInit
    [connectable sql-params]
    (p/-execute connectable sql-params
                {:next.jdbc/sql-params sql-params}))
  (^clojure.lang.IReduceInit
    [connectable sql-params opts]
    (p/-execute connectable sql-params
                (assoc opts :next.jdbc/sql-params sql-params))))

(defn execute!
  "General SQL execution function.

  Returns a fully-realized result set.
  See the Getting Started guide for examples and details.

  Can be called on a `PreparedStatement`, a `Connection`, or something that can
  produce a `Connection` via a `DataSource`."
  ([stmt]
   (p/-execute-all stmt [] {}))
  ([connectable sql-params]
   (p/-execute-all connectable sql-params
                   {:next.jdbc/sql-params sql-params}))
  ([connectable sql-params opts]
   (p/-execute-all connectable sql-params
                   (assoc opts :next.jdbc/sql-params sql-params))))

(defn execute-one!
  "General SQL execution function that returns just the first row of a result.
  See the Getting Started guide for examples and details.

  Can be called on a `PreparedStatement`, a `Connection`, or something that can
  produce a `Connection` via a `DataSource`."
  ([stmt]
   (p/-execute-one stmt [] {}))
  ([connectable sql-params]
   (p/-execute-one connectable sql-params
                   {:next.jdbc/sql-params sql-params}))
  ([connectable sql-params opts]
   (p/-execute-one connectable sql-params
                   (assoc opts :next.jdbc/sql-params sql-params))))

(defn transact
  "Given a transactable object and a function (taking a `Connection`),
  execute the function over the connection in a transactional manner.

  See `with-transaction` for supported options."
  ([transactable f]
   (p/-transact transactable f {}))
  ([transactable f opts]
   (p/-transact transactable f opts)))

(defmacro with-transaction
  "Given a transactable object, gets a connection and binds it to `sym`,
  then executes the `body` in that context, committing any changes if the body
  completes successfully, otherwise rolling back any changes made.

  The options map supports:
  * `:isolation` -- `:none`, `:read-committed`, `:read-uncommitted`,
      `:repeatable-read`, `:serializable`,
  * `:read-only` -- `true` / `false`,
  * `:rollback-only` -- `true` / `false`."
  [[sym transactable opts] & body]
  (let [con (vary-meta sym assoc :tag 'java.sql.Connection)]
   `(transact ~transactable (^{:once true} fn* [~con] ~@body) (or ~opts {}))))
