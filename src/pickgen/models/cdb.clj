(ns pickgen.models.cdb
  (:require
   [clojure.string :as st]
   [buddy.hashers :as hashers]
   [pickgen.models.crud :as crud :refer [Insert-multi Query!]]))

(def users-rows
  [{:lastname  "User"
    :firstname "Regular"
    :username  "user@example.com"
    :password  (hashers/derive "user")
    :dob       "1957-02-07"
    :email     "user@example.com"
    :level     "U"
    :active    "T"}
   {:lastname "User"
    :firstname "Admin"
    :username "admin@example.com"
    :password (hashers/derive "admin")
    :dob "1957-02-07"
    :email "admin@example.com"
    :level "A"
    :active "T"}
   {:lastname "User"
    :firstname "System"
    :username "system@example.com"
    :password (hashers/derive "system")
    :dob "1957-02-07"
    :email "system@example.com"
    :level "S"
    :active "T"}])

(def siblings-rows
  [{:name "Alberto Pacas" :age 22}
   {:name "Morticia Pacas" :age 38}
   {:name "Luisa Pacas" :age 30}
   {:name "Carlos Pacas" :age 25}])

(def cars-rows
  [{:company "Toyota" :model "Corolla" :year 2020}
   {:company "Honda"  :model "Civic"   :year 2022}
   {:company "Ford"   :model "Focus"   :year 2019}])

(def contactos-rows
  [{:name "Pedro Pacas"  :email "ppacas@example.com"  :phone "686 555 6667"
    :sibling_ids "1"     :car_ids "1"}
   {:name "Maria Pacas"  :email "mpacas@example.com"  :phone "686 777 8888"
    :sibling_ids "2]3]4" :car_ids "2"}])


(defn- normalize-token [s]
  (some-> s str st/trim (st/replace #"^:+" "") st/lower-case))

(defn- choose-conn-key
  "Resolve a user token to a key in crud/dbs. All connections are SQLite/pickdict."
  [token]
  (let [t (normalize-token token)
        dbs crud/dbs
        keys* (set (keys dbs))
        direct (when (seq t)
                 (some (fn [k] (when (= (name k) t) k)) keys*))]
    (or direct :default)))

(defn populate-tables
  "Populate a table with rows on the selected connection using pickdict.
   Uses simple DELETE + batch insert."
  [table rows & {:keys [conn]}]
  (let [conn* (or conn :default)
        table-s (name (keyword table))
        ;; coerce row values to DB-appropriate types using schema introspection
        typed-rows (mapv (fn [row]
                           (crud/build-postvars table-s row :conn conn*))
                         rows)]
    (println (format "[database] Seeding %s on connection %s" table-s (name conn*)))
    (try
      ;; Clear existing rows (portable across MySQL/Postgres/SQLite)
      (Query! (str "DELETE FROM " table-s) :conn conn*)
      ;; Batch insert rows
      (Insert-multi (keyword table-s) typed-rows :conn conn*)
      (println (format "[database] Seeded %d rows into %s (%s)"
                       (count typed-rows) table-s (name conn*)))
      (catch Exception e
        (println "[ERROR] Seeding failed for" table-s "on" (name conn*) ":" (.getMessage e))
        (throw e)))))

(defn database
  "Usage:
   - lein database                 ; seeds only users (default)
   - lein database --all           ; seeds users + contactos, siblings, cars
   - lein database localdb         ; seeds only users on :localdb connection
   - lein database localdb --all   ; seeds everything on :localdb connection"
  [& args]
  (let [flags (set (filter #(.startsWith ^String % "--") args))
        tokens (remove #(.startsWith ^String % "--") args)
        conn  (choose-conn-key (first tokens))
        all?  (contains? flags "--all")]
    (println (format "[database] Using connection: %s" (name conn)))
    (populate-tables "users" users-rows :conn conn)
    (when all?
      (populate-tables "siblings" siblings-rows :conn conn)
      (populate-tables "cars" cars-rows :conn conn)
      (populate-tables "contactos" contactos-rows :conn conn))
    (println "[database] Done.")))
