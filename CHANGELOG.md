# Change Log

Only accretive/fixative changes will be made from now on.

## Unreleased Changes

The following changes have been committed to the **master** branch since the 1.0.1 release:

* Fix #45 by adding TimesTen driver support.
* Fix #44 so that `insert-multi!` with an empty `rows` vector returns `[]`.
* Fix #42 by adding specs for `execute-batch!` and `set-parameters` in `next.jdbc.prepare`.
* Fix #41 by improving docstrings and documentation, especially around prepared statement handling.
* Fix #40 by adding `next.jdbc.prepare/execute-batch!`.
* Added `assert`s in `next.jdbc.sql` as more informative errors for cases that would generate SQL exceptions (from malformed SQL).
* Added spec for `:order-by` to reflect what is actually permitted.
* Expose `next.jdbc.connect/dbtypes` as a table of known database types and aliases, along with their class name(s), port, and other JDBC string components.

## Stable Builds

* 2019-07-03 -- 1.0.1
  * Fix #37 by adjusting the spec for `with-transaction` to "require less" of the `:binding` vector.
  * Fix #36 by adding type hint in `with-transaction` macro.
  * Fix #35 by explaining the database-specific options needed to ensure `insert-multi!` performs a single, batched operation.
  * Fix #34 by explaining save points (in the Transactions documentation).
  * Fix #33 by updating the spec for the example `key-map` in `find-by-keys`, `update!`, and `delete!` to reflect that you cannot pass an empty map to these functions (and added tests to ensure the calls fail with spec errors).

* 2019-06-12 -- 1.0.0 "gold"
  * Address #31 by making `reify`'d objects produce a more informative string representation if they are printed (e.g., misusing `plan` by not reducing it or not mapping an operation over the rows).
  * Fix #26 by exposing `next.jdbc.result-set/datafiable-result-set` so that various `java.sql.DatabaseMetaData` methods that return result metadata information in `ResultSet`s can be easily turned into a fully realized result set.

* 2019-06-04 -- 1.0.0-rc1:
  * Fix #24 by adding return type hints to `next.jdbc` functions.
  * Fix #22 by adding `next.jdbc.optional` with six map builders that omit `NULL` columns from the row hash maps.
  * Documentation improvements (#27, #28, and #29), including changing "connectable" to "transactable" for the `transact` function and the `with-transaction` macro (for consistency with the name of the underlying protocol).
  * Fix #30 by adding `modified` variants of column name functions and builders. The `lower` variants have been rewritten in terms of these new `modified` variants. This adds `:label-fn` and `:qualifier-fn` options that mirror `:column-fn` and `:table-fn` for row builders.

* 2019-05-24 -- 1.0.0-beta1:
  * Set up CircleCI testing (just local DBs for now).
  * Address #21 by adding `next.jdbc.specs` and documenting basic usage.
  * Fix #19 by caching loaded database driver classes.
  * Address #16 by renaming `reducible!` to `plan` (**BREAKING CHANGE!**).
  * Address #3 by deciding to maintain this library outside Clojure Contrib.

## Alpha Builds

* 2019-05-04 -- 1.0.0-alpha13 -- Fix #18 by removing more keys from properties when creating connections.
* 2019-04-26 -- 1.0.0-alpha12 -- Fix #17 by renaming `:next.jdbc/sql-string` to `:next.jdbc/sql-params` (**BREAKING CHANGE!**) and pass whole vector.
* 2019-04-24 -- 1.0.0-alpha11 -- Rename `:gen-fn` to `:builder-fn` (**BREAKING CHANGE!**); Fix #13 by adding documentation for `datafy`/`nav`/`:schema`; Fix #15 by automatically adding `:next.jdbc/sql-string` (as of 1.0.0-alpha12: `:next.jdbc/sql-params`) into the options hash map, so custom builders can depend on the SQL string.
* 2019-04-22 -- 1.0.0-alpha9 -- Fix #14 by respecting `:gen-fn` (as of 1.0.0-alpha11: `:builder-fn`) in `execute-one` for `PreparedStatement`.
* 2019-04-21 -- 1.0.0-alpha8 -- Initial publicly announced release.
