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

These functions are described in more detail below. They are intended to cover the most common, simple SQL operations. If you need more expressiveness, consider one of the following libraries to build SQL/parameter vectors, or run queries:

* [HoneySQL](https://github.com/jkk/honeysql)
* [seql](https://github.com/exoscale/seql)
* [SQLingvo](https://github.com/r0man/sqlingvo)
* [Walkable](https://github.com/walkable-server/walkable)

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

Note: this expands to a single SQL statement with placeholders for every
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

`find-by-keys` supports an `:order-by` option which can specify a vector of column names to sort the results by. Elements may be column names or pairs of a column name and the direction to sort: `:asc` or `:desc`:

```clojure
(sql/find-by-keys ds :address
                  {:name "Stella" :email "stella@artois.beer"}
                  {:order-by [[:id :desc]]})
;; equivalent to
(jdbc/execute! ds ["SELECT * FROM address WHERE name = ? AND email = ? ORDER BY id DESC"
                   "Stella" "stella@artois.beer"])
```

## `get-by-id`

Given a table name (as a keyword) and a primary key value, with an optional primary key column name, execute a query on the database:

```clojure
(sql/get-by-id ds :address 2)
;; equivalent to
(sql/get-by-id ds :address 2 {})
;; equivalent to
(sql/get-by-id ds :address 2 :id {})
;; equivalent to
(jdbc/execute-one! ds ["SELECT * FROM address WHERE id = ?" 2])
```

Note that in order to override the default primary key column name (of `:id`), you need to specify both the column name and an options hash map.

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

Note that the entity naming function is passed a string, the result of calling `name` on the keyword passed in. Also note that the default quoting functions do not handle schema-qualified names, such as `dbo.table_name` -- `sql-server` would produce `[dbo.table_name]` from that. Use the `schema` function to wrap the quoting function if you need that behavior, e.g,. `{:table-fn (schema sql-server)}` which would produce `[dbo].[table_name]`.

[<: Getting Started](/doc/getting-started.md) | [Result Set Builders :>](/doc/result-set-builders.md)
