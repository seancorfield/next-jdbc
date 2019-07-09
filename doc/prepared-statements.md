# Prepared Statements

Under the hood, whenever you ask `next.jdbc` to execute some SQL it creates a `java.sql.PreparedStatement`, adds in the parameters you provide, and then calls `.execute` on it. Then it attempts to get a `ResultSet` from that and either return it or process it. If you asked for generated keys to be returned, that `ResultSet` will contain those generated keys if your database supports it, otherwise it will be whatever the `.execute` function produces. If no `ResultSet` is available at all, `next.jdbc` will ask for the count of updated rows and return that as if it were a result set.

If you have a SQL operation that you intend to run multiple times on the same `java.sql.Connection`, it may be worth creating the prepared statement yourself and reusing it. `next.jdbc/prepare` accepts a connection and a vector of SQL and optional parameters and returns a `java.sql.PreparedStatement` which can be passed to `plan`, `execute!`, or `execute-one!` as the first argument. It is your responsibility to close the prepared statement after it has been used.

If you need to pass an option map to `plan`, `execute!`, or `execute-one!` when passing a prepared statement, you must pass `nil` or `[]` as the second argument:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (with-open [ps (jdbc/prepare con ["..." ...])]
    (jdbc/execute-one! ps nil {...})))
```

You can provide the parameters in the `prepare` call or you can provide them via a call to `set-parameters` (discussed in more detail below).

```clojure
;; assuming require next.jdbc.prepare :as p
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["..."])]
  (jdbc/execute-one! (p/set-parameters ps [...])))
```

## Prepared Statement Parameters

If parameters are provided in the vector along with the SQL statement, in the call to `prepare`, then `set-parameter` is called for each of them. This is part of the `SettableParameter` protocol:

* `(set-parameter v ps i)` -- by default this calls `(.setObject ps i v)` (for `nil` and `Object`)

This can be extended to any Clojure data type, to provide a customized way to add specific types of values as parameters to any `PreparedStatement`. Note that you can extend this protocol via metadata so you can do it on a per-object basis if you need:

```clojure
(with-meta obj {'next.jdbc.prepare/set-parameter (fn [v ps i]...)})
```

As noted above, `next.jdbc.prepare/set-parameters` is available for you to call on any existing `PreparedStatement` to set or update the parameters that will be used when the statement is executed:

* `(set-parameters ps params)` -- loops over a sequence of parameter values and calls `set-parameter` for each one, as above.

If you need more specialized parameter handling than the protocol can provide, then you can create prepared statements explicitly, instead of letting `next.jdbc` do it for you, and then calling your own variant of `set-parameters` to install those parameters.

## Batched Parameters

By default, `next.jdbc` assumes that you are providing a single set of parameter values and then executing the prepared statement. If you want to run a single prepared statement with multiple sets of parameters, you might want to take advantage of the increased performance that may come from using JDBC's batching machinery.

```clojure
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["insert into status (id,name) values (?,?)"])]
  (p/set-parameters ps [1 "Approved"])
  (.addBatch ps)
  (p/set-parameters ps [2 "Rejected"])
  (.addBatch ps)
  (p/set-parameters ps [3 "New"])
  (.addBatch ps)
  (.executeBatch ps))
```

Here we set parameters and add them in batches to the prepared statement, then we execute the prepared statement in batch mode. You could also do the above like this, assuming you have those sets of parameters in a sequence:

```clojure
(with-open [con (jdbc/get-connection ds)
            ps  (jdbc/prepare con ["insert into status (id,name) values (?,?)"])]
  (run! #(.addBatch (p/set-parameters ps %))
        [[1 "Approved"] [2 "Rejected"] [3 "New"]])
  (.executeBatch ps))
```

There are several caveats around using batched parameters. Some JDBC drivers need a "hint" in order to perform the batch operation as a single command for the database. In particular, PostgreSQL requires the `:reWriteBatchedInserts true` option and MySQL requires `:rewriteBatchedStatement true` (both non-standard JDBC options, of course!).

In addition, if the batch operation fails for one of the sets of parameters, it is database-specific whether the remaining sets of parameters are used, i.e., whether the operation is performed for any further sets of parameters after the one that failed. The result of calling `.executeBatch` is an array of integers (specifically a Java array `int[]`). Each element of the array is the number of rows affected by the operation for each set of parameters. `.executeBatch` may throw a `BatchUpdateException` and calling `.getUpdatedCounts` on the exception may return an array containing a mix of update counts and error values. Some databases don't always return an update count but instead a value indicating the number of rows is not known (but sometimes you can still get the update counts).

Finally, some database drivers don't do batched operations at all -- they accept `.executeBatch` but they run the operation as separate commands for the database rather than a single batched command.

[<: Result Set Builders](/doc/result-set-builders.md) | [Transactions :>](/doc/transactions.md)
