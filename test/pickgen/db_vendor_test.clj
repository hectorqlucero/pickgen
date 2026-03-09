(ns pickgen.db-vendor-test
  (:require [clojure.test :refer [deftest is testing]]
            [pickgen.models.crud :as crud]))

(deftest crud-fix-id-test
  (testing "crud-fix-id handles various inputs"
    (is (= 0 (crud/crud-fix-id nil)))
    (is (= 42 (crud/crud-fix-id 42)))
    (is (= 42 (crud/crud-fix-id "42")))
    (is (= 0 (crud/crud-fix-id "")))
    (is (= 0 (crud/crud-fix-id "0")))))

(deftest build-pk-where-clause-test
  (testing "builds WHERE clause from pk map"
    (let [[clause values] (crud/build-pk-where-clause {:id 1})]
      (is (= "id = ?" clause))
      (is (= [1] (vec values)))))
  (testing "composite pk"
    (let [[clause values] (crud/build-pk-where-clause {:a 1 :b 2})]
      (is (clojure.string/includes? clause "a = ?"))
      (is (clojure.string/includes? clause "b = ?"))
      (is (= 2 (count values))))))
