# `datafy`, `nav`, and the `:schema` option

Clojure 1.10 introduced a new namespace, [`clojure.datafy`](http://clojure.github.io/clojure/clojure.datafy-api.html), and two new protocols (`Datafiable` and `Navigable`) that allow for generalized, lazy navigation around data structures. Cognitect also released [REBL](http://rebl.cognitect.com/) -- a graphical, interactive tool for browsing Clojure data structures, based on the new `datafy` and `nav` functions.

Shortly after REBL's release, I added experimental support to `clojure.java.jdbc` for `datafy` and `nav` that supported lazy navigation through result sets into foreign key relationships and connected rows and tables. `next.jdbc` bakes that support into result sets produced by `execute!` and `execute-one!`.

In addition to `datafy` and `nav` support in the result sets, as of version 1.0.462, there is a `next.jdbc.datafy` namespace that can be required to extend these protocols to a number of JDBC object types. See **JDBC Datafication** near the end of this page for more detail of this.

## The `datafy`/`nav` Lifecycle on Result Sets

Here's how the process works, for result sets produced by `next.jdbc`:

* `execute!` and `execute-one!` produce result sets containing rows that are `Datafiable`,
* Tools like REBL can call `datafy` on result sets to render them as "pure data" (which they already are, but this makes them also `Navigable`),
* Tools like REBL allow users to "drill down" into elements of rows in the "pure data" result set, using `nav`,
* If a column in a row represents a foreign key into another table, calling `nav` will fetch the related row(s),
* Those can in turn be `datafy`'d and `nav`'d to continue drilling down through connected data in the database.

In addition to `execute!` and `execute-one!`, you can call `next.jdbc.result-set/datafiable-result-set` on any `ResultSet` object to produce a result set whose rows are `Datafiable`. Inside a reduction over the result of `plan`, you can call `next.jdbc.result-set/datafiable-row` on a row to produce a `Datafiable` row. That will realize the entire row, including generating column names using the row builder specified (or `as-maps` by default).

### Identifying Foreign Keys

By default, `next.jdbc` assumes that a column named `<something>id` or `<something>_id` is a foreign key into a table called `<something>` with a primary key called `id`. As an example, if you have a table `address` which has columns `id` (the primary key), `name`, `email`, etc, and a table `contact` which has various columns including `addressid`, then if you retrieve a result set based on `contact`, call `datafy` on it and then "drill down" into the columns, when `(nav row :contact/addressid v)` is called (where `v` is the value of that column in that row) `next.jdbc`'s implementation of `nav` will fetch a single row from the `address` table, identified by `id` matching `v`.

You can override this default behavior for any column in any table by providing a `:schema` option that is a hash map whose keys are column names (usually the table-qualified keywords that `next.jdbc` produces by default) and whose values are table-qualified keywords, optionally wrapped in vectors, that identity the name of the table to which that column is a foreign key and the name of the key column within that table.

The default behavior in the example above is equivalent to this `:schema` value:

```clojure
(jdbc/execute! ds
               ["select * from contact where city = ?" "San Francisco"]
               ;; a one-to-one or many-to-one relationship
               {:schema {:contact/addressid :address/id}})
```

If you had a table to track the valid/bouncing status of email addresses over time, `:deliverability`, where `email` is the non-unique key, you could provide automatic navigation into that using:

```clojure
(jdbc/execute! ds
               ["select * from contact where city = ?" "San Francisco"]
               ;; one-to-many or many-to-many
               {:schema {:contact/addressid :address/id
                         :address/email [:deliverability/email]}})
```

When you indicate a `*-to-many` relationship, by wrapping the foreign table/key in a vector, `next.jdbc`'s implementation of `nav` will fetch a multi-row result set from the target table.

If you use foreign key constraints in your database, you could probably generate this `:schema` data structure automatically from the metadata in your database. Similarly, if you use a library that depends on an entity relationship map (such as [seql](https://exoscale.github.io/seql/) or [walkable](https://walkable.gitlab.io/)), then you could probably generate this `:schema` data structure from that entity map.

### Behind The Scenes

Making rows datafiable is implemented by adding metadata to each row with a key of `clojure.core.protocols/datafy` and a function as the value. That function closes over the connectable and options passed in to the `execute!` or `execute-one!` call that produced the result set containing those rows.

When called (`datafy` on a row), it adds metadata to the row with a key of `clojure.core.protocols/nav` and another function as the value. That function also closes over the connectable and options passed in.

When that is called (`nav` on a row, column name, and column value), if a `:schema` entry exists for that column or it matches the default convention described above, then it will fetch row(s) using `next.jdbc`'s `Executable` functions `-execute-one` or `-execute-all`, passing in the connectable and options closed over.

The protocol `next.jdbc.result-set/DatafiableRow` has a default implementation of `datafiable-row` for `clojure.lang.IObj` that just adds the metadata to support `datafy`. There is also an implementation baked into the result set handling behind `plan` so that you can call `datafiable-row` directly during reduction and get a fully-realized row that can be `datafy`'d (and then `nav`igated).

In addition, you can call `next.jdbc.result-set/datafiable-result-set` on any `ResultSet` object and get a fully realized, datafiable result set created using any of the result set builders.

## JDBC Datafication

If you require `next.jdbc.datafy`, the `Datafiable` protocol is extended to several JDBC object types, so that calling `datafy` will turn them into hash maps according to Java Bean introspection, similar to `clojure.core/bean` although `next.jdbc` uses `clojure.java.data/from-java-shallow` (from [`org.clojure/java.data`](https://github.com/clojure/java.data)), with some additions as described below.

* `java.sql.Connection` -- datafies as a bean; The `:metaData` property is a `java.sql.DatabaseMetaData`, which is also datafiable.
* `DatabaseMetaData` -- datafies as a bean, with an additional `:all-tables` property (that is a dummy object); six properties are navigable to produce fully-realized datafiable result sets:
  * `all-tables` -- produced from `(.getTables this nil nil nil nil)`, this is all the tables and views available from the connection that produced the database metadata,
  * `catalogs` -- produced from `(.getCatalogs this)`
  * `clientInfoProperties` -- all the client properties that the database driver supports,
  * `schemas` -- produced from `(.getSchemas this)`,
  * `tableTypes` -- produced from `(.getTableTypes this)`,
  * `typeInfo` -- produced from `(.getTypeInfo this)`.
* `ParameterMetaData` -- datafies as a vector of parameter descriptions; each parameter hash map has: `:class` (the name of the parameter class -- JVM), `:mode` (one of `:in`, `:in-out`, or `:out`), `:nullability` (one of: `:null`, `:not-null`, or `:unknown`), `:precision`, `:scale`, `:type` (the name of the parameter type -- SQL), and `:signed` (Boolean).
* `ResultSet` -- datafies as a bean; if the `ResultSet` has an associated `Statement` and that in turn has an associated `Connection` then an additional key of `:rows` is provided which is a datafied result set, from `next.jdbc.result-set/datafiable-result-set` with default options. This is provided as a convenience, purely for datafication of other JDBC data types -- in normal `next.jdbc` usage, result sets are datafied under full user control.
* `ResultSetMetaData` -- datafies as a vector of column descriptions; each column hash map has: `:auto-increment`, `:case-sensitive`, `:catalog`, `:class` (the name of the column class -- JVM), `:currency` (Boolean), `:definitely-writable`, `:display-size`, `:label`, `:name`, `:nullability`, `:precision`, `:read-only`, `:searchable`, `:signed`, `:scale`, `:schema`, `:table`, `:type`, and `:writable`.
* `Statement` -- datafies as a bean.

See the Java documentation for these JDBC types for further details on what all the properties from each of these classes mean and which are `int`, `String`, or some other JDBC object type.

In addition, requiring this namespace will affect how `next.jdbc.result-set/metadata` behaves inside the reducing function applied to the result of `plan`. Without this namespace loaded, that function will return a raw `ResultSetMetaData` object (which must not leak outside the reducing function). With this namespace loaded, that function will, instead, return a Clojure data structure describing the columns in the result set.
