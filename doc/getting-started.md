# Getting Started with next.jdbc

The `next.jdbc` library provides a simpler, faster alternative to the [`clojure.java.jdbc`](https://github.com/clojure/java.jdbc) Contrib library and is the next step in the evolution of that library.

It is designed to work with Clojure 1.10 or later, supports `datafy`/`nav`, and by default produces hash maps with automatically qualified keywords, indicating source tables and column names (labels), if your database supports that.

## Installation

**You must be using Clojure 1.10 or later.** 1.10.3 is the most recent stable version of Clojure (as of March 4th, 2021).

You can add `next.jdbc` to your project with either:

```clojure
com.github.seancorfield/next.jdbc {:mvn/version "1.3.828"}
```
for `deps.edn` or:

```clojure
[com.github.seancorfield/next.jdbc "1.3.828"]
```
for `project.clj` or `build.boot`.

**In addition, you will need to add dependencies for the JDBC drivers you wish to use for whatever databases you are using. For example:**

* MySQL: `mysql/mysql-connector-java {:mvn/version "8.0.19"}` ([search for latest version](https://search.maven.org/artifact/mysql/mysql-connector-java))
* PostgreSQL: `org.postgresql/postgresql {:mvn/version "42.2.10"}` ([search for latest version](https://search.maven.org/artifact/org.postgresql/postgresql))
* Microsoft SQL Server: `com.microsoft.sqlserver/mssql-jdbc {:mvn/version "8.2.1.jre8"}` ([search for latest version](https://search.maven.org/artifact/com.microsoft.sqlserver/mssql-jdbc))

> Note: these are the versions that `next.jdbc` is tested against but there may be more recent versions and those should generally work too -- click the "search for latest version" link to see all available versions of those drivers on Maven Central. You can see the full list of drivers and versions that `next.jdbc` is tested against in [the project's `deps.edn` file](https://github.com/seancorfield/next-jdbc/blob/develop/deps.edn#L10-L27), but many other JDBC drivers for other databases should also work (e.g., Oracle, Red Shift).

## An Example REPL Session

To start using `next.jdbc`, you need to create a datasource (an instance of `javax.sql.DataSource`). You can use `next.jdbc/get-datasource` with either a "db-spec" -- a hash map describing the database you wish to connect to -- or a JDBC URL string. Or you can construct a datasource from one of the connection pooling libraries out there, such as [HikariCP](https://github.com/brettwooldridge/HikariCP) or [c3p0](https://www.mchange.com/projects/c3p0/) -- see [Connection Pooling](#connection-pooling) below.

For the examples in this documentation, we will use a local H2 database on disk, and we'll use the [Clojure CLI tools](https://clojure.org/guides/deps_and_cli) and `deps.edn`:

```clojure
;; deps.edn
{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
        com.github.seancorfield/next.jdbc {:mvn/version "1.3.828"}
        com.h2database/h2 {:mvn/version "1.4.199"}}}
```

### Create & Populate a Database

In this REPL session, we'll define an H2 datasource, create a database with a simple table, and then add some data and query it:

```clojure
> clj
Clojure 1.10.3
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

> Note: You can see the full list of `:dbtype` values supported in [next.jdbc/get-datasource](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc#get-datasource)'s docstring. If you need this programmatically, you can get it from the [next.jdbc.connection/dbtypes](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc.connection#dbtypes) hash map. If those lists differ, the hash map is the definitive list (and I'll need to fix the docstring!). The docstring of that Var explains how to tell `next.jdbc` about additional databases.

If you already have a JDBC URL (string), you can use that as-is instead of the db-spec hash map. If you have a JDBC URL and still need additional options passed into the JDBC driver, you can use a hash map with the `:jdbcUrl` key specifying the string and whatever additional options you need.

### `execute!` & `execute-one!`

We used `execute!` to create the `address` table, to insert a new row into it, and to query it. In all three cases, `execute!` returns a vector of hash maps with namespace-qualified keys, representing the result set from the operation, if available.
If the result set contains no rows, `execute!` returns an empty vector `[]`.
When no result set is available, `next.jdbc` returns a "result set" containing the "update count" from the operation (which is usually the number of rows affected; note that `:builder-fn` does not affect this fake "result set"). By default, H2 uses uppercase names and `next.jdbc` returns these as-is.

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
If the result set contains no rows, `execute-one!` returns `nil`.
When no result is available, and `next.jdbc` returns a fake "result set" containing the "update count", `execute-one!` returns just a single hash map with the key `next.jdbc/update-count` and the number of rows updated.

In the same way that you would use `execute-one!` if you only want one row or one update count, compared to `execute!` for multiple rows or a vector containing an update count, you can also ask `execute!` to return multiple result sets -- such as might be returned from a stored procedure call, or a T-SQL script (for SQL Server), or multiple statements (for MySQL) -- instead of just one. If you pass the `:multi-rs true` option to `execute!`, you will get back a vector of results sets, instead of just one result set: a vector of zero or more vectors. The result may well be a mix of vectors containing realized rows and vectors containing update counts, reflecting the results from specific SQL operations in the stored procedure or script.

> Note: In general, you should use `execute-one!` for DDL operations since you will only get back an update count. If you have a SQL statement that you know will only return an update count, `execute-one!` is the right choice. If you have a SQL statement that you know will only return a single row in the result set, you probably want to use `execute-one!`. If you use `execute-one!` for a SQL statement that would return multiple rows in a result set, even though you will only get the first row back (as a hash map), the full result set will still be retrieved from the database -- it does not limit the SQL in any way.

### Options & Result Set Builders

All functions in `next.jdbc` (except `get-datasource`) can accept, as the optional last argument, a hash map containing a [variety of options](/doc/all-the-options.md) that control the behavior of the `next.jdbc` functions.

We saw `:return-keys` provided as an option to the `execute-one!` function above and mentioned the `:builder-fn` option just above that. As noted, the default behavior is to return rows as hash maps with namespace-qualified keywords identifying the column names with the table name as the qualifier. There's a whole chapter on [result set builders](/doc/result-set-builders.md) but here's a quick example showing how to get unqualified, lower case keywords instead:

```clojure
user=> (require '[next.jdbc.result-set :as rs])
nil
user=> (jdbc/execute-one! ds ["
insert into address(name,email)
  values('Someone Else','some@elsewhere.com')
"] {:return-keys true :builder-fn rs/as-unqualified-lower-maps})
{:id 3}
user=> (jdbc/execute-one! ds ["select * from address where id = ?" 3]
                          {:builder-fn rs/as-unqualified-lower-maps})
{:id 3, :name "Someone Else", :email "some@elsewhere.com"}
user=>
```

Relying on the default result set builder -- and table-qualified column names -- is the recommended approach to take, if possible, with a few caveats:
* MS SQL Server produces unqualified column names by default (see [**Tips & Tricks**](/doc/tips-and-tricks.md) for how to get table names back from MS SQL Server),
* Oracle's JDBC driver doesn't support `.getTableName()` so it will only produce unqualified column names (also mentioned in **Tips & Tricks**),
* If your SQL query joins tables in a way that produces duplicate column names, and you use unqualified column names, then those duplicated column names will conflict and you will get only one of them in your result -- use aliases in SQL (`as`) to make the column names distinct,
* If your SQL query joins a table to itself under different aliases, the _qualified_ column names will conflict because they are based on the underlying table name provided by the JDBC driver rather the alias you used in your query -- again, use aliases in SQL to make those column names distinct.

If you want to pass the same set of options into several operations, you can use `next.jdbc/with-options` to wrap your datasource (or connection) in a way that will pass "default options". Here's the example above rewritten with that:

```clojure
user=> (require '[next.jdbc.result-set :as rs])
nil
user=> (def ds-opts (jdbc/with-options ds {:builder-fn rs/as-unqualified-lower-maps}))
#'user/ds-opts
user=> (jdbc/execute-one! ds-opts ["
insert into address(name,email)
  values('Someone Else','some@elsewhere.com')
"] {:return-keys true})
{:id 4}
user=> (jdbc/execute-one! ds-opts ["select * from address where id = ?" 4])
{:id 4, :name "Someone Else", :email "some@elsewhere.com"}
user=>
```

> Note: See the `next.jdbc/with-option` examples in the [**Datasources, Connections & Transactions**](#datasources-connections--transactions) below for some caveats around using this function.

In addition, two pre-built option hash maps are available in `next.jdbc`, that leverage the [camel-snake-kebab library](https://github.com/clj-commons/camel-snake-kebab/):
* `snake-kebab-opts` -- provides `:column-fn`, `:table-fn`, `:label-fn`, `:qualifier-fn`, and `:builder-fn` that will convert Clojure identifiers in `:kebab-case` to SQL entities in `snake_case` and will produce result sets with qualified `:kebab-case` names from SQL entities that use `snake_case`,
* `unqualified-snake-kebab-opts` -- provides `:column-fn`, `:table-fn`, `:label-fn`, `:qualifier-fn`, and `:builder-fn` that will convert Clojure identifiers in `:kebab-case` to SQL entities in `snake_case` and will produce result sets with _unqualified_ `:kebab-case` names from SQL entities that use `snake_case`.

You can `assoc` any additional options you need into these pre-built option hash maps
and pass the combined options into any of this library's functions.

> Note: Using `camel-snake-kebab` might also be helpful if your database has `camelCase` table and column names, although you'll have to provide `:column-fn` and `:table-fn` yourself as `->camelCase` from that library. Either way, consider relying on the _default_ result set builder first and avoid converting column and table names (see [Advantages of 'snake case': portability and ubiquity](https://vvvvalvalval.github.io/posts/clojure-key-namespacing-convention-considered-harmful.html#advantages_of_'snake_case':_portability_and_ubiquity) for an interesting discussion on kebab-case vs snake_case -- I do not agree with all of the author's points in that article, particularly his position against qualified keywords, but his argument for retaining snake_case around system boundaries is compelling).


### `plan` & Reducing Result Sets

While the `execute!` and `execute-one!` functions are fine for retrieving result sets as data, most of the time you want to process that data efficiently without necessarily converting the entire result set into a Clojure data structure, so `next.jdbc` provides a SQL execution function that works with `reduce` and with transducers to consume the result set without the intermediate overhead of creating Clojure data structures for every row.

We're going to create a new table that contains invoice items so we can see how to use `plan` without producing data structures:

```clojure
user=> (jdbc/execute-one! ds ["
create table invoice (
  id int auto_increment primary key,
  product varchar(32),
  unit_price decimal(10,2),
  unit_count int unsigned,
  customer_id int unsigned
)"])
#:next.jdbc{:update-count 0}
user=> (jdbc/execute-one! ds ["
insert into invoice (product, unit_price, unit_count, customer_id)
values ('apple', 0.99, 6, 100),
       ('banana', 1.25, 3, 100),
       ('cucumber', 2.49, 2, 100)
"])
#:next.jdbc{:update-count 3}
user=> (reduce
         (fn [cost row]
           (+ cost (* (:unit_price row)
                      (:unit_count row))))
         0
         (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
14.67M
```

The call to `jdbc/plan` returns an `IReduceInit` object but does not actually run the SQL. Only when the returned object is reduced is the connection obtained from the data source, the SQL executed, and the computation performed. The connection is closed automatically when the reduction is complete. The `row` in the reduction is an abstraction over the underlying (mutable) `ResultSet` object -- it is not a Clojure data structure. Because of that, you can simply access the columns via their SQL labels as shown -- you do not need to use the column-qualified name, and you do not need to worry about the database returning uppercase column names (SQL labels are not case sensitive).

> Note: if you want a column name transformation to be applied here, specify `:column-fn` as an option to the `plan` call.

Here's the same computation rewritten using `transduce`:

```clojure
user=> (transduce
         (map #(* (:unit_price %) (:unit_count %)))
         +
         0
         (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
14.67M
```

or composing the transforms:

```clojure
user=> (transduce
         (comp (map (juxt :unit_price :unit_count))
               (map #(apply * %)))
         +
         0
         (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
14.67M
```

If you just wanted the total item count:

```clojure
user=> (transduce
         (map :unit_count)
         +
         0
         (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
11
```

You can use other functions that perform reductions to process the result of `plan`, such as obtaining a set of unique products from an invoice:

```clojure
user=> (into #{}
             (map :product)
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
#{"apple" "banana" "cucumber"}
```

Any operation that can perform key-based lookup can be used here without creating hash maps from the rows: `get`, `contains?`, `find` (returns a `MapEntry` of whatever key you requested and the corresponding column value), or direct keyword access as shown above. Any operation that would require a Clojure hash map, such as `assoc` or anything that invokes `seq` (`keys`, `vals`), will cause the full row to be expanded into a hash map, such as produced by `execute!` or `execute-one!`, which implements `Datafiable` and `Navigable` and supports lazy navigation via foreign keys, explained in [`datafy`, `nav`, and the `:schema` option](/doc/datafy-nav-and-schema.md).

This means that `select-keys` can be used to create regular Clojure hash map from (a subset of) columns in the row, without realizing the row, and it will not implement `Datafiable` or `Navigable`.

If you wish to create a Clojure hash map that supports that lazy navigation, you can call `next.jdbc.result-set/datafiable-row`, passing in the current row, a `connectable`, and an options hash map, just as you passed into `plan`. Compare the difference in output between these four expressions (see below for a simpler way to do this):

```clojure
;; selects specific keys (as simple keywords):
user=> (into []
             (map #(select-keys % [:id :product :unit_price :unit_count :customer_id]))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; selects specific keys (as qualified keywords):
user=> (into []
             (map #(select-keys % [:invoice/id :invoice/product
                                   :invoice/unit_price :invoice/unit_count
                                   :invoice/customer_id]))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; selects specific keys (as qualified keywords -- ignoring the table name):
user=> (into []
             (map #(select-keys % [:foo/id :bar/product
                                   :quux/unit_price :wibble/unit_count
                                   :blah/customer_id]))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; do not do this:
user=> (into []
             (map #(into {} %))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; do this if you just want realized rows with default qualified names:
user=> (into []
             (map #(rs/datafiable-row % ds {}))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
```

The latter produces a vector of hash maps, just like the result of `execute!`, where each "row" follows the case conventions of the database, the keys are qualified by the table name, and the hash map is datafiable and navigable. The third expression produces a result that looks identical but has stripped all the metadata away: it has still called `rs/datafiable-row` to fully-realize a datafiable and navigable hash map but it has then "poured" that into a new, empty hash map, losing the metadata.

In addition to the hash map operations described above, the abstraction over the `ResultSet` can also respond to a couple of functions in `next.jdbc.result-set`:

* `next.jdbc.result-set/row-number` - returns the 1-based row number, by calling `.getRow()` on the `ResultSet`,
* `next.jdbc.result-set/column-names` - returns a vector of column names from the `ResultSet`, as created by the result set builder specified,
* `next.jdbc.result-set/metadata` - returns the `ResultSetMetaData` object, datafied (so the result will depend on whether you have required `next.jdbc.datafy`).

> Note: Apache Derby requires the following options to be provided in order to call `.getRow()` (and therefore `row-number`): `{:concurrency :read-only, :cursors :close, :result-type :scroll-insensitive}`

If you realize a row, by calling `datafiable-row` on the abstract row passed into the reducing function, you can still call `row-number` and `column-names` on that realized row. These functions are _not_ available on the realized rows returned from `execute!` or `execute-one!`, only within reductions over `plan`.

The order of the column names returned by `column-names` matches SQL's natural order, based on the operation performed, and will also match the order of column values provided in the reduction when using an array-based result set builder (`plan` provides just the column values, one row at a time, when using an array-based builder, without the leading vector of column names that you would get from `execute!`: if you call `datafiable-row` on such a row, you will get a realized vector of column values).

> Note: since `plan` expects you to process the result set via reduction, you should not use it for DDL or for SQL statements that only produce update counts.

As of 1.1.588, two helper functions are available to make some `plan` operations easier:

* `next.jdbc.plan/select-one!` -- reduces over `plan` and returns part of just the first row,
* `next.jdbc.plan/select!` -- reduces over `plan` and returns a sequence of parts of each row.

`select!` accepts a vector of column names to extract or a function to apply to each row. It is equivalent to the following:

```clojure
;; select! with vector of column names:
user=> (into [] (map #(select-keys % cols)) (jdbc/plan ...))
;; select! with a function:
user=> (into [] (map f) (jdbc/plan ...))
```

The `:into` option lets you override the default of `[]` as the first argument to `into`.

`select-one!` performs the same transformation on just the first row returned from a reduction over `plan`, equivalent to the following:

```clojure
;; select-one! with vector of column names:
user=> (reduce (fn [_ row] (reduced (select-keys row cols))) nil (jdbc/plan ...))
;; select-one! with a function:
user=> (reduce (fn [_ row] (reduced (f row))) nil (jdbc/plan ...))
```

For example:

```clojure
;; select columns:
user=> (plan/select-one!
        ds [:n] ["select count(*) as n from invoice where customer_id = ?" 100])
{:n 3}
;; apply a function:
user=> (plan/select-one!
        ds :n ["select count(*) as n from invoice where customer_id = ?" 100])
3
```

Here are some of the above sequence-producing operations, showing their `select!` equivalent:

```clojure
user=> (require '[next.jdbc.plan :as plan])
nil
user=> (into #{}
             (map :product)
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
#{"apple" "banana" "cucumber"}
;; or:
user=> (plan/select! ds
                     :product
                     ["select * from invoice where customer_id = ?" 100]
                     {:into #{}}) ; product a set, rather than a vector
#{"apple" "banana" "cucumber"}
;; selects specific keys (as simple keywords):
user=> (into []
             (map #(select-keys % [:id :product :unit_price :unit_count :customer_id]))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; or:
user=> (plan/select! ds
                     [:id :product :unit_price :unit_count :customer_id]
                     ["select * from invoice where customer_id = ?" 100])
;; selects specific keys (as qualified keywords):
user=> (into []
             (map #(select-keys % [:invoice/id :invoice/product
                                   :invoice/unit_price :invoice/unit_count
                                   :invoice/customer_id]))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; or:
user=> (plan/select! ds
                     [:invoice/id :invoice/product
                      :invoice/unit_price :invoice/unit_count
                      :invoice/customer_id]
                     ["select * from invoice where customer_id = ?" 100])
;; selects specific keys (as qualified keywords -- ignoring the table name):
user=> (into []
             (map #(select-keys % [:foo/id :bar/product
                                   :quux/unit_price :wibble/unit_count
                                   :blah/customer_id]))
             (jdbc/plan ds ["select * from invoice where customer_id = ?" 100]))
;; or:
user=> (plan/select! ds
                     [:foo/id :bar/product
                      :quux/unit_price :wibble/unit_count
                      :blah/customer_id]
                     ["select * from invoice where customer_id = ?" 100])
```

> Note: you need to be careful when using stateful transducers, such as `partition-by`, when reducing over the result of `plan`. Since `plan` returns an `IReduceInit`, the resource management (around the `ResultSet`) only applies to the `reduce` operation: many stateful transducers have a completing function that will access elements of the result sequence -- and this will usually fail after the reduction has cleaned up the resources. This is an inherent problem with stateful transducers over resource-managing reductions with no good solution.

## Datasources, Connections & Transactions

In the examples above, we created a datasource and then passed it into each function call. When `next.jdbc` is given a datasource, it creates a `java.sql.Connection` from it, uses it for the SQL operation (by creating and populating a `java.sql.PreparedStatement` from the connection and the SQL string and parameters passed in), and then closes it. If you're not using a connection pooling datasource (see below), that can be quite an overhead: setting up database connections to remote servers is not cheap!

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

`with-transaction` behaves somewhat like Clojure's `with-open` macro: it will (generally) create a new `Connection` for you (from `ds`) and set up a transaction on it and bind it to `tx`; if the code in the body executes successfully, it will commit the transaction and close the `Connection`; if the code in the body throws an exception, it will rollback the transaction, but still close the `Connection`.

If `ds` is a `Connection`, `with-transaction` will just bind `tx` to that but will set up a transaction on that `Connection`; run the code in the body and either commit or rollback the transaction; it will leave the `Connection` open (but try to restore the state of the `Connection`).

If `ds` is a datasource, `with-transaction` will call `get-connection` on it, bind `tx` to that `Connection` and set up a transaction; run the code in the body and either commit or rollback the transaction; close the `Connection`.

If `ds` is something else, `with-transaction` will call `get-datasource` on it first and then proceed as above.

Here's what will happen in the case where `with-transaction` is given a `Connection`:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (jdbc/execute! con ...) ; auto-committed

  (jdbc/with-transaction [tx con] ; will commit or rollback this group:
    ;; note: tx is bound to the same Connection object as con
    (jdbc/execute! tx ...)
    (jdbc/execute! tx ...)
    (into [] (map :column) (jdbc/plan tx ...)))

  (jdbc/execute! con ...)) ; auto-committed
```

You can read more about [working with transactions](/doc/transactions.md) further on in the documentation.

> Note: Because `get-datasource` and `get-connection` return plain JDBC objects (`javax.sql.DataSource` and `java.sql.Connection` respectively), `next.jdbc/with-options` and `next.jdbc/with-logging` (see **Logging** below) cannot flow options across those calls, so if you are explicitly managing connections or transactions as above, you would need to have local bindings for the wrapped versions:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (let [con-opts (jdbc/with-options con some-options)]
    (jdbc/execute! con-opts ...) ; auto-committed

    (jdbc/with-transaction [tx con-opts] ; will commit or rollback this group:
      (let [tx-opts (jdbc/with-options tx (:options con-opts)]
        (jdbc/execute! tx-opts ...)
        (jdbc/execute! tx-opts ...)
        (into [] (map :column) (jdbc/plan tx-opts ...))))

    (jdbc/execute! con-opts ...))) ; auto-committed
```

### Prepared Statement Caveat

Not all databases support using a `PreparedStatement` for every type of SQL operation. You might have to create a `java.sql.Statement` instead, directly from a `java.sql.Connection` and use that, without parameters, in `plan`, `execute!`, or `execute-one!`. See the following example:

```clojure
(require '[next.jdbc.prepare :as prep])

(with-open [con (jdbc/get-connection ds)]
  (jdbc/execute! (prep/statement con) ["...just a SQL string..."])
  (jdbc/execute! con ["...some SQL..." "and" "parameters"]) ; uses PreparedStatement
  (into [] (map :column) (jdbc/plan (prep/statement con) ["..."])))
```

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

In addition, for HikariCP, you can specify properties to be applied to the underlying `DataSource` itself by passing `:dataSourceProperties` with a hash map containing those properties, such as `:socketTimeout`:

```clojure
;; assumes next.jdbc.connection has been required as connection
(connection/->pool com.zaxxer.hikari.HikariDataSource
                   {:dbtype "postgres" :dbname "thedb" :username "dbuser" :password "secret"
                    :dataSourceProperties {:socketTimeout 30}})
```

_(under the hood, `java.data` converts that hash map to a `java.util.Properties` object with `String` keys and `String` values)_

> Note: both HikariCP and c3p0 defer validation of the settings until a connection is requested. If you want to ensure that your datasource is set up correctly, and the database is reachable, when you first create the connection pool, you will need to call `jdbc/get-connection` on it (and then close that connection and return it to the pool). This will also ensure that the pool is fully initialized. See the examples below.

Some important notes regarding HikariCP:

* Authentication credentials must use `:username` (if you are using c3p0 or regular, non-pooled, connections, then the db-spec hash map must contain `:user`).
* When using `:dbtype "jtds"`, you must specify `:connectionTestQuery "SELECT 1"` (or some other query to verify the health of a connection) because the jTDS JDBC driver does not implement `.isValid()` so HikariCP requires a specific test query instead (c3p0 does not rely on this method so it works with jTDS without needing `:preferredTestQuery`).
* When using PostgreSQL, and trying to set a default `:schema` via HikariCP, you will need to specify `:connectionInitSql "COMMIT;"` until [this HikariCP issue](https://github.com/brettwooldridge/HikariCP/issues/1369) is addressed.

You will generally want to create the connection pooled datasource at the start of your program (and close it before you exit, although that's not really important since it'll be cleaned up when the JVM shuts down):

```clojure
(defn -main [& args]
  ;; db-spec must include :username
  (with-open [^HikariDataSource ds (connection/->pool HikariDataSource db-spec)]
    ;; this code initializes the pool and performs a validation check:
    (.close (jdbc/get-connection ds))
    ;; otherwise that validation check is deferred until the first connection
    ;; is requested in a regular operation:
    (jdbc/execute! ds ...)
    (jdbc/execute! ds ...)
    (do-other-stuff ds args)
    (into [] (map :column) (jdbc/plan ds ...))))
;; or:
(defn -main [& args]
  (with-open [^PooledDataSource ds (connection/->pool ComboPooledDataSource db-spec)]
    ;; this code initializes the pool and performs a validation check:
    (.close (jdbc/get-connection ds))
    ;; otherwise that validation check is deferred until the first connection
    ;; is requested in a regular operation:
    (jdbc/execute! ds ...)
    (jdbc/execute! ds ...)
    (do-other-stuff ds args)
    (into [] (map :column) (jdbc/plan ds ...))))
```

You only need the type hints on `ds` if you plan to call methods on it via Java interop, such as `.close` (or using `with-open` to auto-close it) and you want to avoid reflection.

If you are using [Component](https://github.com/stuartsierra/component), a connection pooled datasource is a good candidate since it has a `start`/`stop` lifecycle. `next.jdbc` has support for Component built-in, via the `next.jdbc.connection/component` function which creates a Component-compatible entity which you can `start` and then invoke as a function with no arguments to obtain the `DataSource` within.

```clojure
(ns my.data.program
  (:require [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)))

;; HikariCP requires :username instead of :user in the db-spec:
(def ^:private db-spec {:dbtype "..." :dbname "..." :username "..." :password "..."})

(defn -main [& args]
  ;; connection/component takes the same arguments as connection/->pool:
  (let [ds (component/start (connection/component HikariDataSource db-spec))]
    (try
      ;; "invoke" the data source component to get the javax.sql.DataSource:
      (jdbc/execute! (ds) ...)
      (jdbc/execute! (ds) ...)
      ;; can pass the data source component around other code:
      (do-other-stuff ds args)
      (into [] (map :column) (jdbc/plan (ds) ...))
      (finally
        ;; stopping the component will close the connection pool:
        (component/stop ds)))))
```

## Working with Additional Data Types

By default, `next.jdbc` relies on the JDBC driver to handle all data type conversions when reading from a result set (to produce Clojure values from SQL values) or setting parameters (to produce SQL values from Clojure values). Sometimes that means that you will get back a database-specific Java object that would need to be manually converted to a Clojure data structure, or that certain database column types require you to manually construct the appropriate database-specific Java object to pass into a SQL operation. You can usually automate those conversions using either the [`ReadableColumn` protocol](/doc/result-set-builders.md#readablecolumn) (for converting database-specific types to Clojure values) or the [`SettableParameter` protocol](/doc/prepared-statements.md#prepared-statement-parameters) (for converting Clojure values to database-specific types).

In particular, PostgreSQL does not seem to perform a conversion from `java.util.Date` to a SQL data type automatically. You can `require` the [`next.jdbc.date-time` namespace](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/CURRENT/api/next.jdbc.date-time) to enable that conversion.

If you are working with Java Time, some JDBC drivers will automatically convert `java.time.Instant` (and `java.time.LocalDate` and `java.time.LocalDateTime`) to a SQL data type automatically, but others will not. Requiring `next.jdbc.date-time` will enable those automatic conversions for all databases.

> Note: `next.jdbc.date-time` also provides functions you can call to enable automatic conversion of SQL date/timestamp types to Clojure data types when reading result sets. If you need specific conversions beyond that to happen automatically, consider extending the `ReadableColumn` protocol, mentioned above.

The `next.jdbc.types` namespace provides over three dozen convenience functions for "type hinting" values so that the JDBC driver might automatically handle some conversions that the default parameter setting function does not. Each function is named for the corresponding SQL type, prefixed by `as-`: `as-bigint`, `as-other`, `as-real`, etc. An example of where this helps is when dealing with PostgreSQL enumerated types: the default behavior, when passed a string that should correspond to an enumerated type, is to throw an exception that `column "..." is of type ... but expression is of type character varying`. You can wrap such strings with `(as-other "...")` which tells PostgreSQL to treat this as `java.sql.Types/OTHER` when setting the parameter.

## Processing Database Metadata

JDBC provides several features that let you introspect the database to obtain lists of tables, views, and so on. `next.jdbc` does not provide any specific functions for this but you can easily get this metadata from a `java.sql.Connection` and turn it into Clojure data as follows:

```clojure
(with-open [con (p/get-connection ds opts)]
  (-> (.getMetaData con) ; produces java.sql.DatabaseMetaData
      ;; return a java.sql.ResultSet describing all tables and views:
      (.getTables nil nil nil (into-array ["TABLE" "VIEW"]))
      (rs/datafiable-result-set ds opts)))
```

Several methods on `DatabaseMetaData` return a `ResultSet` object, e.g., `.getCatalogs()`, `.getClientInfoProperties()`, `.getSchemas()`.
All of those can be handled in a similar manner to the above. See the [Oracle documentation for `java.sql.DatabaseMetaData`](https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/java/sql/DatabaseMetaData.html) (Java 11) for more details.

If you are working with a generalized datasource that may be a `Connection`, a `DataSource`,
or a wrapped connectable (via something like `with-options` or `with-transaction`), you can
write generic, `Connection`-based code using `on-connection` which will reuse a `Connection`
if one is passed or create a new one if needed (and automatically close it afterward):

```clojure
(on-connection [con ds]
  (-> (.getMetaData con) ; produces java.sql.DatabaseMetaData
      ;; return a java.sql.ResultSet describing all tables and views:
      (.getTables nil nil nil (into-array ["TABLE" "VIEW"]))
      (rs/datafiable-result-set ds opts)))
```

> Note: to avoid confusion and/or incorrect usage, you cannot pass options to `on-connection` because they would be ignored in some cases (existing `Connection` or a wrapped `Connection`).

## Logging

Sometimes it is convenient to have database operations logged automatically. `next.jdbc/with-logging`
provides a way to wrap a datasource (or connection) so that operations on it will be logged via
functions you provide.

There are two logging points:
* Logging the SQL and parameters prior to a database operation,
* Logging the result of a database operation.

`next.jdbc/with-logging` accepts two or three arguments and returns a connectable that can
be used with `plan`, `execute!`, `execute-one!`, `prepare`, or any of the "friendly SQL
functions". Since it uses a similar wrapping mechanism to `next.jdbc/with-options`, the
same caveats apply -- see [**Datasources, Connections & Transactions**](#datasources-connections--transactions) above for details.

### Logging SQL and Parameters

```clojure
(let [log-ds (jdbc/with-logging ds my-sql-logger)]
  (jdbc/execute! log-ds ["some SQL" "and" "params"])
  ...
  (jdbc/execute! log-ds ["more SQL" "other" "params"]))
```

The `my-sql-logger` function will be invoked for each database operation, with two arguments:
* The fully-qualified symbol identifying the operation,
* The vector containing the SQL string followed by the parameters.

The symbol will be one of: `next.jdbc/plan`, `next.jdbc/execute!`, `next.jdbc/execute-one!`,
or `next.jdbc/prepare`. The friendly SQL functions invoke `execute!` or `execute-one!` under
the hood, so that is how they will be logged.

The logging function can do anything it wants with the SQL and parameters. If you are logging
parameter values, consider sensitive data that you might be passing in.

### Logging Results

```clojure
(let [log-ds (jdbc/with-logging ds my-sql-logger my-result-logger)]
  (jdbc/execute! log-ds ["some SQL" "and" "params"])
  ...
  (jdbc/execute! log-ds ["more SQL" "other" "params"]))
```

In addition to calling `my-sql-logger` as described above, this will also call `my-result-logger`
for `execute!` and `execute-one!` operations (`plan` and `prepare` do not execute database
operations directly so they do not produce results). `my-result-logger` will be invoked with
three arguments:
* The fully-qualified symbol identify the operation,
* A "state" argument (the result of calling `my-sql-logger`),
* The result set data structure, if the call succeeded, or the exception if it failed.

The return value of the result logger function is ignored.

The symbol will be one of: `next.jdbc/execute!` or `next.jdbc/execute-one!`. The friendly
SQL functions invoke `execute!` or `execute-one!` under the hood, so that is how they will
be logged.

The "state" argument allows you to return data from the first logging function, such as the
current time, that can be consumed by the second logging function, so that you can calculate
how long an `execute!` or `execute-one!` operation took. If the first logging function
returns `nil`, that will be passed as the second argument to your second logging function.

The result set data structure could be arbitrarily large. It will generally be a vector
for calls to `execute!` or a hash map for calls to `execute-one!`, but its shape is determined
by any `:builder-fn` options in effect. You should check if `(instance? Throwable result)`
to see if the call failed and the logger has been called with the thrown exception.

For `plan` and `prepare` calls, only the first logging function is invoked (and the return
value is ignored). You can use the symbol passed in to determine this.

### Naive Logging with Timing

This example prints all SQL and parameters to `*out*` along with millisecond timing and
results, if a result set is available:

```clojure
dev=> (def lds (jdbc/with-logging ds
 #_=>            (fn [sym sql-params]
 #_=>              (prn sym sql-params)
 #_=>              (System/currentTimeMillis))
 #_=>            (fn [sym state result]
 #_=>              (prn sym
 #_=>                   (- (System/currentTimeMillis) state)
 #_=>                   (if (map? result) result (count result))))))
#'dev/lds
dev=> (sql/find-by-keys lds :foo {:name "Person"})
next.jdbc/execute! ["SELECT * FROM foo WHERE name = ?" "Person"]
next.jdbc/execute! 813 1
[#:FOO{:NAME "Person"}]
dev=>
```

A more sophisticated example could use `sym` to decide whether to just log the SQL and
some parameter values or return the current time and the SQL and parameters, so that the
result logging could log the SQL, parameters, _and_ result set information with timing.

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
