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

My latest round of changes exposed the mapped-function-over-rows API more prominently, but I'm still not happy with the "feel" of that aspect of the API yet (it creates a tension with the datafication behavior).

So, while I'm comfortable to put it out there and get feedback – and I've had lots of great feedback so far – expect to see more changes, possible some dramatic ones, in the next month or so before I actually settle on where the library will live and what the published artifacts will look like.

## License

Copyright © 2018-2019 Sean Corfield

Distributed under the Eclipse Public License version 1.0.
