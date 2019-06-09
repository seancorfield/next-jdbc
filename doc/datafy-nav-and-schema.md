# `datafy`, `nav`, and the `:schema` option

Clojure 1.10 introduced a new namespace, [`clojure.datafy`](http://clojure.github.io/clojure/clojure.datafy-api.html), and two new protocols (`Datafiable` and `Navigable`) that allow for generalized, lazy navigation around data structures. Cognitect also released [REBL](http://rebl.cognitect.com/) -- a graphical, interactive tool for browsing Clojure data structures, based on the new `datafy` and `nav` functions.

Shortly after REBL's release, I added experimental support to `clojure.java.jdbc` for `datafy` and `nav` that supported lazy navigation through result sets into foreign key relationships and connected rows and tables. `next.jdbc` bakes that support into result sets produced by `execute!` and `execute-one!`.

## The `datafy`/`nav` Lifecycle

Here's how the process works:

* `execute!` and `execute-one!` produce result sets containing rows that are `Datafiable`,
* Tools like REBL can call `datafy` on result sets to render them as "pure data" (which they already are, but this makes them also `Navigable`),
* Tools like REBL allow users to "drill down" into elements of rows in the "pure data" result set, using `nav`,
* If a column in a row represents a foreign key into another table, calling `nav` will fetch the related row(s),
* Those can in turn be `datafy`'d and `nav`'d to continue drilling down through connected data in the database.

In addition to `execute!` and `execute-one!`, you can call `next.jdbc.result-set/datafiable-result-set` on any `ResultSet` object to produce a result set whose rows are `Datafiable`. Inside a reduction over the result of `plan`, you can call `next.jdbc.result-set/datafiable-row` on a row to produce a `Datafiable` row. That will realize the entire row, including generating column names using the row builder specified (or `as-maps` by default).

## Identifying Foreign Keys

By default, `next.jdbc` assumes that a column named `<something>id` or `<something>_id` is a foreign key into a table called `<something>` with a primary key called `id`. As an example, if you have a table `address` which has columns `id` (the primary key), `name`, `email`, etc, and a table `contact` which has various columns including `addressid`, then if you retrieve a result set based on `contact`, call `datafy` on it and then "drill down" into the columns, when `(nav row :contact/addressid v)` is called (where `v` is the value of that column in that row) `next.jdbc`'s implementation of `nav` will fetch a single row from the `address` table, identified by `id` matching `v`.

You can override this default behavior for any column in any table by providing a `:schema` option that is a hash map whose keys are column names (usually the table-qualified keywords that `next.jdbc` produces by default) and whose values are tuples containing the name of the table to which that column is a foreign key and the name of the key column within that table. These tuples can optionally include a third value which indicates the cardinality of the foreign key relationship: `:one` or `:many`. The default is `:one` and indicates a one-to-one or many-to-one relationship -- `nav`igation will produce a single row. `:many` indicates a one-to-many or many-to-many relationship -- `nav`igation will produce a result set.

The default behavior in the example above is equivalent to this `:schema` value:

```clojure
{:contact/addressid [:address :id :one]} ; :one is the default and could be omitted
```

If you had a table to track the current valid/bouncing status of email addresses, where `email` is the primary key, you could provide automatic navigation into that using:

```clojure
{:contact/addressid [:address :id :one]
 :address/email [:deliverability :email]}
```

If you use foreign key constraints in your database, you could probably generate this `:schema` data structure automatically from the metadata in your database.

## Behind The Scenes

Making rows datafiable is implemented by adding metadata to each row with a key of `clojure.core.protocols/datafy` and a function as the value. That function closes over the connectable and options passed in to the `execute!` or `execute-one!` call that produced the result set containing those rows.

When called (`datafy` on a row), it adds metadata to the row with a key of `clojure.core.protocols/nav` and another function as the value. That function also closes over the connectable and options passed in.

When that is called (`nav` on a row, column name, and column value), if a `:schema` entry exists for that column or it matches the default convention described above, then it will fetch row(s) using `next.jdbc`'s `Executable` functions `-execute-one` or `-execute-all`, passing in the connectable and options closed over.

The protocol `next.jdbc.result-set/DatafiableRow` has a default implementation of `datafiable-row` for `clojure.lang.IObj` that just adds the metadata to support `datafy`. There is also an implementation baked into the result set handling behind `plan` so that you can call `datafiable-row` directly during reduction and get a fully-realized row that can be `datafy`'d (and then `nav`igated).

In addition, you can call `next.jdbc.result-set/datafiable-result-set` on any `ResultSet` object and get a fully realized, datafiable result set created using any of the result set builders.

[<: All The Options](/doc/all-the-options.md) | [Migration from `clojure.java.jdbc` :>](/doc/migration-from-clojure-java-jdbc.md)
