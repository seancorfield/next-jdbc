# RowBuilder and ResultSetBuilder

In [Getting Started](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started), it was noted that, by default, `execute!` and `execute-one!` return result sets as (vectors of) hash maps with namespace-qualified keys as-is. If your database naturally produces uppercase column names from the JDBC driver, that's what you'll get. If it produces mixed-case names, that's what you'll get.

The default builder for rows and result sets creates qualified keywords that match whatever case the JDBC driver produces. That builder is `next.jdbc.result-set/as-maps` but there are several options available:

* `as-maps` -- table-qualified keywords as-is, the default, e.g., `:ADDRESS/ID`, `:myTable/firstName`,
* `as-unqualified-maps` -- simple keywords as-is, e.g., `:ID`, `:firstName`,
* `as-lower-maps` -- table-qualified lower-case keywords, e.g., `:address/id`, `:mytable/firstname`,
* `as-unqualified-lower-maps` -- simple lower-case keywords as-is, e.g., `:id`, `:firstname`,
* `as-arrays` -- table-qualified keywords as-is (vector of column names, followed by vectors of row values),
* `as-unqualified-arrays` -- simple keywords as-is,
* `as-lower-arrays` -- table-qualified lower-case keywords,
* `as-unqualified-lower-arrays` -- simple lower-case keywords.

The reason behind the default is to a) be a simple transform, b) produce qualified keys in keeping with Clojure's direction (with `clojure.spec` etc), and c) not mess with the data. `as-arrays` is (slightly) faster than `as-maps` since it produces less data (vectors of values instead of vectors of hash maps), but the `lower` options will be slightly slower since they include (conditional) logic to convert strings to lower-case. The `unqualified` options may be slightly faster than their qualified equivalents but make no attempt to keep column names unique if your SQL joins across multiple tables.

## RowBuilder Protocol

This protocol defines four functions and is used whenever `next.jdbc` needs to materialize a row from a `ResultSet` as a Clojure data structure:

* `(->row builder)` -- produces a new row (a `(transient {})` by default),
* `(column-count builder)` -- returns the number of columns in each row,
* `(with-column builder row i)` -- given the row so far, fetches column `i` from the current row of the `ResultSet`, converts it to a Clojure value, and adds it to the row (for `as-maps` this is a call to `.getObject`, a call to `read-column-by-index` -- see the `ReadableColumn` protocol below, and a call to `assoc!`),
* `(row! builder row)` -- completes the row (a `(persistent! row)` call by default).

## ResultSet Protocol

This protocol defines three functions and is used whenever `next.jdbc` needs to materialize a result set (multiple rows) from a `ResultSet` as a Clojure data structure:

* `(->rs builder)` -- produces a new result set (a `(transient [])` by default),
* `(with-row builder rs row)` -- given the result set so far and a new row, returns the updated result set (a `(conj! rs row)` call by default),
* `(rs! builder rs)` -- completes the result set (a `(persistent! rs)` call by default).

## Result Set Builder Functions

The `as-*` functions described above are all implemented in terms of these protocols. They are passed the `ResultSet` object and the options hash map (as passed into various `next.jdbc` functions). They return an implementation of the protocols that is then used to build rows and the result set. Note that the `ResultSet` passed in is _mutable_ and is advanced from row to row by the SQL execution function, so each time `->row` is called, the underlying `ResultSet` object points at each new row in turn. By contrast, `->rs` (which is only called by `execute-all!`) is invoked _before_ the `ResultSet` is advanced to the first row.

The options hash map for any `next.jdbc` function can contain a `:gen-fn` key and the value is used at the row/result set builder function. The tests for `next.jdbc.result-set` include a [record-based builder function](https://github.com/seancorfield/next-jdbc/blob/master/test/next/jdbc/result_set_test.clj#L148-L164) as an example of how you can extend this to satisfy your needs.

# ReadableColumn

As mentioned above, when `with-column` is called, the expectation is that the row builder will call `.getObject` on the current state of the `ResultSet` object with the column index and will then call `read-column-by-index`, passing the column value, the `ResultSetMetaData`, and the column index. That function is part of the `ReadableColumn` protocol that you can extend to handle conversion of arbitrary database-specific types to Clojure values.

In addition, inside `reducible!`, as each value is looked up by name in the current state of the `ResultSet` object, the `read-column-by-label` function is called, again passing the column value and the column label (the name used in the SQL to identify that column). This function is also part of the `ReadableColumn` protocol.

The default implementation of this protocol is for these two functions to return `nil` as `nil`, a `Boolean` value as a canonical `true` or `false` value (unfortunately, JDBC drivers cannot be relied on to return unique values here!), and for all other objects to be returned as-is.

Common extensions here could include converting `java.sql.Timestamp` to `java.time.Instant` for example but `next.jdbc` makes no assumptions beyond `nil` and `Boolean`.

Note that the converse, converting Clojure values to database-specific types is handled by the `SettableParameters`, discussed in the next section (Prepared Statements).

[<: Friendly SQL Functions](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started/friendly-sql-functions) | [Prepared Statements :>](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started/prepared-statements)
