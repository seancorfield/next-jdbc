# Prepared Statements

Under the hood, whenever you ask `next.jdbc` to execute some SQL (via `plan`, `execute!`, `execute-one!` or the "friendly" SQL functions) it calls `prepare` to create a `java.sql.PreparedStatement`, adds in the parameters you provide, and then calls `.execute` on it. Then it attempts to get a `ResultSet` from that and either return it or process it. If you asked for generated keys to be returned, that `ResultSet` will contain those generated keys if your database supports it, otherwise it will be whatever the `.execute` function produces. If no `ResultSet` is available at all, `next.jdbc` will ask for the count of updated rows and return that as if it were a result set.

> Note: Some databases do not support all SQL operations via `PreparedStatement`, in which case you may need to create a `java.sql.Statement` instead, via `next.jdbc.prepare/statement`, and pass that into `plan`, `execute!`, or `execute-one!`, along with the SQL you wish to execute. Note that such statement execution may not have parameters. See the [Prepared Statement Caveat in Getting Started](/doc/getting-started.md#prepared-statement-caveat) for an example.

If you have a SQL operation that you intend to run multiple times on the same `java.sql.Connection`, it may be worth creating the prepared statement yourself and reusing it. `next.jdbc/prepare` accepts a connection and a vector of SQL and optional parameters and returns a `java.sql.PreparedStatement` which can be passed to `plan`, `execute!`, or `execute-one!` as the first argument. It is your responsibility to close the prepared statement after it has been used.

If you need to pass an option map to `plan`, `execute!`, or `execute-one!` when passing a statement or prepared statement, you must pass `nil` or `[]` as the second argument:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (with-open [ps (jdbc/prepare con ["..." ...])]
    (jdbc/execute-one! ps nil {...})))
  (with-open [stmt (jdbc/statement con)]
    (jdbc/execute-one! stmt nil {...})))
```

You can provide the parameters in the `prepare` call or you can provide them via a call to `set-parameters` (discussed in more detail below).

```clojure
;; assuming require next.jdbc.prepare :as p
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["..."])]
  (jdbc/execute-one! (p/set-parameters ps [...])))
```

## Prepared Statement Parameters

If parameters are provided in the vector along with the SQL statement, in the call to `prepare`, then `set-parameter` is behind the scenes called for each of them. This is part of the `SettableParameter` protocol:

* `(set-parameter v ps i)` -- by default this calls `(.setObject ps i v)` (for `nil` and `Object`)

This can be extended to any Clojure data type, to provide a customized way to add specific types of values as parameters to any `PreparedStatement`. For example, to have all `java.time.Instant`, `java.time.LocalDate` and `java.time.LocalDateTime` objects converted to `java.sql.Timestamp` automatically:

```clojure
(extend-protocol p/SettableParameter
  java.time.Instant
  (set-parameter [^java.time.Instant v ^PreparedStatement ps ^long i]
    (.setTimestamp ps i (java.sql.Timestamp/from v)))
  java.time.LocalDate
  (set-parameter [^java.time.LocalDate v ^PreparedStatement ps ^long i]
    (.setTimestamp ps i (java.sql.Timestamp/valueOf (.atStartOfDay v))))
  java.time.LocalDateTime
  (set-parameter [^java.time.LocalDateTime v ^PreparedStatement ps ^long i]
    (.setTimestamp ps i (java.sql.Timestamp/valueOf v))))
```

> Note: those conversions can also be enabled by requiring the [`next.jdbc.date-time` namespace](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/api/next.jdbc.date-time).

You can also extend this protocol via metadata so you can do it on a per-object basis if you need:

```clojure
(with-meta obj {'next.jdbc.prepare/set-parameter (fn [v ps i]...)})
```

The `next.jdbc.types` namespace provides functions to wrap values with per-object implementations of `set-parameter` for every standard `java.sql.Types` value. Each is named `as-xxx` corresponding to `java.sql.Types/XXX`.

The converse, converting database-specific types to Clojure values is handled by the `ReadableColumn` protocol, discussed in the previous section ([Result Set Builders](/doc/result-set-builders.md#readablecolumn)).

As noted above, `next.jdbc.prepare/set-parameters` is available for you to call on any existing `PreparedStatement` to set or update the parameters that will be used when the statement is executed:

* `(set-parameters ps params)` -- loops over a sequence of parameter values and calls `set-parameter` for each one, as above.

If you need more specialized parameter handling than the protocol can provide, then you can create prepared statements explicitly, instead of letting `next.jdbc` do it for you, and then calling your own variant of `set-parameters` to install those parameters.

## Batched Parameters

By default, `next.jdbc` assumes that you are providing a single set of parameter values and then executing the prepared statement. If you want to run a single prepared statement with multiple groups of parameters, you might want to take advantage of the increased performance that may come from using JDBC's batching machinery.

You could do this manually:

```clojure
;; assuming require next.jdbc.prepare :as p
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["insert into status (id,name) values (?,?)"])]
  (p/set-parameters ps [1 "Approved"])
  (.addBatch ps)
  (p/set-parameters ps [2 "Rejected"])
  (.addBatch ps)
  (p/set-parameters ps [3 "New"])
  (.addBatch ps)
  (.executeBatch ps)) ; returns int[]
```

Here we set parameters and add them in batches to the prepared statement, then we execute the prepared statement in batch mode. You could also do the above like this, assuming you have those groups of parameters in a sequence:

```clojure
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["insert into status (id,name) values (?,?)"])]
  (run! #(.addBatch (p/set-parameters ps %))
        [[1 "Approved"] [2 "Rejected"] [3 "New"]])
  (.executeBatch ps)) ; returns int[]
```

Both of those are somewhat ugly and contain a fair bit of boilerplate and Java interop, so a helper function is provided in `next.jdbc.prepare` to automate the execution of batched parameters:

```clojure
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["insert into status (id,name) values (?,?)"])]
  (p/execute-batch! ps [[1 "Approved"] [2 "Rejected"] [3 "New"]]))
```

By default, this adds all the parameter groups and executes one batched command. It returns a (Clojure) vector of update counts (rather than `int[]`). If you provide an options hash map, you can specify a `:batch-size` and the parameter groups will be partitioned and executed as multiple batched commands. This is intended to allow very large sequences of parameter groups to be executed without running into limitations that may apply to a single batched command. If you expect the update counts to be very large (more than `Integer/MAX_VALUE`), you can specify `:large true` so that `.executeLargeBatch` is called instead of `.executeBatch`. Note: not all databases support `.executeLargeBatch`.

If you want to get the generated keys from an `insert` done via `execute-batch!`, you need a couple of extras, compared to the above:

```clojure
(with-open [con (jdbc/get-connection ds)
            ;; ensure the PreparedStatement will return the keys:
            ps  (jdbc/prepare con ["insert into status (id,name) values (?,?)"]
                              {:return-keys true})]
  ;; this returns update counts (which we'll ignore)
  (p/execute-batch! ps [[1 "Approved"] [2 "Rejected"] [3 "New"]])
  ;; this produces the generated keys as a (datafiable) Clojure data structure:
  (rs/datafiable-result-set (.getGeneratedKeys ps) con {}))
```

The call to `rs/datafiable-result-set` can be passed a `:builder-fn` option if you want something other than qualified as-is hash maps.

> Note: not all databases support calling `.getGeneratedKeys` here (everything I test against seems to, except MS SQL Server).

### Caveats

There are several caveats around using batched parameters. Some JDBC drivers need a "hint" in order to perform the batch operation as a single command for the database. In particular, PostgreSQL requires the `:reWriteBatchedInserts true` option and MySQL requires `:rewriteBatchedStatements true` (both non-standard JDBC options, of course!). These should be provided as part of the db-spec hash map when the datasource is created.

In addition, if the batch operation fails for a group of parameters, it is database-specific whether the remaining groups of parameters are used, i.e., whether the operation is performed for any further groups of parameters after the one that failed. The result of calling `execute-batch!` is a vector of integers. Each element of the vector is the number of rows affected by the operation for each group of parameters. `execute-batch!` may throw a `BatchUpdateException` and calling `.getUpdateCounts` (or `.getLargeUpdateCounts`) on the exception may return an array containing a mix of update counts and error values (a Java `int[]` or `long[]`). Some databases don't always return an update count but instead a value indicating the number of rows is not known (but sometimes you can still get the update counts).

Finally, some database drivers don't do batched operations at all -- they accept `.executeBatch` but they run the operation as separate commands for the database rather than a single batched command.

[<: Result Set Builders](/doc/result-set-builders.md) | [Transactions :>](/doc/transactions.md)
