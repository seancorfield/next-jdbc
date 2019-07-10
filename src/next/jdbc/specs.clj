;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.specs
  "Specs for the core API of next.jdbc.

  The functions from `next.jdbc`, `next.jdbc.sql`, and `next.jdbc.prepare`
  have specs here.

  Just `:args` are spec'd. These specs are intended to aid development
  with `next.jdbc` by catching simple errors in calling the library.
  The `connectable` argument is currently just `any?` but both
  `get-datasource` and `get-connection` have stricter specs. If you
  extend `Sourceable` or `Connectable`, those specs will likely be too strict.

  In addition, there is an `instrument` function that provides a simple
  way to instrument all of the `next.jdbc` functions."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.sql :as sql])
  (:import (java.sql Connection PreparedStatement)
           (javax.sql DataSource)))

(set! *warn-on-reflection* true)

(s/def ::dbtype string?)
(s/def ::dbname string?)
(s/def ::classname string?)
(s/def ::user string?)
(s/def ::password string?)
(s/def ::host string?)
(s/def ::port pos-int?)
(s/def ::db-spec-map (s/keys :req-un [::dbtype ::dbname]
                             :opt-un [::classname
                                      ::user ::password
                                      ::host ::port]))

(s/def ::connection #(instance? Connection %))
(s/def ::datasource #(instance? DataSource %))
(s/def ::prepared-statement #(instance? PreparedStatement %))

(s/def ::db-spec (s/or :db-spec ::db-spec-map
                       :string  string?
                       :ds      ::datasource))

(s/def ::connectable any?)
(s/def ::key-map (s/map-of keyword? any?))
(s/def ::example-map (s/map-of keyword? any? :min-count 1))
(s/def ::opts-map (s/map-of keyword? any?))

(s/def ::transactable any?)

(s/def ::sql-params (s/and vector?
                           (s/cat :sql string?
                                  :params (s/* any?))))
(s/def ::params (s/coll-of any? :kind sequential?))

(s/def ::batch-size pos-int?)
(s/def ::large boolean?)
(s/def ::batch-opts (s/keys :opt-un [::batch-size ::large]))

(s/fdef jdbc/get-datasource
        :args (s/cat :spec ::db-spec))

(s/fdef jdbc/get-connection
        :args (s/cat :spec ::db-spec
                     :opts (s/? ::opts-map)))

(s/fdef jdbc/prepare
        :args (s/cat :connection ::connection
                     :sql-params ::sql-params
                     :opts (s/? ::opts-map)))

(s/fdef jdbc/plan
        :args (s/alt :prepared (s/cat :stmt ::prepared-statement)
                     :sql (s/cat :connectable ::connectable
                                 :sql-params ::sql-params
                                 :opts (s/? ::opts-map))))

(s/fdef jdbc/execute!
        :args (s/alt :prepared (s/cat :stmt ::prepared-statement)
                     :sql (s/cat :connectable ::connectable
                                 :sql-params ::sql-params
                                 :opts (s/? ::opts-map))))

(s/fdef jdbc/execute-one!
        :args (s/alt :prepared (s/cat :stmt ::prepared-statement)
                     :sql (s/cat :connectable ::connectable
                                 :sql-params ::sql-params
                                 :opts (s/? ::opts-map))))

(s/fdef jdbc/transact
        :args (s/cat :transactable ::transactable
                     :f fn?
                     :opts (s/? ::opts-map)))

(s/fdef jdbc/with-transaction
        :args (s/cat :binding (s/and vector?
                                     (s/cat :sym simple-symbol?
                                            :transactable ::transactable
                                            :opts (s/? ::opts-map)))
                     :body (s/* any?)))

(s/fdef prepare/execute-batch!
        :args (s/cat :ps ::prepared-statement
                     :param-groups (s/coll-of ::params :kind sequential?)
                     :opts (s/? ::batch-opts)))

(s/fdef prepare/set-parameters
        :args (s/cat :ps ::prepared-statement
                     :params ::params))

(s/fdef sql/insert!
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :key-map ::key-map
                     :opts (s/? ::opts-map)))

(s/fdef sql/insert-multi!
        :args (s/and (s/cat :connectable ::connectable
                            :table keyword?
                            :cols (s/coll-of keyword? :kind vector?)
                            :rows (s/coll-of (s/coll-of any? :kind vector?) :kind vector?)
                            :opts (s/? ::opts-map))
                     #(apply = (count (:cols %))
                        (map count (:rows %)))))

(s/fdef sql/query
        :args (s/cat :connectable ::connectable
                     :sql-params ::sql-params
                     :opts (s/? ::opts-map)))

(s/fdef sql/find-by-keys
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :key-map (s/or :example ::example-map
                                    :where ::sql-params)
                     :opts (s/? ::opts-map)))

(s/fdef sql/get-by-id
        :args (s/alt :with-id (s/cat :connectable ::connectable
                                     :table keyword?
                                     :pk any?
                                     :opts (s/? ::opts-map))
                     :pk-name (s/cat :connectable ::connectable
                                     :table keyword?
                                     :pk any?
                                     :pk-name keyword?
                                     :opts ::opts-map)))

(s/fdef sql/update!
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :key-map ::key-map
                     :where-params (s/or :example ::example-map
                                         :where ::sql-params)
                     :opts (s/? ::opts-map)))

(s/fdef sql/delete!
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :where-params (s/or :example ::example-map
                                         :where ::sql-params)
                     :opts (s/? ::opts-map)))

(defn instrument []
  (st/instrument [`jdbc/get-datasource
                  `jdbc/get-connection
                  `jdbc/prepare
                  `jdbc/plan
                  `jdbc/execute!
                  `jdbc/execute-one!
                  `jdbc/transact
                  `jdbc/with-transaction
                  `prepare/execute-batch!
                  `prepare/set-parameters
                  `sql/insert!
                  `sql/insert-multi!
                  `sql/query
                  `sql/find-by-keys
                  `sql/get-by-id
                  `sql/update!
                  `sql/delete!]))
