(ns next.jdbc-test
  "Not exactly a test suite -- more a series of examples."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :refer :all]
            [next.jdbc.result-set :as rs]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

(comment
  (def db-spec {:dbtype "h2:mem" :dbname "perf"})
  ;; these should be equivalent
  (def con (get-connection (get-datasource db-spec) {}))
  (def con (get-connection db-spec {}))
  (execute! con ["DROP TABLE fruit"])
  ;; h2
  (execute! con ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
  ;; either this...
  (execute! con ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (1,'Apple','red',59,87), (2,'Banana','yellow',29,92.2), (3,'Peach','fuzzy',139,90.0), (4,'Orange','juicy',89,88.6)"])
  ;; ...or this
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
  (quick-bench (select* con)) ; 1.06 micros

  ;; almost same as the Java example above -- 1.42-1.49 micros -- 1.4x Java
  (quick-bench
   (reduce (fn [rs m] (reduced (:name m)))
           nil
           (reducible! con ["select * from fruit where appearance = ?" "red"])))
  ;; run through convenience function -- 1.52-1.55 micros
  (quick-bench
   (execute-one! con
                 ["select * from fruit where appearance = ?" "red"]
                 :name
                 {}))
  ;; 5.7 micros -- 3.7x
  (quick-bench
   (jdbc/query {:connection con}
               ["select * from fruit where appearance = ?" "red"]
               {:row-fn :name :result-set-fn first}))

  ;; simple query -- 3.1-3.2 micros
  (quick-bench
   (execute! con ["select * from fruit where appearance = ?" "red"]))

  ;; 5.9 -- ~2x
  (quick-bench
   (jdbc/query {:connection con} ["select * from fruit where appearance = ?" "red"]))

  (quick-bench ; 5.77-5.89
   (execute! con ["select * from fruit"]))
  ;; this is not quite equivalent
  (quick-bench ; 5.34-5.4
   (into [] (map (partial into {})) (reducible! con ["select * from fruit"])))
  ;; but this is (equivalent to execute!)
  (quick-bench ; 5.58-5.8
   (into [] (map (rs/datafiable-row con {})) (reducible! con ["select * from fruit"])))

  (quick-bench ; 7.84-7.96 -- 1.3x
   (jdbc/query {:connection con} ["select * from fruit"]))

  (quick-bench ; 9.4-9.7
   (with-transaction [t con {:rollback-only true}]
     (execute! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])))

  (quick-bench ; 14.14-14.63
   (with-transaction [t con {:rollback-only true}]
     (insert! t :fruit {:id 5, :name "Pear", :appearance "green", :cost 49, :grade 47})))

  (quick-bench ; 12.9-13 -- 1.36x
   (jdbc/with-db-transaction [t {:connection con}]
     (jdbc/db-set-rollback-only! t)
     (jdbc/execute! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])))

  (quick-bench ; 25.52-25.74 -- 1.77x
   (jdbc/with-db-transaction [t {:connection con}]
     (jdbc/db-set-rollback-only! t)
     (jdbc/insert! t :fruit {:id 5, :name "Pear", :appearance "green", :cost 49, :grade 47})))

  (delete! con :fruit {:id 5})
  ;; with a prepopulated prepared statement
  (with-open [ps (prepare con ["select * from fruit where appearance = ?" "red"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (reducible! ps))]))

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

  ;; test assoc works
  (execute-one! con
                ["select * from fruit where appearance = ?" "red"]
                #(assoc % :test :value)
                {})

  ;; test assoc works
  (execute! con
            ["select * from fruit where appearance = ?" "red"]
            #(assoc % :test :value)
            {})

  (with-transaction [t con {:rollback-only true}]
    (insert! t :fruit {:id 5, :name "Pear", :appearance "green", :cost 49, :grade 47})
    (query t ["select * from fruit where name = ?" "Pear"]))
  (query con ["select * from fruit where name = ?" "Pear"])

  (delete! con :fruit {:id 1})
  (update! con :fruit {:appearance "Brown"} {:name "Banana"})

  (reduce rs/as-arrays nil (reducible! con ["select * from fruit"])))
