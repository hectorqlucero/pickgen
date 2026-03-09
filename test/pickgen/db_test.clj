(ns pickgen.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [pickgen.models.crud :as crud]))

(deftest build-db-spec-test
  (testing "build-db-spec produces SQLite spec"
    (let [spec (crud/build-db-spec {:db-name "db/test.sqlite"})]
      (is (= "org.sqlite.JDBC" (:classname spec)))
      (is (= "sqlite" (:subprotocol spec)))
      (is (clojure.string/includes? (:subname spec) "db/test.sqlite")))))

(deftest build-db-spec-defaults-test
  (testing "build-db-spec defaults to pickgen.sqlite"
    (let [spec (crud/build-db-spec {})]
      (is (= "sqlite" (:subprotocol spec)))
      (is (clojure.string/includes? (:subname spec) "pickgen.sqlite")))))
