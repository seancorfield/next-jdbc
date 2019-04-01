(ns next.jdbc-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :refer :all]
            [next.jdbc.result-set :as rs]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

(comment
  (def db-spec {:dbtype "h2:mem" :dbname "perf"})
  (def db-spec {:dbtype "derby" :dbname "perf" :create true})
  (def db-spec {:dbtype "mysql" :dbname "worldsingles" :user "root" :password "visual"})
  (def con db-spec)
  (def con (get-datasource db-spec))
  (get-connection con {})
  (def con (get-connection (get-datasource db-spec) {}))
  (def con (get-connection db-spec {}))
  (execute! con ["DROP TABLE fruit"])
  ;; h2
  (execute! con ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
  (execute! con ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (1,'Apple','red',59,87), (2,'Banana','yellow',29,92.2), (3,'Peach','fuzzy',139,90.0), (4,'Orange','juicy',89,88.6)"])
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

  (.close con)

  (require '[criterium.core :refer [bench quick-bench]])

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
  (quick-bench (select* con))

  ;; almost same as the Java example above
  (quick-bench
   (reduce (fn [rs m] (reduced (:name m)))
           nil
           (reducible! con ["select * from fruit where appearance = ?" "red"])))
  (quick-bench
   (execute-one! con
                 ["select * from fruit where appearance = ?" "red"]
                 {:row-fn :name}))

  ;; simple query
  (quick-bench
   (execute! con ["select * from fruit where appearance = ?" "red"]))

  (execute! con ["select * from fruit"])
  (into [] (map (partial into {})) (reducible! con ["select * from fruit"]))
  (into [] (map (rs/datafiable-row con {})) (reducible! con ["select * from fruit"]))

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
                {:row-fn #(assoc % :test :value)})

  ;; test assoc works
  (execute! con
            ["select * from fruit where appearance = ?" "red"]
            {:row-fn #(assoc % :test :value)})

  (with-transaction [t con {:rollback-only? true}]
    (execute! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])
    (execute! t ["select * from fruit where name = ?" "Pear"]))
  (execute! con ["select * from fruit where name = ?" "Pear"])
  (delete! con :fruit {:id 1})

  (update! con :fruit {:appearance "Brown"} {:name "Banana"})
  (execute! con ["select * from membership"]))
