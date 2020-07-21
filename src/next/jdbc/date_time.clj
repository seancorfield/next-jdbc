;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.date-time
  "Optional namespace that extends `next.jdbc.prepare/SettableParameter`
  to various date/time types so that they will all be treated as SQL
  timestamps (which also supports date and time column types) and has
  functions to extend `next.jdbc.result-set/ReadableColumn`.

  Simply requiring this namespace will extend the `SettableParameter` protocol
  to the four types listed below.

  In addition, there are several `read-as-*` functions here that will
  extend `next.jdbc.result-set/ReadableColumn` to allow `java.sql.Date`
  and `java.sql.Timestamp` columns to be read as (converted to) various
  Java Time types automatically. The expectation is that you will call at
  most one of these, at application startup, to enable the behavior you want.

  Database support for Java Time:
  * H2 and SQLite support conversion of Java Time (`Instant`, `LocalDate`,
    `LocalDateTime`) out of the box,
  * Nearly all databases support conversion of `java.util.Date` out of
    the box -- except PostgreSQL apparently!

  Types supported by this namespace:
  * `java.time.Instant`
  * `java.time.LocalDate`
  * `java.time.LocalDateTime`
  * `java.util.Date` -- mainly for PostgreSQL

  PostgreSQL does not seem able to convert `java.util.Date` to a SQL
  timestamp by default (every other database can!) so you'll probably
  need to require this namespace, even if you don't use Java Time, when
  working with PostgreSQL."
  (:require [next.jdbc.prepare :as p]
            [next.jdbc.result-set :as rs])
  (:import (java.sql PreparedStatement Timestamp)))

(set! *warn-on-reflection* true)

(extend-protocol p/SettableParameter
  ;; Java Time type conversion:
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
    (.setTimestamp s i (Timestamp/from (.toInstant v))))
  ;; but avoid unnecessary conversions for SQL Date and Timestamp:
  java.sql.Date
  (set-parameter [^java.sql.Date v ^PreparedStatement s ^long i]
    (.setDate s i v))
  java.sql.Timestamp
  (set-parameter [^java.sql.Timestamp v ^PreparedStatement s ^long i]
    (.setTimestamp s i v)))

(defn read-as-instant
  "After calling this function, `next.jdbc.result-set/ReadableColumn`
  will be extended to (`java.sql.Date` and) `java.sql.Timestamp` so that any
  timestamp columns will automatically be read as `java.time.Instant`.

  Note that `java.sql.Date` columns will still be returns as-is because they
  cannot be converted to an instant (they lack a time component)."
  []
  (extend-protocol rs/ReadableColumn
    java.sql.Date
    (read-column-by-label [^java.sql.Date v _]     v)
    (read-column-by-label [^java.sql.Date v _2 _3] v)
    (read-column-by-index [^java.sql.Date v _2 _3] v)
    java.sql.Timestamp
    (read-column-by-label [^java.sql.Timestamp v _]     (.toInstant v))
    (read-column-by-label [^java.sql.Timestamp v _2 _3] (.toInstant v))
    (read-column-by-index [^java.sql.Timestamp v _2 _3] (.toInstant v))))

(defn read-as-local
  "After calling this function, `next.jdbc.result-set/ReadableColumn`
  will be extended to `java.sql.Date` and `java.sql.Timestamp` so that any
  date or timestamp columns will automatically be read as `java.time.LocalDate`
  or `java.time.LocalDateTime` respectively."
  []
  (extend-protocol rs/ReadableColumn
    java.sql.Date
    (read-column-by-label [^java.sql.Date v _]     (.toLocalDate v))
    (read-column-by-label [^java.sql.Date v _2 _3] (.toLocalDate v))
    (read-column-by-index [^java.sql.Date v _2 _3] (.toLocalDate v))
    java.sql.Timestamp
    (read-column-by-label [^java.sql.Timestamp v _]     (.toLocalDateTime v))
    (read-column-by-label [^java.sql.Timestamp v _2 _3] (.toLocalDateTime v))
    (read-column-by-index [^java.sql.Timestamp v _2 _3] (.toLocalDateTime v))))

(defn read-as-default
  "After calling this function, `next.jdbc.result-set/ReadableColumn`
  will be extended to `java.sql.Date` and `java.sql.Timestamp` so that any
  date or timestamp columns will be read as-is. This is provided for
  completeness, to undo the effects of `read-as-instant` or `read-as-local`."
  []
  (extend-protocol rs/ReadableColumn
    java.sql.Date
    (read-column-by-label [^java.sql.Date v _]     v)
    (read-column-by-label [^java.sql.Date v _2 _3] v)
    (read-column-by-index [^java.sql.Date v _2 _3] v)
    java.sql.Timestamp
    (read-column-by-label [^java.sql.Timestamp v _]     v)
    (read-column-by-label [^java.sql.Timestamp v _2 _3] v)
    (read-column-by-index [^java.sql.Timestamp v _2 _3] v)))
