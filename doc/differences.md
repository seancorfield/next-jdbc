# Migrating from `clojure.java.jdbc`

This page attempts to list all of the differences between [clojure.java.jdbc](https://github.com/clojure/java.jdbc) and `next.jdbc`. Some of them are large and obvious, some of them are small and subtle -- all of them are deliberate design choices.

## Primary API

`next.jdbc` has a deliberately narrow primary API that has (almost) no direct overlap with `clojure.java.jdbc`:

* `get-datasource` -- has no equivalent in `clojure.java.jdbc` but is intended to emphasize `javax.sql.DataSource` as a starting point,
* `get-connection` -- overlaps with `clojure.java.jdbc` (and returns a `java.sql.Connection`) but accepts only a subset of the options (`:dbtype`/`:dbname` hash map, `String` JDBC URI); `clojure.java.jdbc/get-connection` accepts `{:datasource ds}` whereas `next.jdbc/get-connection` accepts the `javax.sql.DataSource` object directly,
* `prepare` -- somewhat similar to `clojure.java.jdbc/prepare-statement` but it accepts a vector of SQL and parameters (compared to just a raw SQL string),
* `reducible!` -- somewhat similar to `clojure.java.jdbc/reducible-query` but accepts arbitrary SQL statements for execution,
* `execute!` -- has no equivalent in `clojure.java.jdbc`,
* `execute-one!` -- has no equivant in `clojure.java.jdbc`,
* `transact` -- similar to `clojure.java.jdbc/db-transaction*`,
* `with-transaction` -- similar to `clojure.java.jdbc/with-db-transaction`.

If you were using a bare `db-spec` hash map or JDBC URI string everywhere, that should mostly work with `next.jdbc` since most functions accept a "connectable", but it would be better to create a datasource first, and then pass that around.

If you were already creating a pooled connection datasource, as a `{:datasource ds}` hashmap, then passing `(:datasource db-spec)` to the `next.jdbc` functions is the simplest migration path.

If you were using other forms of the `db-spec` hash map, you'll need to adjust to one of the three modes above, since those are the only ones supported in `next.jdbc`.

The `next.jdbc.sql` namespace contains several functions with similarities to `clojure.java.jdbc`'s core API:

* `insert!` -- similar to `clojure.java.jdbc/insert!` but only supports inserting a single map,
* `insert-multi!` -- similar to `clojure.java.jdbc/insert-multi!` but only supports inserting columns and a vector of row values,
* `query` -- similar to `clojure.java.jdbc/query`,
* `find-by-keys` -- similar to `clojure.java.jdbc/find-by-keys` but also accepts a partial where clause (vector),
* `get-by-id` -- similar to `clojure.java.jdbc/get-by-id`,
* `update!` -- similar to `clojure.java.jdbc/update!` but also accepts a hash map of column name/value pairs,
* `delete!` -- similar to `clojure.java.jdbc/delete!` but also accepts a hash map of column name/value pairs.

If you are using `:identifiers` and/or `:entities`, you will need to change to appropriate `:gen-fn` and/or `:table-fn`/`:column-fn` options.

If you are using `:result-set-fn` and/or `:row-fn`, you will need to change to explicit calls (to the result set function, or to `map` the row function), or to use the `reducible!` approach with `reduce` or various transducing functions.

## Minor differences from Issue #5

(this section needs to be edited/expanded)

* Use of protocols instead of db-spec / hash map
* Several legacy db-spec formats are no longer accepted, including Raw, Existing Connection, DriverManager, Factory, DataSource, JNDI, URI.
* Auto-qualified column names (the qualifier is table from which each column comes, if known)
* Result sets are never lazy now -- either you reduce over them, or you get an eager vector of hash maps; for streaming, reduce the `reducible!`
* The `:as-arrays?` option has been replaced by a result set builder function `next.jdbc.result-set/as-arrays` (the new `RowBuilder` and `ResultSetBuilder` protocols allow for a lot more flexibility about how rows and result sets are constructed)
* There is only one type of SQL execution, using the generic `.execute` -- no worrying about batches and updates and so on
* No `?` on keyword options
* Update counts come back as a "result set" for consistency
* The `:identifiers` option is gone and column names come back as qualified keywords with no additional processing, rather than `clojure.string/lower-case` (partly so the default is faster but also so the default behavior is to "not mess with things") -- the result set builder machinery provides an easy way to provide custom column naming if you need it
* `:result-set-fn` and `:row-fn` are not supported -- either wrap the call around the query or handle it via a reduction; for `:result-set-fn first` use `execute-one!` instead
* The `:entities` option has been replaced by `:table-fn` and `:column-fn` and the `quoted` function is gone -- `next.jdbc.quoted` now contains specific functions, named for the database/type of quoting: `ansi`, `mysql`, `sql-server`, with `oracle` and `postgres` as aliases for `ansi`
* `with-db-connection` is just `with-open` with a call to `get-connection`
* `with-transaction` can take a `:rollback-only` option, but there is no way to change a transaction to rollback _dynamically_; throw an exception instead (all transactions roll back on an exception)
* The "sugar" functions -- `query`, `insert!`, `insert-multi!`, `update!`, and `delete!` live in `next.jdbc.sql` as they are no longer part of the core API
* `find-by-keys` no longer supports `:order-by` (but this may come back)
* Extension points for setting parameters and reading columns are `SettableParameter` and `ReadableColumn`

More will be added...
