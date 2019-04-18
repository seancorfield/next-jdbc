(ns next.jdbc-test
  "Not exactly a test suite -- more a series of examples."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :refer :all]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :refer :all]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

(comment
  (def db-spec {:dbtype "h2:mem" :dbname "perf"})
  ;; these should be equivalent
  (def con (get-connection (get-datasource db-spec) {}))
  (def con (get-connection db-spec {}))
  (execute-one! con ["DROP TABLE fruit"])
  ;; h2
  (execute-one! con ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
  ;; either this...
  (execute-one! con ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (1,'Apple','red',59,87), (2,'Banana','yellow',29,92.2), (3,'Peach','fuzzy',139,90.0), (4,'Orange','juicy',89,88.6)"])
  ;; ...or this (H2 can't return generated keys for this)
  (insert-multi! con :fruit [:id :name :appearance :cost :grade]
                 [[1 "Apple" "red" 59 87]
                  [2,"Banana","yellow",29,92.2]
                  [3,"Peach","fuzzy",139,90.0]
                  [4,"Orange","juicy",89,88.6]]
                 {:return-keys false})
  ;; mysql
  (execute! con ["CREATE TABLE fruit (id int auto_increment, name varchar(32), appearance varchar(32), cost int, grade real, primary key (id))"])
  (execute! con ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (1,'Apple','red',59,87), (2,'Banana','yellow',29,92.2), (3,'Peach','fuzzy',139,90.0), (4,'Orange','juicy',89,88.6)"]
            {:return-keys true})
  ;; when you're done
  (.close con)

  (require '[criterium.core :refer [bench quick-bench]])

  (require '[clojure.java.jdbc :as jdbc])

  ;; calibrate
  (quick-bench (reduce + (take 10e6 (range))))

  ;; raw java
  (defn select* [^java.sql.Connection con]
    (let [ps (doto (.prepareStatement con "SELECT * FROM fruit WHERE appearance = ?")
                   (.setObject 1 "red"))
          rs (.executeQuery ps)
          _ (.next rs)
          value (.getObject rs "name")]
      (.close ps)
      value))
  (quick-bench (select* con)) ; 1.15-1.19 micros

  ;; almost same as the Java example above -- 1.66-1.7 micros -- 1.4x Java
  (quick-bench
   (reduce (fn [rs m] (reduced (:name m)))
           nil
           (reducible! con ["select * from fruit where appearance = ?" "red"])))
  ;; run through convenience function -- 2.4 micros
  (quick-bench
   (:FRUIT/NAME (execute-one! con
                              ["select * from fruit where appearance = ?" "red"]
                              {})))

  ;; 6.8 micros -- 3x
  (quick-bench
   (jdbc/query {:connection con}
               ["select * from fruit where appearance = ?" "red"]
               {:row-fn :name :result-set-fn first}))

  ;; simple query -- 2.6 micros
  (quick-bench
   (execute! con ["select * from fruit where appearance = ?" "red"]))

  ;; 6.9 -- ~2.6x
  (quick-bench
   (jdbc/query {:connection con} ["select * from fruit where appearance = ?" "red"]))

  (quick-bench ; 4.55-4.57
   (execute! con ["select * from fruit"]))
  (quick-bench ; 4.34-4.4
   (execute! con ["select * from fruit"] {:gen-fn rs/as-arrays}))

  (quick-bench ; 9.5 -- 2x
   (jdbc/query {:connection con} ["select * from fruit"]))

  (quick-bench ; 8.2
   (with-transaction [t con {:rollback-only true}]
     (execute! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])))

  (quick-bench ; 15.7
   (with-transaction [t con {:rollback-only true}]
     (insert! t :fruit {:id 5, :name "Pear", :appearance "green", :cost 49, :grade 47})))

  (quick-bench ; 13.6 -- 1.6x
   (jdbc/with-db-transaction [t {:connection con}]
     (jdbc/db-set-rollback-only! t)
     (jdbc/execute! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])))

  (quick-bench ; 27.9-28.8 -- 1.8x
   (jdbc/with-db-transaction [t {:connection con}]
     (jdbc/db-set-rollback-only! t)
     (jdbc/insert! t :fruit {:id 5, :name "Pear", :appearance "green", :cost 49, :grade 47})))

  (delete! con :fruit {:id 5})
  ;; with a prepopulated prepared statement - 450ns
  (with-open [ps (prepare con ["select * from fruit where appearance = ?" "red"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (reducible! ps))]))

  (require '[next.jdbc.prepare :as prepare])

  ;; same as above but setting parameters inside the benchmark
  (with-open [ps (prepare con ["select * from fruit where appearance = ?"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (reducible! (prepare/set-parameters ps ["red"])))]))

  ;; this takes more than twice the time of the one above which seems strange
  (with-open [ps (prepare con ["select * from fruit where appearance = ?"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (reducible! (prepare/set-parameters ps ["red"])))
      (reduce (fn [_ row] (reduced (:name row)))
              nil
              (reducible! (prepare/set-parameters ps ["fuzzy"])))]))

  ;; full first row
  (quick-bench
   (execute-one! con ["select * from fruit where appearance = ?" "red"]))

  (with-transaction [t con {:rollback-only true}]
    (insert! t :fruit {:id 5, :name "Pear", :appearance "green", :cost 49, :grade 47})
    (query t ["select * from fruit where name = ?" "Pear"]))
  (query con ["select * from fruit where name = ?" "Pear"])

  (delete! con :fruit {:id 1})
  (update! con :fruit {:appearance "Brown"} {:name "Banana"})

  (reduce (fn [rs m] (reduced (assoc m :test 42)))
          nil
          (reducible! con ["select * from fruit where appearance = ?" "red"]))

  (reduce (fn [rs m] (reduced (rs/datafiable-row (assoc m :test 42) con {})))
          nil
          (reducible! con ["select * from fruit where appearance = ?" "red"]))

  (reduce (fn [rs m] (reduced (rs/datafiable-row m con {})))
          nil
          (reducible! con ["select * from fruit where appearance = ?" "red"]))

  (defrecord Fruit [id name appearance cost grade])

  (defn fruit-builder [^java.sql.ResultSet rs opts]
    (reify
      rs/RowBuilder
      (->row [_] (->Fruit (.getObject rs "id")
                          (.getObject rs "name")
                          (.getObject rs "appearance")
                          (.getObject rs "cost")
                          (.getObject rs "grade")))
      (with-column [_ row i] row)
      (column-count [_] 0) ; no need to iterate over columns
      (row! [_ row] row)
      rs/ResultSetBuilder
      (->rs [_] (transient []))
      (with-row [_ rs row] (conj! rs row))
      (rs! [_ rs] (persistent! rs))))

  (quick-bench ; 2.2 micros
   (execute-one! con ["select * from fruit where appearance = ?" "red"]
                 {:gen-fn fruit-builder}))
  (quick-bench ; 2.47 micros
   (execute! con ["select * from fruit where appearance = ?" "red"]
             {:gen-fn fruit-builder}))
  (quick-bench ; 3 micros
   (execute! con ["select * from fruit"]
             {:gen-fn fruit-builder}))

  ;; with a prepopulated prepared statement - 1.7-1.8 micros
  (with-open [ps (prepare con ["select * from fruit"] {})]
    (quick-bench
     (execute! ps [] {:gen-fn fruit-builder}))))
