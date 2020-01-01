;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.middleware-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as c]
            [next.jdbc.middleware :as mw]
            [next.jdbc.test-fixtures :refer [with-test-db db ds
                                              default-options
                                              derby? postgres?]]
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs])
  (:import (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest logging-test
  (let [logging (atom [])
        logger  (fn [data _] (swap! logging conj data) data)

        sql-p   ["select * from fruit where id in (?,?) order by id desc" 1 4]]
    (jdbc/execute! (mw/wrapper (ds))
                   sql-p
                   (assoc (default-options)
                          :builder-fn rs/as-lower-maps
                          :sql-params-fn logger
                          :row!-fn logger
                          :rs!-fn logger))
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
                               {:builder-fn rs/as-lower-maps
                                :sql-params-fn logger
                                :rs!-fn logger})
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
  (let [timing   (atom {:calls 0 :total 0.0})
        start-fn (fn [sql-p opts]
                   (swap! (:timing opts) update :calls inc)
                   (assoc opts :start (System/nanoTime)))
        exec-fn  (fn [_ opts]
                   (let [end (System/nanoTime)]
                     (swap! (:timing opts) update :total + (- end (:start opts)))
                     opts))
        sql-p    ["select * from fruit where id in (?,?) order by id desc" 1 4]]
    (jdbc/execute! (mw/wrapper (ds) {:timing timing
                                     :sql-params-fn start-fn
                                     :execute-fn    exec-fn})
                   sql-p)
    (jdbc/execute! (mw/wrapper (ds) {:timing timing
                                     :sql-params-fn start-fn
                                     :execute-fn    exec-fn})
                   sql-p)
    (println (db) (:calls @timing) "calls took" (long (:total @timing)) "nanoseconds")))
