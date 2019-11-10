;; copyright (c) 2019 world singles networks llc

(ns next.jdbc.middleware
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
  * `:row!-fn` -- called on each row as it is fully-realized
  * `:rs!-fn` -- called on the whole result set once it is fully-realized

  The results of these functions are returned as the rows/result set."
  [builder-fn]
  (fn [rs opts]
    (let [id2     (fn [x _] x)
          exec-fn (get opts :execute-fn id2)
          opts    (exec-fn opts {})
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
          result        (sql-params-fn sql-params opts)]
      (p/-execute db
                  (if (map? result)
                    (or (:next.jdbc/sql-params result) sql-params)
                    result)
                  (assoc (if (map? result) result opts)
                         :builder-fn
                         (post-processing-adapter builder-fn)))))
  (-execute-one [this sql-params opts]
    (let [opts          (merge global-opts opts)
          id2           (fn [x _] x)
          builder-fn    (get opts :builder-fn rs/as-maps)
          sql-params-fn (get opts :sql-params-fn id2)
          result        (sql-params-fn sql-params opts)]
      (p/-execute-one db
                      (if (map? result)
                        (or (:next.jdbc/sql-params result) sql-params)
                        result)
                      (assoc (if (map? result) result opts)
                             :builder-fn
                             (post-processing-adapter builder-fn)))))
  (-execute-all [this sql-params opts]
    (let [opts          (merge global-opts opts)
          id2           (fn [x _] x)
          builder-fn    (get opts :builder-fn rs/as-maps)
          sql-params-fn (get opts :sql-params-fn id2)
          result        (sql-params-fn sql-params opts)]
      (p/-execute-all db
                      (if (map? result)
                        (or (:next.jdbc/sql-params result) sql-params)
                        result)
                      (assoc (if (map? result) result opts)
                             :builder-fn
                             (post-processing-adapter builder-fn))))))

(defn wrapper
  ""
  ([db]      (JdbcMiddleware. db {}))
  ([db opts] (JdbcMiddleware. db opts)))
