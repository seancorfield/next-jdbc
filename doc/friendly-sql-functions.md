# Friendly SQL Functions

In [Getting Started](/doc/getting-started.md), we used `execute!` and `execute-one!` for all our SQL operations, except when we were reducing a result set. These functions (and `plan`) all expect a "connectable" and a vector containing a SQL string followed by any parameter values required.

A "connectable" can be a `javax.sql.DataSource`, a `java.sql.Connection`, or something that can produce a datasource (when `get-datasource` is called on it). It can also be a `java.sql.PreparedStatement` but we'll cover that a bit later...

Because string-building isn't always much fun, `next.jdbc.sql` also provides some "friendly" functions for basic CRUD operations:

* `insert!` and `insert-multi!` -- for inserting one or more rows into a table -- "Create",
* `query` -- an alias for `execute!` when using a vector of SQL and parameters -- "Read",
* `update!` -- for updating one or more rows in a table -- "Update",
* `delete!` -- for deleting one or more rows in a table -- "Delete".

as well as these more specific "read" operations:

* `find-by-keys` -- a query on one or more column values, specified as a hash map or `WHERE` clause,
* `get-by-id` -- a query to return a single row, based on a single column value, usually the primary key.

These functions are described in more detail below. They are deliberately simple and intended to cover only the most common, basic SQL operations. The primary API (`plan`, `execute!`, `execute-one!`) is the recommended approach for everything beyond that. If you need more expressiveness, consider one of the following libraries to build SQL/parameter vectors, or run queries:

* [HoneySQL](https://github.com/jkk/honeysql) -- a composable DSL for creating SQL/parameter vectors from Clojure data structures
* [seql](https://github.com/exoscale/seql) -- a simplified EQL-inspired query language, built on `next.jdbc` (as of release 0.1.6)
* [SQLingvo](https://github.com/r0man/sqlingvo) -- a composable DSL for creating SQL/parameter vectors
* [Walkable](https://github.com/walkable-server/walkable) -- full EQL query language support for creating SQL/parameter vectors

If you prefer to write your SQL separately from your code, take a look at [HugSQL](https://github.com/layerware/hugsql) -- [HugSQL documentation](https://www.hugsql.org/) -- which has a `next.jdbc` adapter, as of version 0.5.1. See below for a "[quick start](#hugsql-quick-start)" for using HugSQL with `next.jdbc`.

## `insert!`

Given a table name (as a keyword) and a hash map of column names and values, this performs a single row insertion into the database:

```clojure
(sql/insert! ds :address {:name "A. Person" :email "albert@person.org"})
;; equivalent to
(jdbc/execute-one! ds ["INSERT INTO address (name,email) VALUES (?,?)"
                       "A.Person" "albert@person.org"] {:return-keys true})
```

## `insert-multi!`

Given a table name (as a keyword), a vector of column names, and a vector of row value vectors, this performs a multi-row insertion into the database:

```clojure
(sql/insert-multi! ds :address
  [:name :email]
  [["Stella" "stella@artois.beer"]
   ["Waldo" "waldo@lagunitas.beer"]
   ["Aunt Sally" "sour@lagunitas.beer"]])
;; equivalent to
(jdbc/execute! ds ["INSERT INTO address (name,email) VALUES (?,?), (?,?), (?,?)"
                   "Stella" "stella@artois.beer"
                   "Waldo" "waldo@lagunitas.beer"
                   "Aunt Sally" "sour@lagunitas.beer"] {:return-keys true})
```

> Note: this expands to a single SQL statement with placeholders for every
value being inserted -- for large sets of rows, this may exceed the limits
on SQL string size and/or number of parameters for your JDBC driver or your
database. Several databases have a limit of 1,000 parameter placeholders.
Oracle does not support this form of multi-row insert, requiring a different
syntax altogether.

You should look at [`next.jdbc.prepare/execute-batch!`](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/api/next.jdbc.prepare#execute-batch!) for an alternative approach.

## `query`

Given a vector of SQL and parameters, execute it:

```clojure
(sql/query ds ["select * from address where name = ?" "Stella"])
;; equivalent to
(jdbc/execute! ds ["SELECT * FROM address WHERE name = ?" "Stella"])
```

Note that the single argument form of `execute!`, taking just a `PreparedStatement`, is not supported by `query`.

## `update!`

Given a table name (as a keyword), a hash map of columns names and values to set, and either a hash map of column names and values to match on or a vector containing a partial `WHERE` clause and parameters, perform an update operation on the database:

```clojure
(sql/update! ds :address {:name "Somebody New"} {:id 2})
;; equivalent to
(sql/update! ds :address {:name "Somebody New"} ["id = ?" 2])
;; equivalent to
(jdbc/execute-one! ds ["UPDATE address SET name = ? WHERE id = ?"
                       "Somebody New" 2])
```

## `delete!`

Given a table name (as a keyword) and either a hash map of column names and values to match on or a vector containing a partial `WHERE` clause and parameters, perform a delete operation on the database:

```clojure
(sql/delete! ds :address {:id 8})
;; equivalent to
(sql/delete! ds :address ["id = ?" 8])
;; equivalent to
(jdbc/execute-one! ds ["DELETE FROM address WHERE id = ?" 8])
```

## `find-by-keys`

Given a table name (as a keyword) and either a hash map of column names and values to match on or a vector containing a partial `WHERE` clause and parameters, execute a query on the database:

```clojure
(sql/find-by-keys ds :address {:name "Stella" :email "stella@artois.beer"})
;; equivalent to
(sql/find-by-keys ds :address ["name = ? AND email = ?"
                               "Stella" "stella@artois.beer"])
;; equivalent to
(jdbc/execute! ds ["SELECT * FROM address WHERE name = ? AND email = ?"
                   "Stella" "stella@artois.beer"])
```

The default behavior is to return all the columns in each row. You can specify a subset of columns to return using the `:columns` option. It takes a vector and each element of the vector can be:

* a simple keyword representing the column name (`:column-fn` will be applied, if provided),
* a pair of keywords representing the column name and an alias (`:column-fn` will be applied to both, if provided),
* a pair consisting of a string and a keyword, representing a SQL expression and an alias (`:column-fn` will be applied to the alias, if provided).

```clojure
(sql/find-by-keys ds :address {:name "Stella"} {:columns [[:email :address]]})
;; equivalent to
(jdbc/execute! ds ["SELECT email AS address FROM address WHERE name = ?"
                   "Stella"])

(sql/find-by-keys ds :address {:name "Stella"} {:columns [["count(*)" :n]]})
;; equivalent to
(jdbc/execute! ds ["SELECT count(*) AS n FROM address WHERE name = ?"
                   "Stella"])
```

> Note: the SQL string provided for a column is copied exactly as-is into the generated SQL -- you are responsible for ensuring it is legal SQL!

`find-by-keys` supports an `:order-by` option which can specify a vector of column names to sort the results by. Elements may be column names or pairs of a column name and the direction to sort: `:asc` or `:desc`:

```clojure
(sql/find-by-keys ds :address
                  {:name "Stella" :email "stella@artois.beer"}
                  {:order-by [[:id :desc]]})
;; equivalent to
(jdbc/execute! ds ["SELECT * FROM address WHERE name = ? AND email = ? ORDER BY id DESC"
                   "Stella" "stella@artois.beer"])
```

`find-by-keys` also supports basic pagination with `:offset` and `:fetch` options which both accept numeric values and adds `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` to the generated query. To support MySQL and SQLite, you can specify `:limit` instead `:fetch` which adds `LIMIT ? OFFSET ?` to the generated query instead.

If you want to match all rows in a table -- perhaps with the pagination options in effect -- you can pass the keyword `:all` instead of either a hash map of column names and values or a vector containing a partial `WHERE` clause and parameters.

```clojure
(sql/find-by-keys ds :address :all {:order-by [:id] :offset 5 :fetch 10})
;; equivalent to
(jdbc/execute! ds ["SELECT * FROM address ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" 5 10])
```

If no rows match, `find-by-keys` returns `[]`, just like `execute!`.

## `get-by-id`

Given a table name (as a keyword) and a primary key value, with an optional primary key column name, execute a query on the database:

```clojure
(sql/get-by-id ds :address 2)
;; equivalent to
(sql/get-by-id ds :address 2 {}) ; empty options map
;; equivalent to
(sql/get-by-id ds :address 2 :id {}) ; empty options map
;; equivalent to
(jdbc/execute-one! ds ["SELECT * FROM address WHERE id = ?" 2])
```

Note that in order to override the default primary key column name (of `:id`), you need to specify both the column name and an options hash map.

If no rows match, `get-by-id` returns `nil`, just like `execute-one!`.

## Table & Column Entity Names

By default, `next.jdbc.sql` functions construct SQL strings with the entity names exactly matching the (unqualified) keywords provided. If you are trying to use a table name or column name that is a reserved name in SQL for your database, you will need to tell those functions to quote those names.

The namespace `next.jdbc.quoted` provides five functions that cover the most common types of entity quoting, and a modifier function for quoting dot-separated names (e.g., that include schemas):

* `ansi` -- wraps entity names in double quotes,
* `mysql` -- wraps entity names in back ticks,
* `sql-server` -- wraps entity names in square brackets,
* `oracle` -- an alias for `ansi`,
* `postgres` -- an alias for `ansi`.

* `schema` -- wraps a quoting function to support `dbo.table` style entity names.

These quoting functions can be provided to any of the friendly SQL functions above using the `:table-fn` and `:column-fn` options, in a hash map provided as the (optional) last argument in any call. If you want to provide your own entity naming function, you can do that:

```clojure
(defn snake-case [s] (str/replace s #"-" "_"))

(sql/insert! ds :my-table {:some "data"} {:table-fn snake-case})
```

If you have [camel-snake-kebab](https://clj-commons.org/camel-snake-kebab/) on your classpath, `next.jdbc` will contain `snake-kebab-opts` and `unqualified-snake-kebab-opts` which are hash maps containing `:column-fn` and `:table-fn` that use the `->snake_case` function from that library which performs a more sophisticated transformation.

> Note: The entity naming function is passed a string, the result of calling `name` on the keyword passed in. Also note that the default quoting functions do not handle schema-qualified names, such as `dbo.table_name` -- `sql-server` would produce `[dbo.table_name]` from that. Use the `schema` function to wrap the quoting function if you need that behavior, e.g,. `{:table-fn (schema sql-server)}` which would produce `[dbo].[table_name]`.

## HugSQL Quick Start

Here's how to get up and running quickly with `next.jdbc` and HugSQL. For more detail, consult the [HugSQL documentation](https://www.hugsql.org/). Add the following dependencies to your project (in addition to `seancorfield/next.jdbc` and whichever JDBC drivers you need):

```clojure
        com.layerware/hugsql-core {:mvn/version "0.5.1"}
        com.layerware/hugsql-adapter-next-jdbc {:mvn/version "0.5.1"}
```

_Check the HugSQL documentation for the latest versions to use!_

Write your SQL in `.sql` files that are on the classpath (somewhere under `src` or `resources`). For our purposes, assume a SQL file `db/example.sql` containing your first set of definitions. In your namespace, add these `require`s:

```clojure
            [hugsql.core :as hugsql]
            [hugsql.adapter.next-jdbc :as adapter]
            [next.jdbc :as jdbc]
```

At program startup you'll need to call these functions (either at the top-level of your namespace on inside your initialization function):

```clojure
;; regular SQL functions
(hugsql/def-db-fns "db/example.sql"
                   {:adapter (adapter/hugsql-adapter-next-jdbc)})

;; development/advanced usage functions that produce a vector containing
;; SQL and parameters that could be passed to jdbc/execute! etc
(hugsql/def-sqlvec-fns "db/example.sql"
                       {:adapter (adapter/hugsql-adapter-next-jdbc)})
```

Those calls will add function definitions to that namespace based on what is in the `.sql` files. Now set up your db-spec and datasource as usual with `next.jdbc`:

```clojure
(def db-spec {:dbytpe "h2:mem" :dbtype "example"}) ; assumes H2 driver in deps.edn

(def ds (jdbc/get-datasource db-spec))
```

Borrowing from Princess Bride examples from the HugSQL documentation, you can now do things like this:

```clojure
(create-characters-table ds)
;;=> [#:next.jdbc{:update-count 0}]
(insert-character ds {:name "Westley", :specialty "love"})
;;=> 1
```

By default, for compatibility with their default adapter (`clojure.java.jdbc`), the `next.jdbc` adapter uses the `next.jdbc.result-set/as-unqualified-lower-maps` builder function. You can specify a different builder function when you pass in the adapter:

```clojure
;; add require next.jdbc.result-set :as rs to your ns

(hugsql/def-db-fns "db/example.sql"
                   {:adapter (adapter/hugsql-adapter-next-jdbc
                              {:builder-fn rs/as-maps})})

;; now you'll get qualified as-is hash maps back:
(character-by-id ds {:id 1})
;;=> #:CHARACTERS{:ID 1, :NAME "Westley", :SPECIALTY "love", :CREATED_AT #inst "2019-09-27T18:52:54.413000000-00:00"}
```

[<: Getting Started](/doc/getting-started.md) | [Tips & Tricks :>](/doc/tips-and-tricks.md)
