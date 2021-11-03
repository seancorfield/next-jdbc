;; copyright (c) 2020-2021 Sean Corfield, all rights reserved

(ns next.jdbc.plan
  "Some helper functions that make common operations with `next.jdbc/plan`
  much easier."
  (:require [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn select-one!
  "Execute the SQL and params using `next.jdbc/plan` and return just the
  selected columns from just the first row.

  `(plan/select-one! ds [:total] [\"select count(*) as total from table\"])`
  ;;=> {:total 42}

  If the `cols` argument is a vector of columns to select, then it is
  applied using `select-keys`, otherwise, the `cols` argument is used as
  a function directly. That means it can be a simple keyword to return
  just that column -- which is the most common expected usage:

  `(plan/select-one! ds :total [\"select count(*) as total from table\"])`
  ;;=> 42

  The usual caveats apply about operations on a raw result set that
  can be done without realizing the whole row."
  ([connectable cols sql-params]
   (select-one! connectable cols sql-params {}))
  ([connectable cols sql-params opts]
   (reduce (fn [_ row] (reduced (if (vector? cols)
                                  (select-keys row cols)
                                  (cols row))))
           nil
           (jdbc/plan connectable sql-params opts))))

(defn select!
  "Execute the SQL and params using `next.jdbc/plan` and (by default)
  return a vector of rows with just the selected columns.

  `(plan/select! ds [:id :name] [\"select * from table\"])`

  If the `cols` argument is a vector of columns to select, then it is
  applied as:

  `(into [] (map #(select-keys % cols)) (jdbc/plan ...))`

  Otherwise, the `cols` argument is used as a function and mapped over
  the raw result set as:

  `(into [] (map cols) (jdbc/plan ...))`

  The usual caveats apply about operations on a raw result set that
  can be done without realizing the whole row.

  Note: this allows for the following usage, which returns a vector
  of all the values for a single column:

  `(plan/select! ds :id [\"select * from table\"])`

  The result is a vector by default, but can be changed using the
  `:into` option to provide the initial data structure into which
  the selected columns are poured, e.g., `:into #{}`"
  ([connectable cols sql-params]
   (select! connectable cols sql-params {}))
  ([connectable cols sql-params opts]
   (into (or (:into opts) [])
         (map (if (vector? cols)
                #(select-keys % cols)
                cols))
         (jdbc/plan connectable sql-params opts))))
