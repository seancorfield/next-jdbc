# Migrating from `clojure.java.jdbc`

This page attempts to list all of the differences between [clojure.java.jdbc](https://github.com/clojure/java.jdbc) and `next.jdbc`. Some of them are large and obvious, some of them are small and subtle -- all of them are deliberate design choices.

## Conceptually

`clojure.java.jdbc` focuses heavily on a `db-spec` hash map to describe the various ways of interacting with the database and grew from very imperative origins that expose a lot of the JDBC API (multiple types of SQL execution, some operations returned hash maps, others update counts as integers, etc).

`next.jdbc` focuses on using protocols and native Java JDBC types where possible (for performance and simplicity) and strives to present a more modern Clojure API with namespace-qualified keywords in hash maps, reducible SQL operations as part of the primary API, and a streamlined set of SQL execution primitives. Execution always returns a hash map (for one result) or a vector of hash maps (for multiple results) -- even update counts are returned as if they were result sets.

### Rows and Result Sets

`clojure.java.jdbc` returned result sets (and generated keys) as hash maps with simple, lower-case keys by default. `next.jdbc` returns result sets (and generated keys) as hash maps with qualified, as-is keys by default: each key is qualified by the name of table from which it is drawn, if known. The as-is default is chosen to a) improve performance and b) not mess with the data. Using a `:builder-fn` option of `next.jdbc.result-set/as-unqualified-maps` will produce simple, as-is keys. Using a `:builder-fn` option of `next.jdbc.result-set/as-unqualified-lower-maps` will produce simple, lower-case keys -- the most compatible with `clojure.java.jdbc`'s default behavior.

If you used `:as-arrays? true`, you will need to use a `:builder-fn` option of `next.jdbc.result-set/as-arrays` (or the unqualified or lower variant, as appropriate).

## Primary API

`next.jdbc` has a deliberately narrow primary API that has (almost) no direct overlap with `clojure.java.jdbc`:

* `get-datasource` -- has no equivalent in `clojure.java.jdbc` but is intended to emphasize `javax.sql.DataSource` as a starting point,
* `get-connection` -- overlaps with `clojure.java.jdbc` (and returns a `java.sql.Connection`) but accepts only a subset of the options (`:dbtype`/`:dbname` hash map, `String` JDBC URI); `clojure.java.jdbc/get-connection` accepts `{:datasource ds}` whereas `next.jdbc/get-connection` accepts the `javax.sql.DataSource` object directly,
* `prepare` -- somewhat similar to `clojure.java.jdbc/prepare-statement` but it accepts a vector of SQL and parameters (compared to just a raw SQL string),
* `reducible!` -- somewhat similar to `clojure.java.jdbc/reducible-query` but accepts arbitrary SQL statements for execution,
* `execute!` -- has no direct equivalent in `clojure.java.jdbc` (but it can replace most uses of both `query` and `db-do-commands`),
* `execute-one!` -- has no equivalent in `clojure.java.jdbc` (but it can replace most uses of `query` that currently use `:result-set-fn first`),
* `transact` -- similar to `clojure.java.jdbc/db-transaction*`,
* `with-transaction` -- similar to `clojure.java.jdbc/with-db-transaction`.

If you were using a bare `db-spec` hash map with `:dbtype`/`:dbname`, or a JDBC URI string everywhere, that should mostly work with `next.jdbc` since most functions accept a "connectable", but it would be better to create a datasource first, and then pass that around.

If you were already creating `db-spec` as a pooled connection datasource -- a `{:datasource ds}` hashmap -- then passing `(:datasource db-spec)` to the `next.jdbc` functions is the simplest migration path.

If you were using other forms of the `db-spec` hash map, you'll need to adjust to one of the three modes above, since those are the only ones supported in `next.jdbc`.

The `next.jdbc.sql` namespace contains several functions with similarities to `clojure.java.jdbc`'s core API:

* `insert!` -- similar to `clojure.java.jdbc/insert!` but only supports inserting a single map,
* `insert-multi!` -- similar to `clojure.java.jdbc/insert-multi!` but only supports inserting columns and a vector of row values,
* `query` -- similar to `clojure.java.jdbc/query`,
* `find-by-keys` -- similar to `clojure.java.jdbc/find-by-keys` but will also accept a partial where clause (vector) instead of a hash map of column name/value pairs,
* `get-by-id` -- similar to `clojure.java.jdbc/get-by-id`,
* `update!` -- similar to `clojure.java.jdbc/update!` but will also accept a hash map of column name/value pairs instead of a partial where clause (vector),
* `delete!` -- similar to `clojure.java.jdbc/delete!` but will also accept a hash map of column name/value pairs instead of a partial where clause (vector).

If you are using `:identifiers` and/or `:entities`, you will need to change to appropriate `:builder-fn` and/or `:table-fn`/`:column-fn` options. For the latter, instead of the `quoted` function, there is the `next.jdbc.quoted` namespace which contains functions for the common quoting strategies.

If you are using `:result-set-fn` and/or `:row-fn`, you will need to change to explicit calls (to the result set function, or to `map` the row function), or to use the `reducible!` approach with `reduce` or various transducing functions. Note: this means that result sets are never exposed lazily in `next.jdbc` -- in `clojure.java.jdbc` you had to be careful that your `:result-set-fn` was eager, but in `next.jdbc` you either reduce the result set eagerly (via `reducible!`) or you get a fully-realized result set data structure back (from `execute!` and `execute-one!`). As with `clojure.java.jdbc` however, you can still stream result sets from the database and process them via reduction (was `reducible-query`, now `reducible!`). Remember that you can terminate a reduction early by using the `reduced` function to wrap the final value you produce.

## Further Minor differences

These are mostly drawn from [Issue #5](https://github.com/seancorfield/next-jdbc/issues/5) although most of the bullets in that issue are described in more detail above.

* Keyword options no longer end in `?` -- to reflect the latest best practice on predicates vs. attributes,
* `with-db-connection` has been replaced by just `with-open` containing a call to `get-connection`,
* `with-transaction` can take a `:rollback-only` option, but there is no way to change a transaction to rollback _dynamically_; throw an exception instead (all transactions roll back on an exception)
* The extension points for setting parameters and reading columns are now `SettableParameter` and `ReadableColumn` protocols.

[<: `datafy`, `nav`, and `:schema`](/doc/datafy-nav-and-schema.md)
