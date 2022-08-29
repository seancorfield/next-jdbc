;; copyright (c) 2018-2021 Sean Corfield, all rights reserved

(ns next.jdbc.protocols
  "This is the extensible core of the next generation java.jdbc library.

  * `Sourceable` -- for producing `javax.sql.DataSource` objects,
  * `Connectable` -- for producing new `java.sql.Connection` objects,
  * `Executable` -- for executing SQL operations,
  * `Preparable` -- for producing new `java.sql.PreparedStatement` objects,
  * `Transactable` -- for executing SQL operations transactionally.")

(set! *warn-on-reflection* true)

(defprotocol Sourceable
  "Protocol for producing a `javax.sql.DataSource`.

  Implementations are provided for strings, hash maps (`db-spec` structures),
  and also a `DataSource` (which just returns itself).

  Extension via metadata is supported."
  :extend-via-metadata true
  (get-datasource ^javax.sql.DataSource [this]
    "Produce a `javax.sql.DataSource`."))

(defprotocol Connectable
  "Protocol for producing a new JDBC connection that should be closed when you
  are finished with it.

  Implementations are provided for `DataSource`, `PreparedStatement`, and
  `Object`, on the assumption that an `Object` can be turned into a `DataSource`."
  (get-connection ^java.sql.Connection [this opts]
    "Produce a new `java.sql.Connection` for use with `with-open`."))

(defprotocol Executable
  "Protocol for executing SQL operations.

  Implementations are provided for `Connection`, `DataSource`,
  `PreparedStatement`, and `Object`, on the assumption that an `Object` can be
  turned into a `DataSource` and therefore used to get a `Connection`."
  (-execute ^clojure.lang.IReduceInit [this sql-params opts]
    "Produce a 'reducible' that, when reduced, executes the SQL and
    processes the rows of the `ResultSet` directly.")
  (-execute-one [this sql-params opts]
    "Executes the SQL or DDL and produces the first row of the `ResultSet`
    as a fully-realized, datafiable hash map (by default).")
  (-execute-all [this sql-params opts]
    "Executes the SQL and produces (by default) a vector of
    fully-realized, datafiable hash maps from the `ResultSet`."))

(defprotocol Preparable
  "Protocol for producing a new `java.sql.PreparedStatement` that should
  be closed after use. Can be used by `Executable` functions.

  Implementation is provided for `Connection` only."
  (prepare ^java.sql.PreparedStatement [this sql-params opts]
    "Produce a new `java.sql.PreparedStatement` for use with `with-open`."))

(defprotocol Transactable
  "Protocol for running SQL operations in a transaction.

  Implementations are provided for `Connection`, `DataSource`, and `Object`
  (on the assumption that an `Object` can be turned into a `DataSource`)."
  :extend-via-metadata true
  (-transact [this body-fn opts]
    "Run the `body-fn` inside a transaction."))
