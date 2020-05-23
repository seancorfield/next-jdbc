# Change Log

Only accretive/fixative changes will be made from now on.

## Stable Builds

* 2020-05-23 -- 1.0.445
  * Enhanced support in `plan` for "metadata" access: `row-number` and `column-names` can be called on the abstract row (even after calling `datafiable-row`). In addition, `Associative` access via numeric "keys" will read columns by index, and row abstractions now support `Indexed` access via `nth` (which will also read columns by index). Fixes #110.
  * Support for Stuart Sierra's Component library, via `next.jdbc.connection/component`. See updated **Getting Started** guide for usage.
  * Add example of getting generated keys from `execute-batch!`.
  * Add MySQL-specific result set streaming tip.
  * Add array handling example to PostgreSQL **Tips & Tricks**. PR #108 from @maxp.
  * Investigate possible solutions for #106 (mutable transaction thread safety) -- experimental `locking` on `Connection` object.

* 2020-04-10 -- 1.0.424
  * In **Tips & Tricks**, noted that MySQL returns `BLOB` columns as `byte[]` instead of `java.sql.Blob`.
  * Address #103, #104 by adding a section on timeouts to **Tips & Tricks**.
  * Fix #102 by allowing keywords or strings in `:return-keys`.
  * Fix #101 by tightening the spec on a JDBC URL to correctly reflect that it must start with `jdbc:`.
  * Add support for calling `.getLoginTimeout`/`.setLoginTimeout` on the reified `DataSource` returned by `get-datasource` when called on a hash map "db-spec" or JDBC URL string.
  * Documentation improvements based on feedback (mostly from Slack), including a section on database metadata near the end of **Getting Started**.

* 2020-03-16 -- 1.0.409
  * Address #100 by adding support for MariaDB (@green-coder). Set `NEXT_JDBC_TEST_MARIADB=true` as well as `NEXT_JDBC_TEST_MYSQL=true` in order to run tests against MariaDB.

* 2020-03-14 -- 1.0.405 (no code changes -- just documentation)
  * Improve documentation around `plan` so `reduce` etc is more obvious.
  * Attempt to drive readers to cljdoc.org instead of the GitHub version (which is harder to navigate).

* 2020-03-02 -- 1.0.395
  * Add `read-as-instant` and `read-as-local` functions to `next.jdbc.date-time` to extend `ReadableColumn` so that SQL `DATE` and `TIMESTAMP` columns can be read as Java Time types.
  * Specifically call out PostgreSQL as needing `next.jdbc.date-time` to enable automatic conversion of `java.util.Date` objects to SQL timestamps for prepared statements (#95).
  * Split **Tips & Tricks** into its own page, with a whole new section on using JSON data types with PostgreSQL (#94 -- thank you @vharmain).
  * Bump dependencies to latest.

* 2020-02-28 -- 1.0.384
  * Add PostgreSQL streaming option information to **Tips & Tricks** (#87).
  * Minor documentation fixes (including #85, #92, #93).
  * Improve `Unknown dbtype` exception message (to clarify that `:classname` is also missing, #90).
  * Fix #88 by using 1-arity `keyword` call when table name unavailable (or `:qualifier-fn` returns `nil` or an empty string); also allows `:qualifier-fn` function to be called on empty table name (so `:qualifier-fn (constantly "qual")` will now work much like `clojure.java.jdbc`'s `:qualifier "qual"` worked).
  * Address #89, #91 by making minor performance tweaks to `next.jdbc.result-set` functions.
  * Planning to move to MAJOR.MINOR.COMMITS versioning scheme (1.0.384).

* 2019-12-20 -- 1.0.13
  * Fix #82 by adding `clojure.java.data`-based support for setting arbitrary properties on `Connection` and `PreparedStatement` objects, post-creation. Note: this uses the Java reflection API under the hood.
  * Adds `next.jdbc.prepare/statement` to create `Statement` objects with all the options available to `prepare` except `:return-keys`.
  * Update `org.clojure/java.data` to 0.1.5 (for property setting).
  * Additional clarifications in the documentation based on feedback on Slack.

* 2019-12-11 -- 1.0.12
  * Address #81 by splitting the SQL-building functions out of `next.jdbc.sql` into `next.jdbc.sql.builder`.
  * Fix #80 by avoiding the auto-commit restore after a failed rollback in a failed transaction.
  * Address #78 by documenting the `:connectionInitSql` workaround for HikariCP/PostgreSQL and non-default schemas.

* 2019-12-07 -- 1.0.11
  * Fix #76 by avoiding conversions on `java.sql.Date` and `java.sql.Timestamp`.
  * Add testing against Microsoft SQL Server (run tests with environment variables `NEXT_JDBC_TEST_MSSQL=yes` and `MSSQL_SA_PASSWORD` set to your local -- `127.0.0.1:1433` -- SQL Server `sa` user password; assumes that it can create and drop `fruit` and `fruit_time` tables in the `model` database).
  * Add testing against MySQL (run tests with environment variables `NEXT_JDBC_TEST_MYSQL=yes` and `MYSQL_ROOT_PASSWORD` set to your local -- `127.0.0.1:3306` -- MySQL `root` user password; assumes you have already created an empty database called `clojure_test`).
  * Bump several JDBC driver versions for up-to-date testing.
  * Minor documentation fixes.

* 2019-11-14 -- 1.0.10
  * Fix #75 by adding support for `java.sql.Statement` to `plan`, `execute!`, and `execute-one!`.
  * Address #74 by making several small changes to satisfy Eastwood.
  * Fix #73 by providing a new, optional namespace `next.jdbc.date-time` that can be required if your database driver needs assistance converting `java.util.Date` (PostgreSQL!) or the Java Time types to SQL `timestamp` (or SQL `date`/`time`).
  * Fix link to **All The Options** in **Migration from `clojure.java.jdbc`**. PR #71 (@laurio).
  * Address #70 by adding **CLOB & BLOB SQL Types** to the **Tips & Tricks** section of **Friendly SQL Functions** and by adding `next.jdbc.result-set/clob-column-reader` and `next.jdbc.result-set/clob->string` helper to make it easier to deal with `CLOB` column data.
  * Clarify what `execute!` and `execute-one!` produce when the result set is empty (`[]` and `nil` respectively, and there are now tests for this). Similarly for `find-by-keys` and `get-by-id`.
  * Add **MS SQL Server** section to **Tips & Tricks** to note that it returns an empty string for table names by default (so table-qualified column names are not available). Using the `:result-type` (scroll) and `:concurrency` options will cause table names to be returned.
  * Clarify that **Friendly SQL Functions** are deliberately simple (hint: they will not be enhanced or expanded -- use `plan`, `execute!`, and `execute-one!` instead, with a DSL library if you want!).
  * Improve migration docs: explicitly recommend the use of a datasource for code that needs to work with both `clojure.java.jdbc` and `next.jdbc`; add caveats about column name conflicts (in several places).
  * Improve `datafy`/`nav` documentation around `:schema`.
  * Update `org.clojure/java.data` to `"0.1.4"` (0.1.2 fixes a number of reflection warnings).

* 2019-10-11 -- 1.0.9
  * Address #69 by trying to clarify when to use `execute-one!` vs `execute!` vs `plan`.
  * Address #68 by clarifying that builder functions do not affect the "fake result set" containing `:next.jdbc/update-count`.
  * Fix #67 by adding `:jdbcUrl` version spec.
  * Add `next.jdbc.optional/as-maps-adapter` to provide a way to override the default result set reading behavior of using `.getObject` when omitting SQL `NULL` values from result set maps.

* 2019-09-27 -- 1.0.8
  * Fix #66 by adding support for a db-spec hash map format containing a `:jdbcUrl` key (consistent with `->pool`) so that you can create a datasource from a JDBC URL string and additional options.
  * Address #65 by adding a HugSQL "quick start" to the **Friendly SQL Functions** section of the docs.
  * Add `next.jdbc.specs/unstrument`. PR #64 (@gerred).
  * Address #63 by improving documentation around qualified column names and `:qualifier` (`clojure.java.jdbc`) migration, with a specific caveat about Oracle not fully supporting `.getTableName()`.

* 2019-09-09 -- 1.0.7
  * Address #60 by supporting simpler schema entry formats: `:table/column` is equivalent to the old `[:table :column :one]` and `[:table/column]` is equivalent to the old `[:table :column :many]`. The older formats will continue to be supported but should be considered deprecated. PR #62 (@seancorfield).
  * Added test for using `ANY(?)` and arrays in PostgreSQL for `IN (?,,,?)` style queries. Added a **Tips & Tricks** section to **Friendly SQL Functions** with database-specific suggestions, that starts with this one.
  * Improved documentation in several areas.

* 2019-08-24 -- 1.0.6
  * Improved documentation around `insert-multi!` and `execute-batch!` (addresses #57).
  * Fix #54 by improving documentation around data type conversions (and the `ReadableColumn` and `SettableParameter` protocols).
  * Fix #52 by using a US-locale function in the "lower" result set builders to avoid unexpected character changes in column names in locales such as Turkish. If you want the locale-sensitive behavior, pass `clojure.string/lower-case` into one of the "modified" result set builders.
  * Add `next.jdbc.result-set/as-maps-adapter` and `next.jdbc.result-set/as-arrays-adapter` to provide a way to override the default result set reading behavior of using `.getObject`.
  * Update `org.clojure/test.check` to `"0.10.0"`.

* 2019-08-05 -- 1.0.5
  * Fix #51 by implementing `IPersistentMap` fully for the "mapified" result set inside `plan`. This adds support for `dissoc` and `cons` (which will both realize a row), `count` (which returns the column count but does not realize a row), `empty` (returns an empty hash map without realizing a row), etc.
  * Improved documentation around connection pooling (HikariCP caveats).

* 2019-07-24 -- 1.0.4
  * Fix #50 by adding machinery to test against (embedded) PostgreSQL!
  * Improved documentation for connection pooled datasources (including adding a Component example); clarified the recommendations for globally overriding default options (write a wrapper namespace that suits your usage).
  * Note: this release is primarily to fix the cljdoc.org documentation via repackaging the JAR file.

* 2019-07-23 -- 1.0.3
  * Fix #48 by adding `next.jdbc.connection/->pool` and documenting how to use HikariCP and c3p0 in the Getting Started docs (as well as adding tests for both libraries).
  * Documentation improvements, including examples of extending `ReadableColumn` and `SettableParameter`.
  * Updated test dependencies (testing against more recent versions of several drivers).

* 2019-07-15 -- 1.0.2
  * Fix #47 by refactoring database specs to be a single hash map instead of pouring multiple maps into one.
  * Fix #46 by allowing `:host` to be `:none` which tells `next.jdbc` to omit the host/port section of the JDBC URL, so that local databases can be used with `:dbtype`/`:classname` for database types that `next.jdbc` does not know. Also added `:dbname-separator` and `:host-prefix` to the "db-spec" to allow fine-grained control over how the JDBC URL is assembled.
  * Fix #45 by adding [TimesTen](https://www.oracle.com/database/technologies/related/timesten.html) driver support.
  * Fix #44 so that `insert-multi!` with an empty `rows` vector returns `[]`.
  * Fix #43 by adjusting the spec for `insert-multi!` to "require less" of the `cols` and `rows` arguments.
  * Fix #42 by adding specs for `execute-batch!` and `set-parameters` in `next.jdbc.prepare`.
  * Fix #41 by improving docstrings and documentation, especially around prepared statement handling.
  * Fix #40 by adding `next.jdbc.prepare/execute-batch!`.
  * Added `assert`s in `next.jdbc.sql` as more informative errors for cases that would generate SQL exceptions (from malformed SQL).
  * Added spec for `:order-by` to reflect what is actually permitted.
  * Expose `next.jdbc.connect/dbtypes` as a table of known database types and aliases, along with their class name(s), port, and other JDBC string components.

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
