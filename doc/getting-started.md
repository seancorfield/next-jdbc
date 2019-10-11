# Getting Started with next.jdbc

The `next.jdbc` library provides a simpler, faster alternative to the [`clojure.java.jdbc`](https://github.com/clojure/java.jdbc) Contrib library and is the next step in the evolution of that library.

It is designed to work with Clojure 1.10 or later, supports `datafy`/`nav`, and by default produces hash maps with automatically qualified keywords, indicating source tables and column names (labels), if your database supports that.

## Installation

You can add `next.jdbc` to your project with either:

```clojure
{seancorfield/next.jdbc {:mvn/version "1.0.8"}}
```
for `deps.edn` or:

```clojure
[seancorfield/next.jdbc "1.0.8"]
```
for `project.clj` or `build.boot`.

In addition, you will need to add dependencies for the JDBC drivers you wish to use for whatever databases you are using. You can see the drivers and versions that `next.jdbc` is tested against in [the project's `deps.edn` file](https://github.com/seancorfield/next-jdbc/blob/master/deps.edn#L11-L20), but many other JDBC drivers for other databases should also work (e.g., Oracle, Red Shift).

## An Example REPL Session

To start using `next.jdbc`, you need to create a datasource (an instance of `javax.sql.DataSource`). You can use `next.jdbc/get-datasource` with either a "db-spec" -- a hash map describing the database you wish to connect to -- or a JDBC URI string. Or you can construct a datasource from one of the connection pooling libraries out there, such as [HikariCP](https://brettwooldridge.github.io/HikariCP/) or [c3p0](https://www.mchange.com/projects/c3p0/) -- see [Connection Pooling](#connection-pooling) below.

For the examples in this documentation, we will use a local H2 database on disk, and we'll use the [Clojure CLI tools](https://clojure.org/guides/deps_and_cli) and `deps.edn`:

```clojure
;; deps.edn
{:deps {org.clojure/clojure {:mvn/version "1.10.1"}
        seancorfield/next.jdbc {:mvn/version "1.0.8"}
        com.h2database/h2 {:mvn/version "1.4.199"}}}
```

### Create & Populate a Database

In this REPL session, we'll define an H2 datasource, create a database with a simple table, and then add some data and query it:

```clojure
> clj
Clojure 1.10.1
user=> (require '[next.jdbc :as jdbc])
nil
user=> (def db {:dbtype "h2" :dbname "example"})
#'user/db
user=> (def ds (jdbc/get-datasource db))
#'user/ds
user=> (jdbc/execute! ds ["
create table address (
  id int auto_increment primary key,
  name varchar(32),
  email varchar(255)
)"])
[#:next.jdbc{:update-count 0}]
user=> (jdbc/execute! ds ["
insert into address(name,email)
  values('Sean Corfield','sean@corfield.org')"])
[#:next.jdbc{:update-count 1}]
user=> (jdbc/execute! ds ["select * from address"])
[#:ADDRESS{:ID 1, :NAME "Sean Corfield", :EMAIL "sean@corfield.org"}]
user=>
```

### The "db-spec" hash map

We described the database with just `:dbtype` and `:dbname` because it is created as a local file and needs no authentication. For most databases, you would need `:user` and `:password` for authentication, and if the database is running on a remote machine you would need `:host` and possibly `:port` (`next.jdbc` tries to guess the correct port based on the `:dbtype`).

> Note: You can see the full list of `:dbtype` values supported in [next.jdbc/get-datasource](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/api/next.jdbc#get-datasource)'s docstring. If you need this programmatically, you can get it from the [next.jdbc.connection/dbtypes](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/api/next.jdbc.connection#dbtypes) hash map. If those lists differ, the hash map is the definitive list (and I'll need to fix the docstring!). The docstring of that Var explains how to tell `next.jdbc` about additional databases.

If you already have a JDBC URL (string), you can use that as-is instead of the db-spec hash map. If you have a JDBC URL and still need additional options passed into the JDBC driver, you can use a hash map with the `:jdbcUrl` key specifying the string and whatever additional options you need.

### `execute!` & `execute-one!`

We used `execute!` to create the `address` table, to insert a new row into it, and to query it. In all three cases, `execute!` returns a vector of hash maps with namespace-qualified keys, representing the result set from the operation, if available. When no result set is produced, `next.jdbc` returns a "result set" containing the "update count" from the operation (which is usually the number of rows affected; note that `:builder-fn` does not affect this fake "result set"). By default, H2 uses uppercase names and `next.jdbc` returns these as-is.

If you only want a single row back -- the first row of any result set, generated keys, or update counts -- you can use `execute-one!` instead. Continuing the REPL session, we'll insert another address and ask for the generated keys to be returned, and then we'll query for a single row:

```clojure
user=> (jdbc/execute-one! ds ["
insert into address(name,email)
  values('Someone Else','some@elsewhere.com')
"] {:return-keys true})
#:ADDRESS{:ID 2}
user=> (jdbc/execute-one! ds ["select * from address where id = ?" 2])
#:ADDRESS{:ID 2, :NAME "Someone Else", :EMAIL "some@elsewhere.com"}
user=>
```

Since we used `execute-one!`, we get just one row back (a hash map). This also shows how you provide parameters to SQL statements -- with `?` in the SQL and then the corresponding parameter values in the vector after the SQL string.

> Note: In general, you should use `execute-one!` for DDL operations since you will only get back an update count. If you have a SQL statement that you know will only return an update count, `execute-one!` is the right choice. If you have a SQL statement that you know will only return a single row in the result set, you probably want to use `execute-one!`. If you use `execute-one!` for a SQL statement that would return multiple rows in a result set, even though you will only get the first row back (as a hash map), the full result set will still be retrieved from the database -- it does not limit the SQL in any way.

### `plan` & Reducing Result Sets

While those functions are fine for retrieving result sets as data, most of the time you want to process that data efficiently, so `next.jdbc` provides a SQL execution function that works with `reduce` and with transducers to consume the result set without the intermediate overhead of creating Clojure data structures for every row:

```clojure
user=> (into #{}
             (map :ADDRESS/NAME)
             (jdbc/plan ds ["select * from address"]))
#{"Sean Corfield" "Someone Else"}
user=>
```

This produces a set of all the unique names in the `address` table, directly from the `java.sql.ResultSet` object returned by the JDBC driver, without creating any Clojure hash maps. That means you can use either the qualified keyword that would be produced by `execute!` or `execute-one!` or you can use a simple keyword that mirrors the column name (label) directly:

```clojure
user=> (into #{}
             (map :name)
             (jdbc/plan ds ["select * from address"]))
#{"Sean Corfield" "Someone Else"}
user=>
```

Any operation that can perform key-based lookup can be used here without creating hash maps: `get`, `contains?`, `find` (returns a `MapEntry` of whatever key you requested and the corresponding column value), or direct keyword access as shown above. Any operation that would require a Clojure hash map, such as `assoc` or anything that invokes `seq` (`keys`, `vals`), will cause the full row to be expanded into a hash map, such as produced by `execute!` or `execute-one!`.

> Note: since `plan` expects you to process the result set via reduction, you should not use it for DDL or for SQL statements that only produce update counts.

## Datasources, Connections & Transactions

In the examples above, we created a datasource and then passed it into each function call. When `next.jdbc` is given a datasource, it creates a `java.sql.Connection` from it, uses it for the SQL operation, and then closes it. If you're not using a connection pooling datasource (see below), that can be quite an overhead: setting up database connections to remote servers is not cheap!

If you want to run multiple SQL operations without that overhead each time, you can create the connection yourself and reuse it across several operations using `with-open` and `next.jdbc/get-connection`:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (jdbc/execute! con ...)
  (jdbc/execute! con ...)
  (into [] (map :column) (jdbc/plan con ...)))
```

If any of these operations throws an exception, the connection will still be closed but operations prior to the exception will have already been committed to the database. If you want to reuse a connection across multiple operations but have them all rollback if an exception occurs, you can use `next.jdbc/with-transaction`:

```clojure
(jdbc/with-transaction [tx ds]
  (jdbc/execute! tx ...)
  (jdbc/execute! tx ...)
  (into [] (map :column) (jdbc/plan tx ...)))
```

If `with-transaction` is given a datasource, it will create and close the connection for you. If you pass in an existing connection, `with-transaction` will set up a transaction on that connection and, after either committing or rolling back the transaction, will restore the state of the connection and leave it open:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (jdbc/execute! con ...) ; committed
  (jdbc/with-transaction [tx con] ; will commit or rollback this group:
    (jdbc/execute! tx ...)
    (jdbc/execute! tx ...)
    (into [] (map :column) (jdbc/plan tx ...)))
  (jdbc/execute! con ...)) ; committed
```

You can read more about [working with transactions](/doc/transactions.md) further on in the documentation.

## Connection Pooling

`next.jdbc` makes it easy to use either HikariCP or c3p0 for connection pooling.

First, you need to add the connection pooling library as a dependency, e.g.,

```clojure
com.zaxxer/HikariCP {:mvn/version "3.3.1"}
;; or:
com.mchange/c3p0 {:mvn/version "0.9.5.4"}
```

_Check those libraries' documentation for the latest version to use!_

Then import the appropriate classes into your code:

```clojure
(ns my.main
  (:require [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)
           ;; or:
           (com.mchange.v2.c3p0 ComboPooledDataSource PooledDataSource)))
```

Finally, create the connection pooled datasource. `db-spec` here contains the regular `next.jdbc` options (`:dbtype`, `:dbname`, and maybe `:host`, `:port`, `:classname` etc -- or the `:jdbcUrl` format mentioned above). Those are used to construct the JDBC URL that is passed into the datasource object (by calling `.setJdbcUrl` on it). You can also specify any of the connection pooling library's options, as mixed case keywords corresponding to any simple setter methods on the class being passed in, e.g., `:connectionTestQuery`, `:maximumPoolSize` (HikariCP), `:maxPoolSize`, `:preferredTestQuery` (c3p0).

Some important notes regarding HikariCP:

* Authentication credentials must use `:username` (if you are using c3p0 or regular, non-pooled, connections, then the db-spec hash map must contain `:user`).
* When using `:dbtype "jtds"`, you must specify `:connectionTestQuery "SELECT 1"` (or some other query to verify the health of a connection) because the jTDS JDBC driver does not implement `.isValid()` so HikariCP requires a specific test query instead (c3p0 does not rely on this method so it works with jTDS without needing `:preferredTestQuery`).

You will generally want to create the connection pooled datasource at the start of your program (and close it before you exit, although that's not really important since it'll be cleaned up when the JVM shuts down):

```clojure
(defn -main [& args]
  (with-open [^HikariDataSource ds (connection/->pool HikariDataSource db-spec)]
    (jdbc/execute! ds ...)
    (jdbc/execute! ds ...)
    (do-other-stuff ds args)
    (into [] (map :column) (jdbc/plan ds ...))))
;; or:
(defn -main [& args]
  (with-open [^PooledDataSource ds (connection/->pool ComboPooledDataSource db-spec)]
    (jdbc/execute! ds ...)
    (jdbc/execute! ds ...)
    (do-other-stuff ds args)
    (into [] (map :column) (jdbc/plan ds ...))))
```

You only need the type hints on `ds` if you plan to call methods on it via Java interop, such as `.close` (or using `with-open` to auto-close it) and you want to avoid reflection.

If you are using [Component](https://github.com/stuartsierra/component), a connection pooled datasource is a good candidate since it has a `start`/`stop` lifecycle:

```clojure
(ns ...
  (:require [com.stuartsierra.component :as component]
            ...))

(defrecord Database [db-spec ^HikariDataSource datasource]
  component/Lifecycle
  (start [this]
    (if datasource
      this ; already started
      (assoc this :datasource (connection/->pool HikariDataSource db-spec))))
  (stop [this]
    (if datasource
      (do
        (.close datasource)
        (assoc this :datasource nil))
      this))) ; already stopped

(defn -main [& args]
  (let [db (component/start (map->Database {:db-spec db-spec}))]
    (try
      (jdbc/execute! (:datasource db) ...)
      (jdbc/execute! (:datasource db) ...)
      (do-other-stuff db args)
      (into [] (map :column) (jdbc/plan (:datasource db) ...)))
      (catch Throwable t)
        (component/stop db)))
```

## Working with Additional Data Types

By default, `next.jdbc` relies on the JDBC driver to handle all data type conversions when reading from a result set (to produce Clojure values from SQL values) or setting parameters (to produce SQL values from Clojure values). Sometimes that means that you will get back a database-specific Java object that would need to be manually converted to a Clojure data structure, or that certain database column types require you to manually construct the appropriate database-specific Java object to pass into a SQL operation. You can usually automate those conversions using either the [ReadableColumn protocol](/doc/result-set-builders.md#readablecolumn) (for converting database-specific types to Clojure values) or the [SettableParameter protocol](/doc/prepared-statements.md#prepared-statement-parameters) (for converting Clojure values to database-specific types).

## Support from Specs

As you are developing with `next.jdbc`, it can be useful to have assistance from `clojure.spec` in checking calls to `next.jdbc`'s functions, to provide explicit argument checking and/or better error messages for some common mistakes, e.g., trying to pass a plain SQL string where a vector (containing a SQL string, and no parameters) is expected.

You can enable argument checking for functions in `next.jdbc`, `next.jdbc.connection`, `next.jdbc.prepare`, and `next.jdbc.sql` by requiring the `next.jdbc.specs` namespace and instrumenting the functions. A convenience function is provided:

```clojure
(require '[next.jdbc.specs :as specs])
(specs/instrument) ; instruments all next.jdbc API functions

(jdbc/execute! ds "SELECT * FROM fruit")
Call to #'next.jdbc/execute! did not conform to spec.
```

In the `:problems` output, you'll see the `:path [:sql :sql-params]` and `:pred vector?` for the `:val "SELECT * FROM fruit"`. Without the specs' assistance, this mistake would produce a more cryptic error, a `ClassCastException`, that a `Character` cannot be cast to a `String`, from inside `next.jdbc.prepare`.

A convenience function also exists to revert that instrumentation:

```clojure
(specs/unstrument) ; undoes the instrumentation of all next.jdbc API functions
```

[Friendly SQL Functions :>](/doc/friendly-sql-functions.md)
