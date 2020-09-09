;; copyright (c) 2020 Sean Corfield, all rights reserved

(ns next.jdbc.plan-test
  "Tests for the plan helpers."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc.plan :as plan]
            [next.jdbc.specs :as specs]
            [next.jdbc.test-fixtures
             :refer [with-test-db ds]]))

(set! *warn-on-reflection* true)

;; around each test because of the folding tests using 1,000 rows
(use-fixtures :each with-test-db)

(specs/instrument)

(deftest select-one!-tests
  (is (= {:id 1}
         (plan/select-one! (ds) [:id] ["select * from fruit order by id"])))
  (is (= 1
         (plan/select-one! (ds) :id ["select * from fruit order by id"])))
  (is (= "Banana"
         (plan/select-one! (ds) :name ["select * from fruit where id = ?" 2])))
  (is (= [1 "Apple"]
         (plan/select-one! (ds) (juxt :id :name)
                           ["select * from fruit order by id"])))
  (is (= {:id 1 :name "Apple"}
         (plan/select-one! (ds) #(select-keys % [:id :name])
                           ["select * from fruit order by id"]))))

(deftest select-vector-tests
  (is (= [{:id 1} {:id 2} {:id 3} {:id 4}]
         (plan/select! (ds) [:id] ["select * from fruit order by id"])))
  (is (= [1 2 3 4]
         (plan/select! (ds) :id ["select * from fruit order by id"])))
  (is (= ["Banana"]
         (plan/select! (ds) :name ["select * from fruit where id = ?" 2])))
  (is (= [[2 "Banana"]]
         (plan/select! (ds) (juxt :id :name)
                       ["select * from fruit where id = ?" 2])))
  (is (= [{:id 2 :name "Banana"}]
         (plan/select! (ds) [:id :name]
                       ["select * from fruit where id = ?" 2]))))

(deftest select-set-tests
  (is (= #{{:id 1} {:id 2} {:id 3} {:id 4}}
         (plan/select! (ds) [:id] ["select * from fruit order by id"]
                       {:into #{}})))
  (is (= #{1 2 3 4}
         (plan/select! (ds) :id ["select * from fruit order by id"]
                       {:into #{}}))))

(deftest select-map-tests
  (is (= {1 "Apple", 2 "Banana", 3 "Peach", 4 "Orange"}
         (plan/select! (ds) (juxt :id :name) ["select * from fruit order by id"]
                       {:into {}}))))
