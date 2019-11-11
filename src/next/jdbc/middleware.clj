;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.middleware
  "This is just an experimental sketch of what it might look like to be
  able to provide middleware that can wrap SQL execution in a way that
  behavior can be extended in interesting ways, to support logging, timing.
  and other cross-cutting things.

  Since it's just an experiment, there's no guarantee that this -- or
  anything like it -- will actually end up in a next.jdbc release. You've
  been warned!

  So far these execution points can be hooked into:
  * start -- pre-process the SQL & parameters and options
  * (execute SQL)
  * ????? -- process the options (and something else?)
  * row   -- post-process each row and options
  * rs    -- post-process the whole result set and options

  For the rows and result set, it's 'obvious' that the functions should
  take the values and return them (or updated versions). For the start
  function with SQL & parameters, it also makes sense to take and return
  that vector.

  For timing middleware, you'd need to pass data through the call chain
  somehow -- unless you control the whole middleware and this isn't sufficient
  for that yet. Hence the decision to allow processing of the options and
  passing data through those -- which leads to a rather odd call chain:
  start can return the vector or a map of updated options (with a payload),
  and the ????? point can process the options again (e.g., to update timing
  data etc). And that's all kind of horrible."
  (:require [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]))

(defn post-processing-adapter
  "Given a builder function (e.g., `as-lower-maps`), return a new builder
  function that post-processes rows and the result set. The options may
  contain post-processing functions that are called on each row and on the
  the result set. The options map is provided as a second parameter to these
  functions, which should include `:next.jdbc/sql-params` (the vector of SQL
  and parameters, in case post-processing needs it):

  * `:execute-fn` -- called immediately after the SQL operation completes
      ^ This is a horrible name and it needs to return the options which
        is weird so I don't like this approach overall...
  * `:row!-fn` -- called on each row as it is fully-realized
  * `:rs!-fn` -- called on the whole result set once it is fully-realized

  The results of these functions are returned as the rows/result set."
  [builder-fn]
  (fn [rs opts]
    (let [id2     (fn [x _] x)
          id2'    (fn [_ x] x)
          exec-fn (get opts :execute-fn id2')
          opts    (exec-fn rs opts)
          mrsb    (builder-fn rs opts)
          row!-fn (get opts :row!-fn id2)
          rs!-fn  (get opts :rs!-fn id2)]
      (reify
        rs/RowBuilder
        (->row [this] (rs/->row mrsb))
        (column-count [this] (rs/column-count mrsb))
        (with-column [this row i] (rs/with-column mrsb row i))
        (row! [this row] (row!-fn (rs/row! mrsb row) opts))
        rs/ResultSetBuilder
        (->rs [this] (rs/->rs mrsb))
        (with-row [this mrs row] (rs/with-row mrsb mrs row))
        (rs! [this mrs] (rs!-fn (rs/rs! mrsb mrs) opts))))))

(defrecord JdbcMiddleware [db global-opts]
  p/Executable
  (-execute [this sql-params opts]
    (let [opts          (merge global-opts opts)
          id2           (fn [x _] x)
          builder-fn    (get opts :builder-fn rs/as-maps)
          sql-params-fn (get opts :sql-params-fn id2)
          result        (sql-params-fn sql-params opts)
          sql-params'   (if (map? result)
                          (or (:next.jdbc/sql-params result) sql-params)
                          result)]
      (p/-execute db sql-params'
                  (assoc (if (map? result) result opts)
                         :builder-fn (post-processing-adapter builder-fn)
                         :next.jdbc/sql-params sql-params'))))
  (-execute-one [this sql-params opts]
    (let [opts          (merge global-opts opts)
          id2           (fn [x _] x)
          builder-fn    (get opts :builder-fn rs/as-maps)
          sql-params-fn (get opts :sql-params-fn id2)
          result        (sql-params-fn sql-params opts)
          sql-params'   (if (map? result)
                          (or (:next.jdbc/sql-params result) sql-params)
                          result)]
      (p/-execute-one db sql-params'
                      (assoc (if (map? result) result opts)
                             :builder-fn (post-processing-adapter builder-fn)
                             :next.jdbc/sql-params sql-params'))))
  (-execute-all [this sql-params opts]
    (let [opts          (merge global-opts opts)
          id2           (fn [x _] x)
          builder-fn    (get opts :builder-fn rs/as-maps)
          sql-params-fn (get opts :sql-params-fn id2)
          result        (sql-params-fn sql-params opts)
          sql-params'   (if (map? result)
                          (or (:next.jdbc/sql-params result) sql-params)
                          result)]
      (p/-execute-all db sql-params'
                      (assoc (if (map? result) result opts)
                             :builder-fn (post-processing-adapter builder-fn)
                             :next.jdbc/sql-params sql-params')))))

(defn wrapper
  ""
  ([db]      (JdbcMiddleware. db {}))
  ([db opts] (JdbcMiddleware. db opts)))
