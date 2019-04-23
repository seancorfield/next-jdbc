# Getting Started with next.jdbc

The `next.jdbc` library provides a simpler, faster alternative to the [`clojure.java.jdbc`](https://github.com/clojure/java.jdbc) Contrib library and is the next step in the evolution of that library.

It is designed to work with Clojure 1.10 or later, supports `datafy`/`nav`, and by default produces hash maps with automatically qualified keywords, indicating source tables and column names.

## Installation

You can add `next.jdbc` to your project with either:

```clojure
{seancorfield/next.jdbc {:mvn/version "1.0.0-alpha9"}}
```
for `deps.edn` or:

```clojure
[seancorfield/next.jdbc "1.0.0-alpha9"]
```
for `project.clj` or `build.boot`.

In addition, you will need to add dependencies for the JDBC drivers you wish to use for whatever databases you are using. You can see the drivers and versions that `next.jdbc` is tested against in [the project's `deps.edn` file](https://github.com/seancorfield/next-jdbc/blob/master/deps.edn#L6-L16), but many other JDBC drivers for other databases should also work (e.g., Oracle, Red Shift).

## An Example REPL Session

To start using `next.jdbc`, you need to create a datasource (an instance of `javax.sql.DataSource`). You can use `next.jdbc/get-datasource` with either a "db-spec" -- a hash map describing the database you wish to connect to -- or a JDBC URI string. Or you can construct a datasource from one of the connection pooling libraries out there, such as [HikariCP](https://brettwooldridge.github.io/HikariCP/) or [c3p0](https://www.mchange.com/projects/c3p0/).

For the examples in this documentation, we will use a local H2 database on disk, and we'll use the [Clojure CLI tools](https://clojure.org/guides/deps_and_cli) and `deps.edn`:

```clojure
;; deps.edn
{:deps {org.clojure/clojure {:mvn/version "1.10.0"}
        seancorfield/next.jdbc {:mvn/version "1.0.0-alpha9"}
        com.h2database/h2 {:mvn/version "1.4.197"}}}
```

In this REPL session, we'll define an H2 datasource, create a database with a simple table, and then add some data and query it:

```clojure
> clj
Clojure 1.10.0
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
We described the database with just `:dbtype` and `:dbname` because it is created as a local file and needs no authentication. For most databases, you would need `:user` and `:password` for authentication, and if the database is running on a remote machine you would need `:host` and possibly `:port` (`next.jdbc` tries to guess the correct port based on the `:dbtype`).

> Note: You can see the full list of `:dbtype` values supported in [next.jdbc/get-datasource](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/api/next.jdbc#get-datasource).

We used `execute!` to create the `address` table, to insert a new row into it, and to query it. In all three cases, `execute!` returns a vector of hash maps with namespace-qualified keys, representing the result set from the operation, if available. When no result set is produced, `next.jdbc` returns a "result set" containing the "update count" from the operation (which is usually the number of rows affected). By default, H2 uses uppercase names and `next.jdbc` returns these as-is.

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

While these functions are fine for retrieving result sets as data, most of the time you want to process that data efficiently, so `next.jdbc` provides a SQL execution function that works with `reduce` and with transducers to consume the result set without the intermediate overhead of creating Clojure data structures for every row:

```clojure
user=> (into #{}
             (map :ADDRESS/NAME)
             (jdbc/reducible! ds ["select * from address"]))
#{"Sean Corfield" "Someone Else"}
user=>
```

This produces a set of all the unique names in the `address` table, directly from the `java.sql.ResultSet` object returned by the JDBC driver, without creating any Clojure hash maps. That means you can use either the qualified keyword that would be produced by `execute!` or `execute-one!` or you can use a simple keyword that mirrors the column name directly:

```clojure
user=> (into #{}
             (map :name)
             (jdbc/reducible! ds ["select * from address"]))
#{"Sean Corfield" "Someone Else"}
user=>
```

Any operation that can perform key-based lookup can be used here without creating hash maps: `get`, `contains?`, `find` (returns a `MapEntry` of whatever key you requested and the corresponding column value), or direct keyword access as shown above. Any operation that would require a Clojure hash map, such as `assoc` or anything that invokes `seq` (`keys`, `vals`), will cause the full row to be expanded into a hash map, such as produced by `execute!` or `execute-one!`.

## Datasources, Connections & Transactions

In the examples above, we created a datasource and then passed it into each function call. When `next.jdbc` is given a datasource, it creates a `java.sql.Connection` from it, uses it for the SQL operation, and then closes it. If you're not using a connection pooling datasource, that can be quite an overhead: setting up database connections to remote servers is not cheap!

If you want to run multiple SQL operations without that overhead each time, you can create the connection yourself and reuse it across several operations using `with-open` and `next.jdbc/get-connection`:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (jdbc/execute! con ...)
  (jdbc/execute! con ...)
  (into [] (map :column) (jdbc/reducible! con ...)))
```

If any of these operations throws an exception, the connection will still be closed but operations prior to the exception will have already been committed to the database. If you want to reuse a connection across multiple operations but have them all rollback if an exception occurs, you can use `next.jdbc/with-transaction`:

```clojure
(jdbc/with-transaction [tx ds]
  (jdbc/execute! tx ...)
  (jdbc/execute! tx ...)
  (into [] (map :column) (jdbc/reducible! tx ...)))
```

If `with-transaction` is given a datasource, it will create and close the connection for you. If you pass in an existing connection, `with-transaction` will set up a transaction on that connection and, after either committing or rolling back the transaction, will restore the state of the connection and leave it open:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (jdbc/execute! con ...) ; committed
  (jdbc/with-transaction [tx con] ; will commit or rollback this group:
    (jdbc/execute! tx ...)
    (jdbc/execute! tx ...)
    (into [] (map :column) (jdbc/reducible! tx ...)))
  (jdbc/execute! con ...)) ; committed
```

[Friendly SQL Functions :>](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started/friendly-sql-functions)
