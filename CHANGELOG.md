# Change Log

Only accretive/fixative changes will be made from now on.

* 1.3.862 -- 2023-03-13
  * Fix [#243](https://github.com/seancorfield/next-jdbc/issues/243) by ensuring URI properties become keywords.
  * Fix [#242](https://github.com/seancorfield/next-jdbc/issues/242) by making the logging wrapper aware of the default options wrapper.

* 1.3.858 -- 2023-03-05
  * Address [#241](https://github.com/seancorfield/next-jdbc/issues/241) by correcting link to PostgreSQL docs.
  * Address [clj-kondo#1685](https://github.com/clj-kondo/clj-kondo/issues/1685) by using `.clj_kondo` extension for hook files.
  * Improve docs for SQLite users per [#239](https://github.com/seancorfield/next-jdbc/pull/239) -- [peristeri](https://github.com/peristeri).
  * Address [#236](https://github.com/seancorfield/next-jdbc/issues/236) by showing examples of `run!` over `plan`.

* 1.3.847 -- 2022-11-04
  * Fix [#232](https://github.com/seancorfield/next-jdbc/issues/232) by using `as-cols` in `insert-multi!` SQL builder. Thanks to @changsu-farmmorning for spotting that bug!
  * Fix [#229](https://github.com/seancorfield/next-jdbc/issues/229) by adding `next.jdbc.connect/uri->db-spec` which converts a URI string to a db-spec hash map; in addition, if `DriverManager/getConnection` fails, it assumes it was passed a URI instead of a JDBC URL, and retries after calling that function and then recreating the JDBC URL (which should have the effect of moving the embedded user/password credentials into the properties structure instead of the URL).
  * Address [#228](https://github.com/seancorfield/next-jdbc/issues/228) by adding `PreparedStatement` caveat to the Oracle **Tips & Tricks** section.
  * Address [#226](https://github.com/seancorfield/next-jdbc/issues/226) by adding a section on exception handling to **Tips & Tricks** (TL;DR: it's all horribly vendor-specific!).
  * Add `on-connection` to exported `clj-kondo` configuration.
  * Switch `run-test` from `sh` to `bb`.

* 1.3.834 -- 2022-09-23
  * Fix [#227](https://github.com/seancorfield/next-jdbc/issues/227) by correcting how [#221](https://github.com/seancorfield/next-jdbc/issues/221) was implemented.
  * Address [#224](https://github.com/seancorfield/next-jdbc/issues/224) by attempting to clarify how to use the snake/kebab options and builders.

* 1.3.828 -- 2022-09-11
  * Fix [#222](https://github.com/seancorfield/next-jdbc/issues/222) by correcting implementation of `.cons` on a row.
  * Address [#221](https://github.com/seancorfield/next-jdbc/issues/221) by supporting `:column-fn` a top-level option in `plan`-related functions to transform keys used in reducing function contexts. Also corrects handling of column names in schema `nav`igation (which previously only supported `:table-fn` and incorrectly applied it to columns as well).
  * Address [#218](https://github.com/seancorfield/next-jdbc/issues/218) by moving `:extend-via-metadata true` after the protocols' docstrings.
  * Document `:useBulkCopyForBatchInsert` for Microsoft SQL Server via PR [#216](https://github.com/seancorfield/next-jdbc/issues/216) -- [danskarda](https://github.com/danskarda).
  * Address [#215](https://github.com/seancorfield/next-jdbc/issues/215) by dropping official support for JDK 8 and updating various JDBC drivers in the testing matrix.
  * Address [#214](https://github.com/seancorfield/next-jdbc/issues/214) by updating test/CI versions.
  * Address [#212](https://github.com/seancorfield/next-jdbc/issues/212) by documenting the problem with SQLite's JDBC driver.
  * Fix [#211](https://github.com/seancorfield/next-jdbc/issues/211) by auto-creating `clojure_test` DB in MySQL if needed; also streamline the CI processes.
  * Fix [#210](https://github.com/seancorfield/next-jdbc/issues/210) by updating CI to test against MySQL and SQL Server.
  * Switch SQL Server testing setup to `docker-compose`.

* 1.2.796 -- 2022-08-01
  * Make `Transactable` extensible via metadata, via PR [#209](https://github.com/seancorfield/next-jdbc/issues/209) -- [@vemv](https://github.com/vemv).
  * Fix [#208](https://github.com/seancorfield/next-jdbc/issues/208) by treating unsupported exception as an empty string, just like the JDBC docs say should happen.

* 1.2.790 -- 2022-07-29
  * Address [#207](https://github.com/seancorfield/next-jdbc/issues/207) by supporting "db-spec" hash maps containing `:datasource` or `:connection-uri` (this is otherwise undocumented and intended to aid migration from `clojure.java.jdbc`).
  * Address [#199](https://github.com/seancorfield/next-jdbc/issues/199) by adding notes on UTC usage -- [@denismccarthykerry](https://github.com/denismccarthykerry).
  * Enhance `insert-multi!` to accept a sequence of hash maps and also to support batch execution, via PR [#206](https://github.com/seancorfield/next-jdbc/pull/206) -- [@rschmukler](https://github.com/rschmukler).
  * Fix HikariCP pooling example.

* 1.2.780 -- 2022-04-04
  * Address [#204](https://github.com/seancorfield/next-jdbc/issues/204) by adding `next.jdbc/on-connection`.
  * Address [#203](https://github.com/seancorfield/next-jdbc/issues/203) by adding a note to the **PostgreSQL Tips & Tricks** section.
  * Update `build-clj` to v0.8.0.

* 1.2.772 -- 2022-02-09
  * To support more tools that perform `datafy`/`nav`, make rows directly `nav`able (even though this is not really the correct behavior).
  * Address #193 by expanding the argument specs for `get-datasource` and `get-connection`.
  * Streamline `execute-batch!` for `with-options` and `with-logging` (and this should generalize to any wrapper that satisfies `Connectable` and stores the actual `Connection` under the `:connectable` key).
  * Update log4j2 test dependency.
  * Update `build-clj` to v0.6.7.

* 1.2.761 -- 2021-12-15
  * Fix #194 by throwing an exception if a table or column name used with the friendly SQL functions (or the SQL builder functions behind them) contains a "suspicious" character (currently, that's just `;`).
  * Update several test dependencies (incl. log4j2).
  * Update `build-clj` to v0.6.3.

* 1.2.753 -- 2021-11-17
  * Address #187 by adding `clj-kondo.exports` for future expansion (`with-transaction` is already built into `clj-kondo`).
  * Documentation updates; `pom.xml` template cleanup.
  * Update `build-clj` to v0.5.4.

* 1.2.737 -- 2021-10-17
  * Address #186 by updating `java.data` to 1.0.92 and documenting HikariCP's `:dataSourceProperties`.
  * Address #184 by improving documentation about `:jdbcUrl`.

* 1.2.731 -- 2021-10-04
  * Fix #181 by supporting option-wrapped connectables in `execute-batch!`.
  * Address #179 by improving documentation around connection pool initialization.
  * Update `build-clj` to v0.5.0.

* 1.2.724 -- 2021-09-25
  * Make `next.jdbc` compatible with GraalVM 22+ (PR #178, @FieryCod).
  * Address #177 by adding an important performance tip for Oracle.
  * Update most of the JDBC drivers for testing; make it easier to test MariaDB's driver;

* 1.2.709 -- 2021-08-30
  * Fix #174 by removing `:property-separator` from "etc" map and defaulting H2 to `";"` for this.
  * Switch to `tools.build` for running tests and JAR building etc.

* 1.2.689 -- 2021-08-01
  * Address #173 by extending `DatafiableRow` to `ResultSet` so there's a public method to call on (rows of) a JDBC result set directly.
  * Address #171 by clarifying that you cannot use `clojure.java.jdbc` functions inside `next.jdbc/with-transaction` and discuss how to migrate transaction-based code in the **Migration** guide.
  * Address #169 by expanding the description of `with-transaction` in **Getting Started**.
  * Cross-link to HoneySQL documentation for JSON/JSONB manipulation.
  * Remove superfluous prev/next links in docs (cljdoc does this automatically now).
  * Update `depstar`, `test-runner`, and CI versions. Add example `build.clj` to run tests in a subprocess (purely educational).

* 1.2.674 -- 2021-06-16
  * Fix #167 by adding `:property-separator` to `next.jdbc.connection/dbtypes` and using it in `jdbc-url`.
  * Address #166 by adding `next.jdbc/with-logging` to create a wrapped connectable that will invoke logging functions with the SQL/parameters and optionally the result or exception for each operation.
  * Fix `:unit_count` references in **Getting Started** (were `:unit_cost`).
  * Update `test-runner`.

* 1.2.659 -- 2021-05-05
  * Address #164 by making `clj-commons/camel-snake-kebab` an unconditional dependency. _[Being a conditional dependency that could be brought in at runtime caused problems with GraalVM-based native compilation as well as with multi-project monorepos]_
  * Add **Tips & Tricks** section about working with PostgreSQL "interval" types (via PR #163 from @snorremd).
  * Address #162 by adding GraalVM to the test matrix (thank you @DeLaGuardo).
  * Update several dependency versions.

* 1.1.646 -- 2021-03-15
  * Fix #161 by allowing `execute-batch!` to work with datasources and connections, and providing the SQL statement directly.

* 1.1.643 -- 2021-03-06
  * Change coordinates to `com.github.seancorfield/next.jdbc` (although new versions will continue to be deployed to `seancorfield/next.jdbc` for a while -- see the [Clojars Verified Group Names policy](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names)).
  * Documented `next.jdbc.transaction/*nested-tx*` more thoroughly since that difference from `clojure.java.jdbc` has come up in conversation a few times recently.
  * Fix #158 by documenting (and testing) `:allowMultiQueries true` as an option for MySQL/MariaDB to allow multiple statements to be executed and multiple result sets to be returned.
  * Fix #157 by copying `next.jdbc.prepare/execute-batch!` to `next.jdbc/execute-batch!` (to avoid a circular dependency that previously relied on requiring `next.jdbc.result-set` at runtime -- which was problematic for GraalVM-based native compilation); **`next.jdbc.prepare/execute-batch!` is deprecated:** it will continue to exist and work, but is no longer documented. In addition, `next.jdbc.prepare/execute-batch!` now relies on a private `volatile!` in order to reference `next.jdbc.result-set/datafiable-result-set` so that it is GraalVM-friendly. Note: code that requires `next.jdbc.prepare` and uses `execute-batch!` without also requiring something that causes `next.jdbc.result-set` to be loaded will no longer return generated keys from `execute-batch!` but that's an almost impossible path since nearly all code that uses `execute-batch!` will have called `next.jdbc/prepare` to get the `PreparedStatement` in the first place.

* 1.1.613 -- 2020-11-05
  * Fix #144 by ensuring `camel-snake-case` is properly required before use in an uberjar context.

* 1.1.610 -- 2020-10-19
  * Fix #140 by adding `"duckdb"` to `next.jdbc.connection/dbtypes`.
  * Change `next.jdbc.types/as-*` functions to use a thunk instead of a vector to convey metadata, so that wrapped values do not get unpacked by HoneySQL.
  * Refactor reducing and folding code around `ResultSet`, so that `reducible-result-set` and `foldable-result-set` can be exposed for folks who want more control over processing result sets obtained from database metadata.
  * `datafiable-result-set` can now be called without the `connectable` and/or `opts` arguments; a `nil` connectable now disables foreign key navigation in datafied results (rather than throwing an obscure exception).

* 1.1.588 -- 2020-09-09
  * Fix #139 by adding `next.jdbc.plan/select-one!` and `next.jdbc.plan/select!`.
  * If `ResultSet.getMetaData()` returns `null`, we assume the column count is zero, i.e., an empty result set. This should "never happen" but some JDBC drivers are badly behaved and their idea of an "empty result set" does not match the JDBC API spec.

* 1.1.582 -- 2020-08-05
  * Fix #138 by exposing `next.jdbc.connection/jdbc-url` to build `:jdbcUrl` values that can be passed to `->pool` or `component`.

* 1.1.581 -- 2020-08-03
  * Fix #137 by adding support for specifying username and password per-connection (if your datasource supports this).
  * Document SQLite handling of `bool` and `bit` columns in a new **Tips & Tricks** section, inspired by #134.
  * Address #133 by adding `:return-generated-keys` as an option on `execute-batch!`.

* 1.1.569 -- 2020-07-10
  * Fix #132 by adding specs for `next.jdbc/with-options` and `next.jdbc.prepare/statement`; correct spec for `next.jdbc.connection/component`. PR #131 from @Briaoeuidhtns.
  * Fix #130 by implementing `clojure.lang.ILookup` on the three builder adapters.
  * Fix #129 by adding `with-column-value` to `RowBuilder` and a more generic `builder-adapter`.
  * Fix #128 by adding a test for the "not found" arity of lookup on mapified result sets.
  * Fix #121 by conditionally adding `next.jdbc/snake-kebab-opts`, `next.jdbc/unqualified-snake-kebab-opts`, `next.jdbc.result-set/as-kebab-maps`, and `next.jdbc.result-set/as-unqualified-kebab-maps` (which are present only if `camel-snake-kebab` is on your classpath). _As of 1.2.659, these are included unconditionally and `next.jdbc` depends directly on `camel-snake-kebab`._
  * Correct MySQL batch statement rewrite tip: it's `:rewriteBatchedStatements true` (plural). Also surface the batch statement tips in the **Tips & Tricks** page.
  * Clarify how combining is interleaving with reducing in **Reducing and Folding with `plan`**.
  * Use "JDBC URL" consistently everywhere (instead of "JDBC URI" in several places).

* 1.1.547 -- 2020-06-29
  * Address #125 by making the result of `plan` foldable (in the `clojure.core.reducers` sense).
  * Address #124 by extending `next.jdbc.sql.builder/for-query` to support `:top` (SQL Server), `:limit` / `:offset` (MySQL/PostgreSQL), `:offset` / `:fetch` (SQL Standard) for `find-by-keys`.
  * Address #117 by adding `next.jdbc.transaction/*nested-tx*` to provide control over how attempts to create nested transactions should be handled.
  * Address #116 by adding a `:multi-rs` option to `execute!` to retrieve multiple result sets, for example from stored procedure calls or T-SQL scripts.
  * Allow `:all` to be passed into `find-by-keys` instead of an example hash map or a where clause vector so all rows will be returned (expected to be used with `:offset` etc to support simple pagination of an entire table).
  * Add `:columns` option to `find-by-keys` (and `get-by-id`) to specify a subset of columns to be returned in each row. This can also specify an alias for the column and allows for computed expressions to be selected with an alias.

* 1.0.478 -- 2020-06-24
  * Address #123 by adding `next.jdbc.types` namespace, full of auto-generated `as-xxx` functions, one for each of the `java.sql.Types` values.

* 1.0.476 -- 2020-06-22
  * Extend default options behavior to `next.jdbc.sql` functions.

* 1.0.475 -- 2020-06-22
  * Add tests for `"jtds"` database driver (against MS SQL Server), making it officially supported.
  * Switch from OpenTable Embedded PostgreSQL to Zonky's version, so that testing can move forward from PostgreSQL 10.11 to 12.2.0.
  * Fix potential reflection warnings caused by `next.jdbc.prepare/statement` being incorrectly type-hinted.
  * Address #122 by adding `next.jdbc/with-options` that lets you wrap up a connectable along with default options that should be applied to all operations on that connectable.
  * Address #119 by clarifying realization actions in the docstrings for `row-number`, `column-names`, and `metadata`.
  * Address #115 by adding equivalent of `db-do-commands` in the `clojure.java.jdbc` migration guide.
  * Add log4j2 as a test dependency so that I have better control over logging (which makes debugging easier!).

* 1.0.462 -- 2020-05-31
  * Addition of `next.jdbc.datafy` to provide more `datafy`/`nav` introspection (see the additional section in **datafy, nav, and :schema** for details).
  * Addition of `next.jdbc.result-set/metadata` to provide (datafied) result set metadata within `plan`.

* 1.0.445 -- 2020-05-23
  * Enhanced support in `plan` for "metadata" access: `row-number` and `column-names` can be called on the abstract row (even after calling `datafiable-row`). In addition, `Associative` access via numeric "keys" will read columns by index, and row abstractions now support `Indexed` access via `nth` (which will also read columns by index). Fixes #110.
  * Support for Stuart Sierra's Component library, via `next.jdbc.connection/component`. See updated **Getting Started** guide for usage.
  * Add example of getting generated keys from `execute-batch!`.
  * Add MySQL-specific result set streaming tip.
  * Add array handling example to PostgreSQL **Tips & Tricks**. PR #108 from @maxp.
  * Investigate possible solutions for #106 (mutable transaction thread safety) -- experimental `locking` on `Connection` object.

* 1.0.424 -- 2020-04-10
  * In **Tips & Tricks**, noted that MySQL returns `BLOB` columns as `byte[]` instead of `java.sql.Blob`.
  * Address #103, #104 by adding a section on timeouts to **Tips & Tricks**.
  * Fix #102 by allowing keywords or strings in `:return-keys`.
  * Fix #101 by tightening the spec on a JDBC URL to correctly reflect that it must start with `jdbc:`.
  * Add support for calling `.getLoginTimeout`/`.setLoginTimeout` on the reified `DataSource` returned by `get-datasource` when called on a hash map "db-spec" or JDBC URL string.
  * Documentation improvements based on feedback (mostly from Slack), including a section on database metadata near the end of **Getting Started**.

* 1.0.409 -- 2020-03-16
  * Address #100 by adding support for MariaDB (@green-coder). Set `NEXT_JDBC_TEST_MARIADB=true` as well as `NEXT_JDBC_TEST_MYSQL=true` in order to run tests against MariaDB.

* 1.0.405 -- 2020-03-14 (no code changes -- just documentation)
  * Improve documentation around `plan` so `reduce` etc is more obvious.
  * Attempt to drive readers to cljdoc.org instead of the GitHub version (which is harder to navigate).

* 1.0.395 -- 2020-03-02
  * Add `read-as-instant` and `read-as-local` functions to `next.jdbc.date-time` to extend `ReadableColumn` so that SQL `DATE` and `TIMESTAMP` columns can be read as Java Time types.
  * Specifically call out PostgreSQL as needing `next.jdbc.date-time` to enable automatic conversion of `java.util.Date` objects to SQL timestamps for prepared statements (#95).
  * Split **Tips & Tricks** into its own page, with a whole new section on using JSON data types with PostgreSQL (#94 -- thank you @vharmain).
  * Bump dependencies to latest.

* 1.0.384 -- 2020-02-28
  * Add PostgreSQL streaming option information to **Tips & Tricks** (#87).
  * Minor documentation fixes (including #85, #92, #93).
  * Improve `Unknown dbtype` exception message (to clarify that `:classname` is also missing, #90).
  * Fix #88 by using 1-arity `keyword` call when table name unavailable (or `:qualifier-fn` returns `nil` or an empty string); also allows `:qualifier-fn` function to be called on empty table name (so `:qualifier-fn (constantly "qual")` will now work much like `clojure.java.jdbc`'s `:qualifier "qual"` worked).
  * Address #89, #91 by making minor performance tweaks to `next.jdbc.result-set` functions.
  * Planning to move to MAJOR.MINOR.COMMITS versioning scheme (1.0.384).

* 1.0.13 -- 2019-12-20
  * Fix #82 by adding `clojure.java.data`-based support for setting arbitrary properties on `Connection` and `PreparedStatement` objects, post-creation. Note: this uses the Java reflection API under the hood.
  * Adds `next.jdbc.prepare/statement` to create `Statement` objects with all the options available to `prepare` except `:return-keys`.
  * Update `org.clojure/java.data` to 0.1.5 (for property setting).
  * Additional clarifications in the documentation based on feedback on Slack.

* 1.0.12 -- 2019-12-11
  * Address #81 by splitting the SQL-building functions out of `next.jdbc.sql` into `next.jdbc.sql.builder`.
  * Fix #80 by avoiding the auto-commit restore after a failed rollback in a failed transaction.
  * Address #78 by documenting the `:connectionInitSql` workaround for HikariCP/PostgreSQL and non-default schemas.

* 1.0.11 -- 2019-12-07
  * Fix #76 by avoiding conversions on `java.sql.Date` and `java.sql.Timestamp`.
  * Add testing against Microsoft SQL Server (run tests with environment variables `NEXT_JDBC_TEST_MSSQL=yes` and `MSSQL_SA_PASSWORD` set to your local -- `127.0.0.1:1433` -- SQL Server `sa` user password; assumes that it can create and drop `fruit` and `fruit_time` tables in the `model` database).
  * Add testing against MySQL (run tests with environment variables `NEXT_JDBC_TEST_MYSQL=yes` and `MYSQL_ROOT_PASSWORD` set to your local -- `127.0.0.1:3306` -- MySQL `root` user password; assumes you have already created an empty database called `clojure_test`).
  * Bump several JDBC driver versions for up-to-date testing.
  * Minor documentation fixes.

* 1.0.10 -- 2019-11-14
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

* 1.0.9 -- 2019-10-11
  * Address #69 by trying to clarify when to use `execute-one!` vs `execute!` vs `plan`.
  * Address #68 by clarifying that builder functions do not affect the "fake result set" containing `:next.jdbc/update-count`.
  * Fix #67 by adding `:jdbcUrl` version spec.
  * Add `next.jdbc.optional/as-maps-adapter` to provide a way to override the default result set reading behavior of using `.getObject` when omitting SQL `NULL` values from result set maps.

* 1.0.8 -- 2019-09-27
  * Fix #66 by adding support for a db-spec hash map format containing a `:jdbcUrl` key (consistent with `->pool`) so that you can create a datasource from a JDBC URL string and additional options.
  * Address #65 by adding a HugSQL "quick start" to the **Friendly SQL Functions** section of the docs.
  * Add `next.jdbc.specs/unstrument`. PR #64 (@gerred).
  * Address #63 by improving documentation around qualified column names and `:qualifier` (`clojure.java.jdbc`) migration, with a specific caveat about Oracle not fully supporting `.getTableName()`.

* 1.0.7 -- 2019-09-09
  * Address #60 by supporting simpler schema entry formats: `:table/column` is equivalent to the old `[:table :column :one]` and `[:table/column]` is equivalent to the old `[:table :column :many]`. The older formats will continue to be supported but should be considered deprecated. PR #62 (@seancorfield).
  * Added test for using `ANY(?)` and arrays in PostgreSQL for `IN (?,,,?)` style queries. Added a **Tips & Tricks** section to **Friendly SQL Functions** with database-specific suggestions, that starts with this one.
  * Improved documentation in several areas.

* 1.0.6 -- 2019-08-24
  * Improved documentation around `insert-multi!` and `execute-batch!` (addresses #57).
  * Fix #54 by improving documentation around data type conversions (and the `ReadableColumn` and `SettableParameter` protocols).
  * Fix #52 by using a US-locale function in the "lower" result set builders to avoid unexpected character changes in column names in locales such as Turkish. If you want the locale-sensitive behavior, pass `clojure.string/lower-case` into one of the "modified" result set builders.
  * Add `next.jdbc.result-set/as-maps-adapter` and `next.jdbc.result-set/as-arrays-adapter` to provide a way to override the default result set reading behavior of using `.getObject`.
  * Update `org.clojure/test.check` to `"0.10.0"`.

* 1.0.5 -- 2019-08-05
  * Fix #51 by implementing `IPersistentMap` fully for the "mapified" result set inside `plan`. This adds support for `dissoc` and `cons` (which will both realize a row), `count` (which returns the column count but does not realize a row), `empty` (returns an empty hash map without realizing a row), etc.
  * Improved documentation around connection pooling (HikariCP caveats).

* 1.0.4 -- 2019-07-24
  * Fix #50 by adding machinery to test against (embedded) PostgreSQL!
  * Improved documentation for connection pooled datasources (including adding a Component example); clarified the recommendations for globally overriding default options (write a wrapper namespace that suits your usage).
  * Note: this release is primarily to fix the cljdoc.org documentation via repackaging the JAR file.

* 1.0.3 -- 2019-07-23
  * Fix #48 by adding `next.jdbc.connection/->pool` and documenting how to use HikariCP and c3p0 in the Getting Started docs (as well as adding tests for both libraries).
  * Documentation improvements, including examples of extending `ReadableColumn` and `SettableParameter`.
  * Updated test dependencies (testing against more recent versions of several drivers).

* 1.0.2 -- 2019-07-15
  * Fix #47 by refactoring database specs to be a single hash map instead of pouring multiple maps into one.
  * Fix #46 by allowing `:host` to be `:none` which tells `next.jdbc` to omit the host/port section of the JDBC URL, so that local databases can be used with `:dbtype`/`:classname` for database types that `next.jdbc` does not know. Also added `:dbname-separator` and `:host-prefix` to the "db-spec" to allow fine-grained control over how the JDBC URL is assembled.
  * Fix #45 by adding [TimesTen](https://www.oracle.com/database/technologies/related/timesten.html) driver support.
  * Fix #44 so that `insert-multi!` with an empty `rows` vector returns `[]`.
  * Fix #43 by adjusting the spec for `insert-multi!` to "require less" of the `cols` and `rows` arguments.
  * Fix #42 by adding specs for `execute-batch!` and `set-parameters` in `next.jdbc.prepare`.
  * Fix #41 by improving docstrings and documentation, especially around prepared statement handling.
  * Fix #40 by adding `next.jdbc/execute-batch!` (previously `next.jdbc.prepare/execute-batch!`).
  * Added `assert`s in `next.jdbc.sql` as more informative errors for cases that would generate SQL exceptions (from malformed SQL).
  * Added spec for `:order-by` to reflect what is actually permitted.
  * Expose `next.jdbc.connect/dbtypes` as a table of known database types and aliases, along with their class name(s), port, and other JDBC string components.

* 1.0.1 -- 2019-07-03
  * Fix #37 by adjusting the spec for `with-transaction` to "require less" of the `:binding` vector.
  * Fix #36 by adding type hint in `with-transaction` macro.
  * Fix #35 by explaining the database-specific options needed to ensure `insert-multi!` performs a single, batched operation.
  * Fix #34 by explaining save points (in the Transactions documentation).
  * Fix #33 by updating the spec for the example `key-map` in `find-by-keys`, `update!`, and `delete!` to reflect that you cannot pass an empty map to these functions (and added tests to ensure the calls fail with spec errors).

* 1.0.0 "gold" -- 2019-06-12
  * Address #31 by making `reify`'d objects produce a more informative string representation if they are printed (e.g., misusing `plan` by not reducing it or not mapping an operation over the rows).
  * Fix #26 by exposing `next.jdbc.result-set/datafiable-result-set` so that various `java.sql.DatabaseMetaData` methods that return result metadata information in `ResultSet`s can be easily turned into a fully realized result set.

* 1.0.0-rc1 -- 2019-06-04
  * Fix #24 by adding return type hints to `next.jdbc` functions.
  * Fix #22 by adding `next.jdbc.optional` with six map builders that omit `NULL` columns from the row hash maps.
  * Documentation improvements (#27, #28, and #29), including changing "connectable" to "transactable" for the `transact` function and the `with-transaction` macro (for consistency with the name of the underlying protocol).
  * Fix #30 by adding `modified` variants of column name functions and builders. The `lower` variants have been rewritten in terms of these new `modified` variants. This adds `:label-fn` and `:qualifier-fn` options that mirror `:column-fn` and `:table-fn` for row builders.

* 1.0.0-beta1 -- 2019-05-24
  * Set up CircleCI testing (just local DBs for now).
  * Address #21 by adding `next.jdbc.specs` and documenting basic usage.
  * Fix #19 by caching loaded database driver classes.
  * Address #16 by renaming `reducible!` to `plan` (**BREAKING CHANGE!**).
  * Address #3 by deciding to maintain this library outside Clojure Contrib.

## Alpha Builds

* 1.0.0-alpha13 -- 2019-05-04 -- Fix #18 by removing more keys from properties when creating connections.
* 1.0.0-alpha12 -- 2019-04-26 -- Fix #17 by renaming `:next.jdbc/sql-string` to `:next.jdbc/sql-params` (**BREAKING CHANGE!**) and pass whole vector.
* 1.0.0-alpha11 -- 2019-04-24 -- Rename `:gen-fn` to `:builder-fn` (**BREAKING CHANGE!**); Fix #13 by adding documentation for `datafy`/`nav`/`:schema`; Fix #15 by automatically adding `:next.jdbc/sql-string` (as of 1.0.0-alpha12: `:next.jdbc/sql-params`) into the options hash map, so custom builders can depend on the SQL string.
* 1.0.0-alpha9 -- 2019-04-22 -- Fix #14 by respecting `:gen-fn` (as of 1.0.0-alpha11: `:builder-fn`) in `execute-one!` for `PreparedStatement`.
* 1.0.0-alpha8 -- 2019-04-21 -- Initial publicly announced release.
