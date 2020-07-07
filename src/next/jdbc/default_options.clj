;; copyright (c) 2020 Sean Corfield, all rights reserved

(ns ^:no-doc next.jdbc.default-options
  "Implementation of default options logic."
  (:require [next.jdbc.protocols :as p]))

(defrecord DefaultOptions [connectable options])

(extend-protocol p/Sourceable
  DefaultOptions
  (get-datasource [this]
                  (p/get-datasource (:connectable this))))

(extend-protocol p/Connectable
  DefaultOptions
  (get-connection [this opts]
                  (p/get-connection (:connectable this)
                                    (merge (:options this) opts))))

(extend-protocol p/Executable
  DefaultOptions
  (-execute [this sql-params opts]
            (p/-execute (:connectable this) sql-params
                        (merge (:options this) opts)))
  (-execute-one [this sql-params opts]
                (p/-execute-one (:connectable this) sql-params
                                (merge (:options this) opts)))
  (-execute-all [this sql-params opts]
                (p/-execute-all (:connectable this) sql-params
                                (merge (:options this) opts))))

(extend-protocol p/Preparable
  DefaultOptions
  (prepare [this sql-params opts]
           (p/prepare (:connectable this) sql-params
                      (merge (:options this) opts))))

(extend-protocol p/Transactable
  DefaultOptions
  (-transact [this body-fn opts]
             (p/-transact (:connectable this) body-fn
                          (merge (:options this) opts))))
