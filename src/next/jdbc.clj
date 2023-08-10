;; copyright (c) 2018-2023 Sean Corfield, all rights reserved

(ns next.jdbc
  "The public API of the next generation java.jdbc library.

  The basic building blocks are the `java.sql`/`javax.sql` classes:
  * `DataSource` -- something to get connections from,
  * `Connection` -- an active connection to the database,
  * `PreparedStatement` -- SQL and parameters combined, from a connection,

  and the following functions and a macro:
  * `get-datasource` -- given a hash map describing a database or a JDBC
      connection string, construct a `javax.sql.DataSource` and return it,
  * `get-connection` -- given a connectable, obtain a new `java.sql.Connection`
      from it and return that,
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
  * `execute-batch!` -- given a `PreparedStatement` and groups of parameters,
      execute the statement in batch mode (via `.executeBatch`); given a
      connectable, a SQL string, and groups of parameters, create a new
      `PreparedStatement` from the SQL and execute it in batch mode.
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

  The following options are supported wherever a `Connection` is created:
  * `:auto-commit` -- either `true` or `false`,
  * `:read-only` -- either `true` or `false`,
  * `:connection` -- a hash map of camelCase properties to set, via reflection,
      on the `Connection` object after it is created.

  The following options are supported wherever a `Statement` or
  `PreparedStatement` is created:
  * `:concurrency` -- `:read-only`, `:updatable`,
  * `:cursors` -- `:close`, `:hold`
  * `:fetch-size` -- the fetch size value,
  * `:max-rows` -- the maximum number of rows to return,
  * `:result-type` -- `:forward-only`, `:scroll-insensitive`, `:scroll-sensitive`,
  * `:timeout` -- the query timeout,
  * `:statement` -- a hash map of camelCase properties to set, via reflection,
      on the `Statement` or `PreparedStatement` object after it is created.

  In addition, wherever a `PreparedStatement` is created, you may specify:
  * `:return-keys` -- either `true` or a vector of key names to return."
  (:require [camel-snake-kebab.core :refer [->kebab-case ->snake_case]]
            [next.jdbc.connection]
            [next.jdbc.default-options :as opts]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql-logging :as logger]
            [next.jdbc.transaction :as tx])
  (:import (java.sql PreparedStatement)))

(set! *warn-on-reflection* true)

(defn get-datasource
  "Given some sort of specification of a database, return a `DataSource`.

  A specification can be a JDBC URL string (which is passed to the JDBC
  driver as-is), or a hash map.

  For the hash map, there are two formats accepted:

  In the first format, these keys are required:
  * `:dbtype` -- a string indicating the type of the database
  * `:dbname` -- a string indicating the name of the database to be used

  The following optional keys are commonly used:
  * `:user` -- the username to authenticate with
  * `:password` -- the password to authenticate with
  * `:host` -- the hostname or IP address of the database (default: `127.0.0.1`);
      can be `:none` which means the host/port segment of the JDBC URL should
      be omitted entirely (for 'local' databases)
  * `:port` -- the port for the database connection (the default is database-
      specific -- see below); can be `:none` which means the port segment of
      the JDBC URL should be omitted entirely
  * `:classname` -- if you need to override the default for the `:dbtype`
      (or you want to use a database that next.jdbc does not know about!)

  The following optional keys can be used to control how JDBC URLs are
  assembled. This may be needed for `:dbtype` values that `next.jdbc`
  does not recognize:
  * `:dbname-separator` -- override the `/` or `:` that normally precedes
      the database name in the JDBC URL
  * `:host-prefix` -- override the `//` that normally precedes the IP
      address or hostname in the JDBC URL
  * `:property-separator` -- an optional string that can be used to override
      the separators used in `jdbc-url` for the properties (after the initial
      JDBC URL portion); by default `?` and `&` are used to build JDBC URLs
      with properties; for SQL Server drivers (both MS and jTDS)
      `:property-separator \";\"` is used

  In the second format, this key is required:
  * `:jdbcUrl` -- a JDBC URL string

  Any additional options provided will be passed to the JDBC driver's
  `.getConnection` call as a `java.util.Properties` structure.

  Database types supported (for `:dbtype`), and their defaults:
  * `derby` -- `org.apache.derby.jdbc.EmbeddedDriver` -- also pass `:create true`
      if you want the database to be automatically created
  * `duckdb` -- `org.duckdb.DuckDBDriver` -- embedded database
  * `h2` -- `org.h2.Driver` -- for an on-disk database
  * `h2:mem` -- `org.h2.Driver` -- for an in-memory database
  * `hsqldb`, `hsql` -- `org.hsqldb.jdbcDriver`
  * `jtds:sqlserver`, `jtds` -- `net.sourceforge.jtds.jdbc.Driver` -- `1433`
  * `mariadb` -- `org.mariadb.jdbc.Driver` -- `3306`
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
  * `timesten:direct` -- `com.timesten.jdbc.TimesTenDriver`

  For more details about `:dbtype` and `:classname` values, see:
  https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc.connection#dbtypes"
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
  first to try to get a `DataSource`, and then call `get-connection` on that.

  If you want different per-connection username/password values, you can
  either put `:user` and `:password` into the `opts` hash map or pass them
  as positional arguments."
  (^java.sql.Connection
    [spec]
    (p/get-connection spec {}))
  (^java.sql.Connection
    [spec opts]
    (p/get-connection spec opts))
  (^java.sql.Connection
    [spec user password]
    (p/get-connection spec {:user user :password password}))
  (^java.sql.Connection
    [spec user password opts]
    (p/get-connection spec (assoc opts :user user :password password))))

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
  "General SQL execution function (for working with result sets).

  Returns a reducible that, when reduced, runs the SQL and yields the result.
  The reducible is also foldable (in the `clojure.core.reducers` sense) but
  see the **Tips & Tricks** section of the documentation for some important
  caveats about that.

  Can be called on a `PreparedStatement`, a `Connection`, or something that can
  produce a `Connection` via a `DataSource`.

  Your reducing function can read columns by name (string or simple keyword)
  from each row of the underlying `ResultSet` without realizing the row as
  a Clojure hash map. `select-keys` can also be used without realizing the row.
  Operations that imply an actual Clojure data structure (such as `assoc`,
  `dissoc`, `seq`, `keys`, `vals`, etc) will realize the row into a hash map
  using the supplied `:builder-fn` (or `as-maps` by default).

  If your reducing function needs to produce a hash map without calling a
  function that implicitly realizes the row, you can call:

  `(next.jdbc.result-set/datafiable-row row connectable opts)`

  passing in the current row (passed to the reducing function), a `connectable`,
  and an `opts` hash map. These can be the same values that you passed to `plan`
  (or they can be different, depending on how you want the row to be built,
  and how you want any subsequent lazy navigation to be handled)."
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

  Returns a fully-realized result set. When `:multi-rs true` is provided, will
  return multiple result sets, as a vector of result sets. Each result set is
  a vector of hash maps, by default, but can be controlled by the `:builder-fn`
  option.

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
  For any DDL or SQL statement that will return just an update count, this is
  the preferred function to use.

  Can be called on a `PreparedStatement`, a `Connection`, or something that can
  produce a `Connection` via a `DataSource`.

  Note: although this only returns the first row of a result set, it does not
  place any limit on the result of the SQL executed."
  ([stmt]
   (p/-execute-one stmt [] {}))
  ([connectable sql-params]
   (p/-execute-one connectable sql-params
                   {:next.jdbc/sql-params sql-params}))
  ([connectable sql-params opts]
   (p/-execute-one connectable sql-params
                   (assoc opts :next.jdbc/sql-params sql-params))))

(defn execute-batch!
  "Given a `PreparedStatement` and a vector containing parameter groups,
  i.e., a vector of vector of parameters, use `.addBatch` to add each group
  of parameters to the prepared statement (via `set-parameters`) and then
  call `.executeBatch`. A vector of update counts is returned.

  An options hash map may also be provided, containing `:batch-size` which
  determines how to partition the parameter groups for submission to the
  database. If omitted, all groups will be submitted as a single command.
  If you expect the update counts to be larger than `Integer/MAX_VALUE`,
  you can specify `:large true` and `.executeLargeBatch` will be called
  instead.

  Alternatively, given a connectable, a SQL string, a vector containing
  parameter groups, and an options hash map, create a new `PreparedStatement`
  (after possibly creating a new `Connection`), and execute the SQL with
  the specified parameter groups. That new `PreparedStatement` (and the
  new `Connection`, if created) will be closed automatically after use.

  By default, returns a Clojure vector of update counts. Some databases
  allow batch statements to also return generated keys and you can attempt that
  if you ensure the `PreparedStatement` is created with `:return-keys true`
  and you also provide `:return-generated-keys true` in the options passed
  to `execute-batch!`. Some databases will only return one generated key
  per batch, some return all the generated keys, some will throw an exception.
  If that is supported, `execute-batch!` will return a vector of hash maps
  containing the generated keys as fully-realized, datafiable result sets,
  whose content is database-dependent.

  May throw `java.sql.BatchUpdateException` if any part of the batch fails.
  You may be able to call `.getUpdateCounts` on that exception object to
  get more information about which parts succeeded and which failed.

  For additional caveats and database-specific options you may need, see:
  https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/doc/getting-started/prepared-statements#caveats

  Not all databases support batch execution."
  ([ps param-groups]
   (execute-batch! ps param-groups {}))
  ([^PreparedStatement ps param-groups opts]
   (let [params (if-let [n (:batch-size opts)]
                  (if (and (number? n) (pos? n))
                    (partition-all n param-groups)
                    (throw (IllegalArgumentException.
                            ":batch-size must be positive")))
                  [param-groups])]
     (into []
           (mapcat (fn [group]
                     (run! #(.addBatch (prepare/set-parameters ps %)) group)
                     (let [result (if (:large opts)
                                    (.executeLargeBatch ps)
                                    (.executeBatch ps))]
                       (if (:return-generated-keys opts)
                         (rs/datafiable-result-set (.getGeneratedKeys ps)
                                                   (p/get-connection ps {})
                                                   opts)
                         result))))
           params)))
  ([connectable sql param-groups opts]
   (if (or (instance? java.sql.Connection connectable)
           (and (satisfies? p/Connectable connectable)
                (instance? java.sql.Connection (:connectable connectable))))
     (with-open [ps (prepare connectable [sql] opts)]
       (execute-batch! ps param-groups opts))
     (with-open [con (get-connection connectable)]
       (execute-batch! con sql param-groups opts)))))

(defmacro on-connection
  "Given a connectable object, gets a connection and binds it to `sym`,
  then executes the `body` in that context.

  This allows you to write generic, `Connection`-based code without
  needing to know the exact type of an incoming datasource:

```clojure
  (on-connection [conn datasource]
    (let [metadata (.getMetadata conn)
          catalog  (.getCatalog conn)]
      ...))
```

  If passed a `Connection` or a `Connectable` that wraps a `Connection`,
  then that `Connection` is used as-is.

  Otherwise, creates a new `Connection` object from the connectable,
  executes the body, and automatically closes it for you."
  [[sym connectable] & body]
  (let [con-sym (vary-meta sym assoc :tag 'java.sql.Connection)]
    `(let [con-obj# ~connectable]
       (cond (instance? java.sql.Connection con-obj#)
             ((^{:once true} fn* [~con-sym] ~@body) con-obj#)
             (and (satisfies? p/Connectable con-obj#)
                  (instance? java.sql.Connection (:connectable con-obj#)))
             ((^{:once true} fn* [~con-sym] ~@body) (:connectable con-obj#))
             :else
             (with-open [con# (get-connection con-obj#)]
               ((^{:once true} fn* [~con-sym] ~@body) con#))))))

(defmacro on-connection+options
  "Given a connectable object, assumed to be wrapped with options, gets
  a connection, rewraps it with those options, and binds it to `sym`,
  then executes the `body` in that context.

  This allows you to write generic, **wrapped** connectable code without
  needing to know the exact type of an incoming datasource:

```clojure
  (on-connection+options [conn datasource]
    (execute! conn some-insert-sql)
    (execute! conn some-update-sql))
```

  If passed a `Connection` then that `Connection` is used as-is.

  If passed a `Connectable` that wraps a `Connection`, then that
  `Connectable` is used as-is.

  Otherwise, creates a new `Connection` object from the connectable,
  wraps that with options, executes the body, and automatically closes
  the new `Connection` for you."
  [[sym connectable] & body]
  `(let [con-obj# ~connectable]
     (cond (instance? java.sql.Connection con-obj#)
           ((^{:once true} fn* [~sym] ~@body) con-obj#)
           (and (satisfies? p/Connectable con-obj#)
                (instance? java.sql.Connection (:connectable con-obj#)))
           ((^{:once true} fn* [~sym] ~@body) con-obj#)
           :else
           (with-open [con# (get-connection con-obj#)]
             ((^{:once true} fn* [~sym] ~@body)
              (with-options con# (:options con-obj# {})))))))

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

  Like `with-open`, if `with-transaction` creates a new `Connection` object,
  it will automatically close it for you.

  If you are working with default options via `with-options`, you might want
  to use `with-transaction+options` instead.

  The options map supports:
  * `:isolation` -- `:none`, `:read-committed`, `:read-uncommitted`,
      `:repeatable-read`, `:serializable`,
  * `:read-only` -- `true` / `false` (`true` will make the `Connection` readonly),
  * `:rollback-only` -- `true` / `false` (`true` will make the transaction
      rollback, even if it would otherwise succeed)."
  [[sym transactable opts] & body]
  (let [con (vary-meta sym assoc :tag 'java.sql.Connection)]
   `(transact ~transactable (^{:once true} fn* [~con] ~@body) ~(or opts {}))))

(defn active-tx?
  "Returns true if `next.jdbc` has a currently active transaction in the
  current thread, else false.

  Note: transactions are a convention of operations on a `Connection` so
  this predicate only reflects `next.jdbc/transact` and `next.jdbc/with-transaction`
  operations -- it does not reflect any other operations on a `Connection`,
  performed via JDBC interop directly."
  []
  @#'tx/*active-tx*)

(defn with-options
  "Given a connectable/transactable object and a set of (default) options
  that should be used on all operations on that object, return a new
  wrapper object that can be used in its place.

  Bear in mind that `get-datasource`, `get-connection`, and `with-transaction`
  return plain Java objects, so if you call any of those on this wrapped
  object, you'll need to re-wrap the Java object `with-options` again. See
  the Datasources, Connections & Transactions section of Getting Started for
  more details, and some examples of use with these functions.

  `with-transaction+options` exists to automatically rewrap a `Connection`
  with the options from a `with-options` wrapper."
  [connectable opts]
  (let [c (:connectable connectable)
        o (:options connectable)]
    (if (and c o)
      (opts/->DefaultOptions c (merge o opts))
      (opts/->DefaultOptions connectable opts))))

(defmacro with-transaction+options
  "Given a transactable object, assumed to be wrapped with options, gets a
  connection, rewraps it with those options, and binds it to `sym`, then
  executes the `body` in that context, committing any changes if the body
  completes successfully, otherwise rolling back any changes made.

  Like `with-open`, if `with-transaction+options` creates a new `Connection`
  object, it will automatically close it for you.

  Note: the bound `sym` will be a **wrapped** connectable and not a plain
  Java object, so you cannot call JDBC methods directly on it like you can
  with `with-transaction`.

  The options map supports:
  * `:isolation` -- `:none`, `:read-committed`, `:read-uncommitted`,
      `:repeatable-read`, `:serializable`,
  * `:read-only` -- `true` / `false` (`true` will make the `Connection` readonly),
  * `:rollback-only` -- `true` / `false` (`true` will make the transaction
      rollback, even if it would otherwise succeed)."
  [[sym transactable opts] & body]
  `(let [tx# ~transactable]
     (transact tx#
               (^{:once true} fn*
                [con#] ; this is the unwrapped java.sql.connection
                (let [~sym (with-options con# (:options tx# {}))]
                  ~@body))
               ~(or opts {}))))

(defn with-logging
  "Given a connectable/transactable object and a sql/params logging
  function and an optional result logging function that should be used
  on all operations on that object, return a new wrapper object that can
  be used in its place.

  The sql/params logging function will be called with two arguments:
  * a symbol indicating which operation is being performed:
    * `next.jdbc/plan`, `next.jdbc/execute-one!`, `next.jdbc/execute!`,
      or `next.jdbc/prepare`
  * the vector containing the SQL string and its parameters

  Whatever the sql/params logging function returns will be passed as a
  `state` argument to the optional result logging function. This means you can
  use this mechanism to provide some timing information, since your sql/params
  logging function can return the current system time, and your result logging
  function can then calculate the elapsed time. There is an example of this in
  the Naive Logging with Timing section of Getting Started.

  The result logging function, if provided, will be called with the
  same symbol passed to the sql/params logging function, the `state`
  returned by the sql/params logging function, and either the result of
  the `execute!` or `execute-one!` call or an exception if the call
  failed. The result logging function is not called for the `plan`
  or `prepare` call (since they do not produce result sets directly).

  Bear in mind that `get-datasource`, `get-connection`, and `with-transaction`
  return plain Java objects, so if you call any of those on this wrapped
  object, you'll need to re-wrap the Java object `with-logging` again. See
  the Datasources, Connections & Transactions section of Getting Started for
  more details, and some examples of use with these functions."
  [connectable sql-logger & [result-logger]]
  (logger/->SQLLogging connectable sql-logger result-logger (:options connectable)))

(def snake-kebab-opts
  "A hash map of options that will convert Clojure identifiers to
  snake_case SQL entities (`:table-fn`, `:column-fn`), and will convert
  SQL entities to qualified kebab-case Clojure identifiers (`:builder-fn`)."
  {:column-fn  ->snake_case :table-fn     ->snake_case
   :label-fn   ->kebab-case :qualifier-fn ->kebab-case
   :builder-fn rs/as-kebab-maps})

(def unqualified-snake-kebab-opts
  "A hash map of options that will convert Clojure identifiers to
  snake_case SQL entities (`:table-fn`, `:column-fn`), and will convert
  SQL entities to unqualified kebab-case Clojure identifiers (`:builder-fn`)."
  {:column-fn  ->snake_case :table-fn     ->snake_case
   :label-fn   ->kebab-case :qualifier-fn ->kebab-case
   :builder-fn rs/as-unqualified-kebab-maps})
