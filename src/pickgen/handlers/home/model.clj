(ns pickgen.handlers.home.model
  (:require
   [pickgen.models.crud :refer [db Query Save]]))

(defn get-user
  [username]
  (first (Query ["SELECT * FROM users WHERE username=?" username])))

(defn get-users
  []
  (Query ["SELECT * FROM users"]))

(defn update-password
  [username password]
  (Save :users {:password password} ["username = ?" username]))

(comment
  (get-user "system@gmail.com")
  (get-users))
