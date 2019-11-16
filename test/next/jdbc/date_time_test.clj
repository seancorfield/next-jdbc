;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.date-time-test
  "Date/time parameter auto-conversion tests.

  These tests contain no assertions. Without requiring `next.jdbc.date-time`
  several of the `insert` operations would throw exceptions for some databases
  so the test here just checks those operations 'succeed'."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time] ; to extend SettableParameter to date/time
            [next.jdbc.test-fixtures :refer [with-test-db db ds
                                              mssql?]]
            [next.jdbc.specs :as specs])
  (:import (java.sql ResultSet)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest issue-73
  (try
    (jdbc/execute-one! (ds) ["drop table fruit_time"])
    (catch Throwable _))
  (jdbc/execute-one! (ds) [(str "create table fruit_time (id int not null, deadline "
                                (if (mssql?) "datetime" "timestamp")
                                " not null)")])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 1 (java.util.Date.)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 2 (java.time.Instant/now)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 3 (java.time.LocalDate/now)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 4 (java.time.LocalDateTime/now)])
  (try
    (jdbc/execute-one! (ds) ["drop table fruit_time"])
    (catch Throwable _))
  (jdbc/execute-one! (ds) ["create table fruit_time (id int not null, deadline time not null)"])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 1 (java.util.Date.)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 2 (java.time.Instant/now)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 3 (java.time.LocalDate/now)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 4 (java.time.LocalDateTime/now)])
  (try
    (jdbc/execute-one! (ds) ["drop table fruit_time"])
    (catch Throwable _))
  (jdbc/execute-one! (ds) ["create table fruit_time (id int not null, deadline date not null)"])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 1 (java.util.Date.)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 2 (java.time.Instant/now)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 3 (java.time.LocalDate/now)])
  (jdbc/execute-one! (ds) ["insert into fruit_time (id, deadline) values (?,?)" 4 (java.time.LocalDateTime/now)]))
