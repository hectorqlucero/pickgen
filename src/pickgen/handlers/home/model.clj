(ns pickgen.handlers.home.model
  (:require
   [pickgen.models.crud :refer [db Query Save]]
   [pickdict.crud :as pick-crud]))

(defn get-user
  [username]
  (first (Query ["SELECT * FROM users WHERE username=?" username])))

(defn get-users
  []
  (pick-crud/read-all-records db "users"))

(defn update-password
  [username password]
  (Save :users {:password password} ["username = ?" username]))

(comment
  (Query ["select * from contactos"])
  (get-user "system@gmail.com")
  (get-users))
