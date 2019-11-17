# `next.jdbc` Middleware

The `next.jdbc.middleware` namespace provides functionality to allow you "wrap" a connectable, such as you'd pass to `plan`, `execute-one`, or `execute!` (or any of the friendly sql functions), so that you can either provide default options that will be used on those operations, or perform transformations on the SQL and parameters passed in, or perform transformations on the result set generated, or instrument those functions for timing or logging. Or anything else you can think of where you might want to extend or instrument `next.jdbc`'s SQL execution APIs.

## Middleware Overview

The `next.jdbc.middleware/wrapper` function accepts a connectable and an optional hash map of default options, and returns a new type of connectable that can be passed to any SQL execution function.

```clojure
(ns ,,,
  (:require [next.jdbc :as jdbc]
            [next.jdbc.middleware :as mw]
            [next.jdbc.result-set :as rs]))

(def db-spec {:dbtype "..." :dbname "..." ,,,})            

;; make a datasource (a connectable) from the db-spec
;; and wrap it up with a default result set builder function:
(def ds (mw/wrapper (jdbc/get-datasource db-spec)
                    {:builder-fn rs/as-lower-maps}))

(jdbc/execute! ds ["select * from fruit"])
;; that will behave as if it had that :builder-fn option provided
```

In addition to providing default options, the middleware wrapper also provides a number of "hooks" around SQL execution and result set building that you can tap into by providing any of the following options:

* `:pre-process-fn` -- `(fn [sql-params opts] ,,, [sql-params' opts'])` -- this function is called on the SQL & parameters and the options hash map, prior to executing the SQL, and can pre-process them, returning a vector pair of (possibly updated) SQL & parameters and options,
* `:post-process-fn` -- `(fn [rs opts] ,,, [rs' opts'])` -- this function is called on the `ResultSet` object and the options hash map, after executing the SQL, and can post-process them, returning a vector pair of (possibly updated) `ResultSet` object and options,
* `:row!-fn` -- `(fn [row opts] ,,, row')` -- this function is called on each row as it is realized (and also passed the options hash map) and can post-process the row, returning a  (possibly updated) row; it is named for the `row!` function in the result set builder that it wraps,
* `:rs!-fn` -- `(fn [sql-params opts] ,,, [sql-params' opts'])` -- this function is called on the result set once it is realized (and also passed the options hash map) and can post-process the result set, returning a (possibly updated) result set; it is named for the `rs!` function in the result set builder that it wraps.

Here's the data flow of middleware:

```clojure
;; assuming appropriate definitions for some-default and the fns A, B, C, and D:
(def default-opts {:an-option       some-default
                   ,,, ; more defaults for options
                   :pre-process-fn  A
                   :post-process-fn B
                   :row!-fn         C
                   :rs!-fn          D})
(def ds (mw/wrapper (jdbc/get-datasource db-spec) default-opts))

(jdbc/execute! ds ["select * from fruit where id < ?" 4] {:my-option some-value})
;; that is processed as follows:
;; 1. opts' <- (merge default-opts {:my-option some-value})
;; 2. pre-process the SQL, parameters, and options:
;;    [sql-params' opts''] <- (A ["select..." 4] opts')
;; 3. execute sql-params' with the opts'' hash map
;; 4. create the result set builder from the ResultSet rs and options opts''
;; 5. inside that builder, post-process the ResultSet and options:
;;    [rs' opts'''] <- (B rs opts'')
;; 6. post-process each row as row! is called:
;;    row' <- (C (row! builder row) opts''')
;; 7. add row' into the result set being built
;; 8. post-process the result set when rs! is called:
;;    rs' <- (D (rs! builder rs) opts''')
;; and the result is rs'
```

As you can see, both `:pre-process-fn` and `:post-process-fn` can return updated options that are passed along the processing pipeline so they can contain data that later stages in the pipeline can examine. This allows for timing data to passed through the pipeline for example.

Any of the hook functions may execute side-effects (such as logging) but must still return the expected data.

## Examples of Middleware Usage

The usage for providing default options should be clear from the overview above
and it is expected that `:builder-fn`, `:qualifier-fn`, `:label-fn`, `:table-fn`, and `:column-fn` will be the most common defaults you might want to provide. You can also provide any of the hook function options as defaults (or in specific calls if you only want them to run for those calls).

The next sections look at logging and timing examples.

### Logging with Middleware

Assuming you have some logging library available in your application, such as [tools.logging](https://github.com/clojure/tools.logging), and you've aliased that in as `logger`, we can do basic logging like this:

```clojure
(def ds (mw/wrapper (jdbc/get-datasource db-spec)
                    {:pre-process-fn
                     (fn [sql-p opts]
                       (logger/info "About to execute" sql-p)
                       [sql-p opts])
                     :rs!-fn
                     (fn [rs opts]
                       (logger/info "=>" (count rs) "rows")
                       rs)}))

(jdbc/execute! ds ["select * from fruit"])
;; should produce log output like:
;; ... About to execute ["select * from fruit"]
;; ... => 4 rows
```

You'll want to take care not to log sensitive data from your parameters and you could of course produce more detail in the result set logging (but consider how big your result set might be!).

### Timing with Middleware

Because the pre- and post-process hooks can modify the options hash map that is passed through the pipeline, you can use those to store timing data and then report on it at the end of each SQL operation:

```clojure
(def ds (mw/wrapper
         (jdbc/get-datasource db-spec)
         {:pre-process-fn
          (fn [sql-p opts]
            [sql-p (assoc opts ::start (System/currentTimeMillis))])
          :post-process-fn
          (fn [rs opts]
            [rs (assoc opts ::end (System/currentTimeMillis))])
          :rs!-fn
          (fn [rs opts]
            (let [build-time (- (System/currentTimeMillis)
                                (::end opts))]
              (logger/info "SQL took" (- (::end opts) (::start opts)) "ms,"
                           "build took" (- build-time (::end opts)) "ms,"
                           "for" (first (:next.jdbc/sql-params opts))))
            rs)}))

(jdbc/execute! ds ["select * from fruit"])
;; should produce log output like:
;; ... SQL took 10 ms, build took 2 ms, for select * from fruit
```

This takes advantage of the fact that `next.jdbc` adds the SQL & parameters vector into the options automcatically, under the key `:next.jdbc/sql-params`.

[<: All The Options](/doc/all-the-options.md) | [`datafy`, `nav`, and `:schema` :>](/doc/datafy-nav-and-schema.md)
