
(ns pickgen.migrations
  "Database schema creation using pickdict (Pick/D3 multivalue philosophy).
   Tables are created with pick/create-file! and dictionaries with pick/define-dictionary-field.
   All operations are idempotent — safe to run multiple times."
  (:require
   [pickgen.models.crud :as crud]
   [clojure.java.io :as io]
   [pickdict.core :as pick]
   [pickdict.database :as pick-db]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ensure-db-dir!
  "Ensure the directory for the SQLite db file exists."
  [db-spec]
  (when-let [subname (:subname db-spec)]
    (let [f (io/file (-> subname (clojure.string/split #"\?") first))]
      (io/make-parents f))))

(defn- table-exists?
  "Check if a table already exists in the database."
  [db table-name]
  (let [rows (pick-db/execute-query
              db
              "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
              [table-name])]
    (pos? (count rows))))

(defn- dict-table-exists?
  "Check if the dictionary table for a given file/table already exists."
  [db table-name]
  (table-exists? db (str table-name "_DICT")))

(defn- dict-field-exists?
  "Check if a specific dictionary field already exists."
  [db dict-table field-name]
  (if (table-exists? db dict-table)
    (let [rows (pick-db/execute-query
                db
                (str "SELECT 1 FROM " dict-table " WHERE key=?")
                [field-name])]
      (pos? (count rows)))
    false))

;; ---------------------------------------------------------------------------
;; Table definitions (Pick/D3 style — no foreign keys)
;; ---------------------------------------------------------------------------

(defn- create-tables!
  "Create all application tables using pickdict. Idempotent."
  [db]
  ;; Users table
  (when-not (table-exists? db "users")
    (println "[migrate] Creating table: users")
    (pick/create-file! db "users"
                       {:id         "INTEGER PRIMARY KEY AUTOINCREMENT"
                        :lastname   "TEXT"
                        :firstname  "TEXT"
                        :username   "TEXT UNIQUE"
                        :password   "TEXT"
                        :dob        "TEXT"
                        :cell       "TEXT"
                        :phone      "TEXT"
                        :fax        "TEXT"
                        :email      "TEXT"
                        :level      "TEXT"
                        :active     "TEXT"
                        :imagen     "TEXT"
                        :last_login "TEXT DEFAULT (datetime('now'))"}))

  ;; Contactos table — includes multivalue fields for siblings and cars
  (when-not (table-exists? db "contactos")
    (println "[migrate] Creating table: contactos")
    (pick/create-file! db "contactos"
                       {:id          "INTEGER PRIMARY KEY AUTOINCREMENT"
                        :name        "TEXT"
                        :email       "TEXT"
                        :phone       "TEXT"
                        :imagen      "TEXT"
                        :sibling_ids "TEXT"   ;; Multivalue: 1]2]3 (Pick style)
                        :car_ids     "TEXT"}) ;; Multivalue: 1]2]3 (Pick style)
    )

  ;; Siblings table — standalone, no foreign keys (Pick philosophy)
  (when-not (table-exists? db "siblings")
    (println "[migrate] Creating table: siblings")
    (pick/create-file! db "siblings"
                       {:id     "INTEGER PRIMARY KEY AUTOINCREMENT"
                        :name   "TEXT"
                        :age    "INTEGER"
                        :imagen "TEXT"}))

  ;; Cars table — standalone, no foreign keys (Pick philosophy)
  (when-not (table-exists? db "cars")
    (println "[migrate] Creating table: cars")
    (pick/create-file! db "cars"
                       {:id      "INTEGER PRIMARY KEY AUTOINCREMENT"
                        :company "TEXT"
                        :model   "TEXT"
                        :year    "INTEGER"
                        :imagen  "TEXT"})))

;; ---------------------------------------------------------------------------
;; Dictionary definitions (T-type translation dictionaries)
;; ---------------------------------------------------------------------------

(defn- create-dictionaries!
  "Create T-type (translation) dictionary fields for cross-table lookups.
   A-type dictionaries are created automatically by create-file!.
   We only need to define T-type dictionaries to replace relational FK joins."
  [db]
  ;; Note: A-type (Attribute) fields are created automatically by create-file!
  ;; The following fields are created automatically for contactos table:
  ;; - NAME (A-type, position 1)
  ;; - EMAIL (A-type, position 2)
  ;; - PHONE (A-type, position 3)
  ;; - IMAGEN (A-type, position 4)
  ;; - SIBLING_IDS (A-type, position 5)
  ;; - CAR_IDS (A-type, position 6)
  (let [d "contactos_DICT"]
    ;; Translate sibling_ids (position 5) → siblings data
    (when-not (dict-field-exists? db d "SIBLING_NAMES")
      (pick/define-dictionary-field db d "SIBLING_NAMES" "T" "5" "Tsiblings;NAME" "Sibling Names"))
    (when-not (dict-field-exists? db d "SIBLING_AGES")
      (pick/define-dictionary-field db d "SIBLING_AGES" "T" "5" "Tsiblings;AGE" "Sibling Ages"))
    ;; Translate car_ids (position 6) → cars data
    (when-not (dict-field-exists? db d "CAR_COMPANIES")
      (pick/define-dictionary-field db d "CAR_COMPANIES" "T" "6" "Tcars;COMPANY" "Car Companies"))
    (when-not (dict-field-exists? db d "CAR_MODELS")
      (pick/define-dictionary-field db d "CAR_MODELS" "T" "6" "Tcars;MODEL" "Car Models"))
    (when-not (dict-field-exists? db d "CAR_YEARS")
      (pick/define-dictionary-field db d "CAR_YEARS" "T" "6" "Tcars;YEAR" "Car Years"))))

;; ---------------------------------------------------------------------------
;; Views (created via raw SQL since pickdict doesn't have view support)
;; ---------------------------------------------------------------------------

(defn- create-views!
  "Create database views. Idempotent via IF NOT EXISTS."
  [db]
  (when-not (let [rows (pick-db/execute-query
                        db
                        "SELECT name FROM sqlite_master WHERE type='view' AND name=?"
                        ["users_view"])]
              (pos? (count rows)))
    (println "[migrate] Creating view: users_view")
    (pick-db/execute-command
     db
     "CREATE VIEW IF NOT EXISTS users_view AS
      SELECT id, lastname, firstname, username, dob, cell, phone, fax,
             email, level, active, imagen, last_login,
             strftime('%d/%m/%Y', dob) as dob_formatted,
             CASE WHEN level = 'U' THEN 'Usuario'
                  WHEN level = 'A' THEN 'Administrador'
                  ELSE 'Sistema' END as level_formatted,
             CASE WHEN active = 'T' THEN 'Activo'
                  ELSE 'Inactivo' END as active_formatted
      FROM users ORDER BY lastname, firstname"
     [])))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- normalize-conn-key [k]
  (cond
    (keyword? k) k
    (string? k) (-> (if (.startsWith ^String k ":") (subs k 1) k)
                    keyword)
    :else (-> (str k)
              (cond-> (.startsWith ^String (str k) ":") (subs 1))
              keyword)))

(defn- resolve-db
  "Resolve a connection key to a pickdict db spec."
  ([] (resolve-db :main))
  ([conn-key]
   (let [conn-key (normalize-conn-key conn-key)]
     (or (get crud/dbs conn-key)
         (get crud/dbs :default)
         crud/db))))

(defn migrate
  "Create all tables, dictionaries, and views. Idempotent — safe to run repeatedly.
   Usage: (migrate) or (migrate :localdb)"
  ([] (migrate :main))
  ([conn-key]
   (let [db (resolve-db conn-key)]
     (ensure-db-dir! db)
     (println "[migrate] Using database:" (:subname db))
     (create-tables! db)
     (create-dictionaries! db)
     (create-views! db)
     (println "[migrate] Done."))))

(defn -main
  "Entry point for lein migrate."
  [& args]
  (migrate (or (first args) :main)))

(comment
  ;; Create all tables and dictionaries on the default database
  (migrate)

  ;; Create on a specific connection
  (migrate :localdb)

  ;; See which db-spec is being used
  (load-config)
  (load-config :analytics))
