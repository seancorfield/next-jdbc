;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.middleware-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.middleware :as mw]
            [next.jdbc.test-fixtures :refer [with-test-db db ds
                                              default-options]]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs])
  (:import (java.sql ResultSet)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest logging-test
  (let [logging (atom [])
        logger  (fn [data _]     (swap! logging conj data)  data)
        log-sql (fn [sql-p opts] (logger sql-p opts) [sql-p opts])
        sql-p   ["select * from fruit where id in (?,?) order by id desc" 1 4]]
    (jdbc/execute! (mw/wrapper (ds))
                   sql-p
                   (assoc (default-options)
                          :builder-fn     rs/as-lower-maps
                          :pre-execute-fn log-sql
                          :row!-fn        logger
                          :rs!-fn         logger))
    ;; should log four things
    (is (= 4     (-> @logging count)))
    ;; :next.jdbc/sql-params value
    (is (= sql-p (-> @logging (nth 0))))
    ;; first row (with PK 4)
    (is (= 4     (-> @logging (nth 1) :fruit/id)))
    ;; second row (with PK 1)
    (is (= 1     (-> @logging (nth 2) :fruit/id)))
    ;; full result set with two rows
    (is (= 2     (-> @logging (nth 3) count)))
    (is (= [4 1] (-> @logging (nth 3) (->> (map :fruit/id)))))
    ;; now repeat without the row logging
    (reset! logging [])
    (jdbc/execute! (mw/wrapper (ds)
                               {:builder-fn     rs/as-lower-maps
                                :pre-execute-fn log-sql
                                :rs!-fn         logger})
                   sql-p
                   (default-options))
    ;; should log two things
    (is (= 2     (-> @logging count)))
    ;; :next.jdbc/sql-params value
    (is (= sql-p (-> @logging (nth 0))))
    ;; full result set with two rows
    (is (= 2     (-> @logging (nth 1) count)))
    (is (= [4 1] (-> @logging (nth 1) (->> (map :fruit/id)))))))

(deftest timing-test
  (let [start-fn (fn [sql-p opts]
                   (swap! (::timing opts) update ::calls inc)
                   [sql-p (assoc opts ::start (System/nanoTime))])
        end-fn   (fn [rs opts]
                   (let [end (System/nanoTime)]
                     (swap! (::timing opts) update ::total + (- end (::start opts)))
                     [rs opts]))
        timing   (atom {::calls 0 ::total 0.0})
        mw-ds    (mw/wrapper (ds) {::timing         timing
                                   :pre-execute-fn  start-fn
                                   :post-execute-fn end-fn})
        sql-p    ["select * from fruit where id in (?,?) order by id desc" 1 4]]
    (jdbc/execute! mw-ds sql-p)
    (jdbc/execute! mw-ds sql-p)
    (printf "%20s - %d calls took %,10d nanoseconds\n"
            (:dbtype (db)) (::calls @timing) (long (::total @timing)))))

(deftest post-execute-tests
  (let [calls   (atom 0)
        seen-rs (atom 0)
        rows    (atom 0)
        rss     (atom 0)
        post-fn (fn [x opts]
                  (swap! calls inc)
                  (when (instance? ResultSet x)
                    (swap! seen-rs inc))
                  [x opts])
        mw-ds   (mw/wrapper (ds) {:post-execute-fn post-fn
                                  :row!-fn (fn [row _] (swap! rows inc) row)
                                  :rs!-fn  (fn [rs  _] (swap! rss  inc) rs)})]
    ;; first call, four rows, one result set
    (jdbc/execute! mw-ds ["select * from fruit"])
    (is (= 1 @calls))
    (is (= 1 @seen-rs))
    (is (= 4 @rows))
    (is (= 1 @rss))
    ;; second call, no rows, one more result set
    (jdbc/execute! mw-ds ["select * from fruit where id < 0"])
    (is (= 2 @calls))
    (is (= 2 @seen-rs))
    (is (= 4 @rows))
    (is (= 2 @rss))
    ;; third call, no result set
    (jdbc/execute! mw-ds ["update fruit set name = ? where id < 0" "Plum"])
    (is (= 3 @calls))
    (is (= 2 @seen-rs))
    (is (= 4 @rows))
    (is (= 2 @rss))
    ;; fourth call, one row, one result set (but no rs!-fn)
    (jdbc/execute-one! mw-ds ["select * from fruit"])
    (is (= 4 @calls))
    (is (= 3 @seen-rs))
    (is (= 5 @rows))
    (is (= 2 @rss))))

;; does middleware compose?
(deftest middleware-composition
  (let [pre   (atom 0)
        post  (atom 0)
        rows  (atom 0)
        inner (mw/wrapper (ds) {:pre-execute-fn #(do (swap! pre inc) [%1 %2])})
        mw-ds (mw/wrapper inner {:post-execute-fn #(do (swap! post inc) [%1 %2])})]
    (jdbc/execute! mw-ds ["select * from fruit"]
                   {:row!-fn (fn [row _] (swap! rows inc) row)})
    (is (= 1 @pre))
    (is (= 1 @post))
    (is (= 4 @rows))))
