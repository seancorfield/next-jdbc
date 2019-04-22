# Prepared Statements

Under the hood, whenever you ask `next.jdbc` to execute some SQL it creates a `java.sql.PreparedStatement`, adds in the parameters you provide, and then calls `.execute` on it. Then it attempts to get a `ResultSet` from that and either return it or process it. If you asked for generated keys to be returned, that `ResultSet` will contain the those generated keys if your database supports it, otherwise it will be whatever the `.execute` function produces. If no `ResultSet` is available at all, `next.jdbc` will ask for the count of updated rows and return that as if it were a result set.

If you have a SQL operation that you intend to run multiple times on the same `java.sql.Connection`, it may be worth creating the prepared statement yourself and reusing it. `next.jdbc/prepare` accepts a connection and a vector of SQL and optional parameters and returns a `java.sql.PreparedStatement` which can be passed to `reducible!`, `execute!`, or `execute-one!` as the first argument. It is your responsibility to close the prepared statement after it has been used.

If you need to pass an option map to `reducible!`, `execute!`, or `execute-one!` when passing a prepared statement, you must pass `nil` or `[]` as the second argument:

```clojure
(with-open [con (jdbc/get-connection ds)]
  (with-open [ps (jdbc/prepare con ["..." ...])]
    (execute-one! ps nil {...})))
```

## Prepared Statement Parameters

If parameters are provided in the vector along with the SQL statement, in the call to `prepare`, then `set-parameter` is called for each of them. This is part of the `SettableParameter` protocol:

* `(set-parameter v ps i)` -- by default this calls `(.setObject ps i v)` (for `nil` and `Object`)

This can be extended to any Clojure data type, to provide a customized way to add specific types of values as parameters to any `PreparedStatement`. Note that you can extend this protocol via metadata so you can do it on a per-object basis if you need:

```clojure
(with-meta obj {'next.jdbc.prepare/set-parameter (fn [v ps i]...)})
```

`next.jdbc/set-parameters` is available for you to call on any existing `PreparedStatement` to set or update the parameters that will be used when the statement is executed:

* `(set-parameters ps params)` -- loops over a sequence of parameter values and calls `set-parameter` for each one, as above.

If you need more specialized parameter handling than the protocol can provide, then you can create prepared statements explicitly, instead of letting `next.jdbc` do it for you, and then calling your own variant of `set-parameters` to install those parameters.

[<: Result Set Builders](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started/result-set-builders) | [Transactions :>](https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started/transactions)
