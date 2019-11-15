;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.date-time
  "Optional namespace that extends `next.jdbc.prepare/SettableParameter`
  to various date/time types so that they will all be treated as SQL
  timestamps (which also supports date and time column types).

  Some databases support a wide variety of date/time type conversions.
  Other databases need a bit of help. You should only require this
  namespace if you database does not support these conversions automatically.

  * H2 and SQLite support conversion of Java Time (`Instant`, `LocalDate`,
    `LocalDateTime`) out of the box,
  * Nearly all databases support conversion of `java.util.Date` out of
    the box -- except PostgreSQL apparently!

  Types supported:
  * `java.time.Instant`
  * `java.time.LocalDate`
  * `java.time.LocalDateTime`
  * `java.util.Date` -- mainly for PostgreSQL

  PostgreSQL does not seem able to convert `java.util.Date` to a SQL
  timestamp by default (every other database can!) so you'll probably
  need to require this namespace, even if you don't use Java Time."
  (:require [next.jdbc.prepare :as p])
  (:import (java.sql PreparedStatement Timestamp)
           (java.time Instant LocalDate LocalDateTime)))

(set! *warn-on-reflection* true)

(extend-protocol p/SettableParameter
  ;; Java Time type conversion
  java.time.Instant
  (set-parameter [^java.time.Instant v ^PreparedStatement s ^long i]
    (.setTimestamp s i (Timestamp/from v)))
  java.time.LocalDate
  (set-parameter [^java.time.LocalDate v ^PreparedStatement s ^long i]
    (.setTimestamp s i (Timestamp/valueOf (.atStartOfDay v))))
  java.time.LocalDateTime
  (set-parameter [^java.time.LocalDateTime v ^PreparedStatement s ^long i]
    (.setTimestamp s i (Timestamp/valueOf v)))

  ;; this is just to help PostgreSQL:
  java.util.Date
  (set-parameter [^java.util.Date v ^PreparedStatement s ^long i]
    (.setTimestamp s i (Timestamp/from (.toInstant v)))))
