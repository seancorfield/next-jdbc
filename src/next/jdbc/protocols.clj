;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.protocols
  "This is the extensible core of the next generation java.jdbc library.

  get-datasource -- turn something into a javax.sql.DataSource; implementations
      are provided for strings, hash maps (db-spec structures), and also a
      DataSource (which just returns itself).

  get-connection -- create a new JDBC connection that should be closed when you
      are finished with it; implementations are provided for DataSource and
      Object, on the assumption that an Object can possibly be turned into a
      DataSource.

  -execute -- given SQL and parameters, produce a 'reducible' that, when
      reduced, executes the SQL and produces a ResultSet that can be processed;
      implementations are provided for Connection, DataSource,
      PreparedStatement, and Object (on the assumption that an Object can be
      turned into a DataSource and therefore used to get a Connection).

  prepare -- given SQL and parameters, produce a PreparedStatement that can
      be executed (by -execute above); implementation is provided for
      Connection.

  -transact -- given a function (presumably containing SQL operations),
      run the function inside a SQL transaction; implementations are provided
      for Connection, DataSource, and Object (on the assumption that an Object
      can be turned into a DataSource).")

(set! *warn-on-reflection* true)

(defprotocol Sourceable
  (get-datasource ^javax.sql.DataSource [this]))
(defprotocol Connectable
  (get-connection ^java.lang.AutoCloseable [this opts]))
(defprotocol Executable
  (-execute ^clojure.lang.IReduceInit [this sql-params opts]))
(defprotocol Preparable
  (prepare ^java.sql.PreparedStatement [this sql-params opts]))
(defprotocol Transactable
  (-transact [this body-fn opts]))
