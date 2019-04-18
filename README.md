# next.jdbc

The next generation of `clojure.java.jdbc`: a new low-level Clojure wrapper for JDBC-based access to databases.

## Motivation

Why another JDBC library? Why a different API from `clojure.java.jdbc`?

* Performance: there's a surprising amount of overhead in how `ResultSet` objects are converted to sequences of hash maps – which can be really noticeable for large result sets – so I want a better way to handle that. There's also quite a bit of overhead and complexity in all the conditional logic and parsing that is associated with `db-spec`-as-hash-map.
* A more modern API, based on using qualified keywords and transducers etc: `:qualifier` and `reducible-query` in recent `clojure.java.jdbc` versions were steps toward that but there's a lot of "legacy" API in the library and I want to present a more focused, more streamlined API so folks naturally use the `IReduceInit` / transducer approach from day one and benefit from qualified keywords. I'm still contemplating whether there are reasonable ways to integrate with `clojure.spec` (for example, if you have specs of your data model, could `next.jdbc` leverage that somehow?).
* Simplicity: `clojure.java.jdbc` uses a variety of ways to execute SQL which can lead to inconsistencies and surprises – `query`, `execute!`, and `db-do-commands` are all different ways to execute different types of SQL statement so you have to remember which is which and you often have to watch out for restrictions in the underlying JDBC API.

Those are my three primary drivers. In addition, the `db-spec`-as-hash-map approach in `clojure.java.jdbc` has caused a lot of frustration and confusion in the past, especially with the wide range of conflicting options that are supported. `next.jdbc` is heavily protocol-based so it's easier to mix'n'match how you use it with direct Java JDBC code (and the protocol-based approach contributes to the improved performance overall). There's a much clearer path of `db-spec` -> `DataSource` -> `Connection` now, which should steer people toward more connection reuse and better performing apps.

I also wanted `datafy`/`nav` support baked right in (it was added to `clojure.java.jdbc` back in December 2018 as an undocumented, experimental API in a separate namespace). I wanted it to be "free" in terms of performance (it isn't quite – my next round of changes should address that).

The API so far is still very much a work-in-progress. I'm still very conflicted about the "syntactic sugar" SQL functions (`insert!`, `query`, `update!`, and `delete!`). They go beyond what I really want to include in the API, but I know that their equivalents in `clojure.java.jdbc` are heavily used (based on the number of questions and JIRA issues I get).

So, while I'm comfortable to put it out there and get feedback – and I've had lots of great feedback so far – expect to see more changes, possible some dramatic ones, in the next month or so before I actually settle on where the library will live and what the published artifacts will look like.

## Usage

The primary concepts behind `next.jdbc` are that you start by producing a `javax.sql.DataSource`. You can create a pooled datasource object using your preferred library (c3p0, hikari-cp, etc). You can use `next.jdbc`'s `get-datasource` function to create a `DataSource` from a `db-spec` hash map or from a JDBC URL (string). The underlying protocol, `Sourceable`, can be extended to allow more things to be turned into a `DataSource` (and can be extended via metadata on an object as well as via types).

From a `DataSource`, either you or `next.jdbc` can create a `java.sql.Connection` via the `get-connection` function. You can specify an options hash map to `get-connection` to modify the connection that is created: `:read-only`, `:auto-commit`.

The primary SQL execution API in `next.jdbc` is:
* `reducible!` -- yields an `IReduceInit` that, when reduced, executes the SQL statement and then reduces over the `ResultSet` with as little overhead as possible.
* `execute!` -- executes the SQL statement and produces a vector of realized hash maps, that use qualified keywords for the column names, of the form `:<table>/<column>`. If you join across multiple tables, the qualified keywords will reflect the originating tables for each of the columns. If the SQL produces named values that do not come from an associated table, a simple, unqualified keyword will be used. The realized hash maps returned by `execute!` are `Datafiable` and thus `Navigable` (see Clojure 1.10's `datafy` and `nav` functions, and tools like Cognitect's REBL). Alternatively, you can specify `{:gen-fn rs/as-arrays}` and produce a vector with column names followed by vectors of row values.
* `execute-one!` -- executes the SQL statement and produces a single realized hash map. The realized hash map returned by `execute-one!` is `Datafiable` and thus `Navigable`.

In addition, there are API functions to create `PreparedStatement`s (`prepare`) from `Connection`s, which can be passed to `reducible!`, `execute!`, or `execute-one!`, and to run code inside a transaction (the `transact` function and the `with-transaction` macro).

Since `next.jdbc` uses raw Java JDBC types, you can use `with-open` directly to reuse connections and ensure they are cleaned up correctly:

```
  (let [my-datasource (get-datasource {:dbtype "..." :dbname "..." ...})]
    (with-open [connection (get-connection my-datasource)]
      (execute! connection [...])
      (reduce my-fn init-value (reducible! connection [...]))
      (execute! connection [...])
```

### Usage scenarios

There are three intended usage scenarios that may drive the API to change:
* Execute a SQL statement to obtain a single, fully-realized, `Datafiable` hash map that represents either the first row from a `ResultSet`, the first generated keys result (again, from a `ResultSet`), or the first result where neither of those are available (`next.jdbc` will yield `{:next.jdbc/update-count N}`) when it can only return an update count). This usage is currently supported by `execute-one!`.
* Execute a SQL statement to obtain a fully-realized, `Datafiable` result set -- a vector of hash maps. This usage is supported by `execute!`. You can also produce a vector of column names/row values (`next.jdbc.result-set/as-arrays`).
* Execute a SQL statement and process it in a single eager operation, which may allow for the results to be streamed from the database (how to persuade JDBC to do that is database-specific!), and which cleans up resources before returning the result -- even if the reduction is short-circuited via `reduced`. This usage is supported by `reducible!`.

In addition, convenience functions -- "syntactic sugar" -- are provided to insert rows, run queries, update rows, and delete rows, using the same names as in `clojure.java.jdbc`. These are currently in `next.jdbc` but may move to `next.jdbc.sql` since they involve SQL creation, or they may move into a separate "sibling" library -- since they are not part of the intended core API.

## Differences from `clojure.java.jdbc`

In addition to the obvious differences outlined above, there are a number of other smaller differences outlined below and also listed in https://github.com/seancorfield/next-jdbc/issues/5

### The `db-spec` hash map

Whereas `clojure.java.jdbc` supports a wide variety of options to describe how to make a database connection, `next.jdbc` streamlines this to just the `:dbtype`/`:dbname` approach which has been the recommended way to write `db-spec`s for some time in `clojure.java.jdbc`. See the docstring for `get-connection` for the full list of databases supported and options available.

### The `:result-set-fn` and `:row-fn` options

`:result-set-fn` is not supported: either call your function on the result of `execute!` or handle it via reducing the result of `reducible!`.

`:row-fn` is not supported; either `map` your function over the result of `execute!` or handle it via reducing the result of `reducible!`.

### Clojure identifier creation

While the `:identifiers` option is still (currently) supported for operations that produce realized row hash maps, it defaults to `identity` rather than `clojure.java.jdbc`'s `clojure.string/lower-case`, and it is called separately for the table name string and the column name string in the qualified keys of those maps.

### SQL entity creation

The `:entities` option is still (currently) supported for operations that create SQL strings from Clojure data structures. As with `clojure.java.jdbc`, it is applied to the string that will be used for the SQL entity (after converting incoming keywords to strings), and the various "quoting" strategies used in specific databases are now functions in the `next.jdbc.quoted` namespace: `ansi`, `mysql`, `postgres` (alias for `ansi`), `oracle` (also an alias for `ansi`), and `sql-server`.

## License

Copyright © 2018-2019 Sean Corfield

Distributed under the Eclipse Public License version 1.0.
