;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.protocols
  "")

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
