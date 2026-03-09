(ns pickgen.models.crud
  (:require
   [clojure.java.io :as io]
   [clojure.string :as st]
   [clojure.edn :as edn]
   [pickdict.core :as pick]
   [pickdict.database :as pick-db]))

;; Reusable regex patterns (private constants)
(def ^:private true-re  #"(?i)^(true|on|1)$")
(def ^:private false-re #"(?i)^(false|off|0)$")
(def ^:private int-re   #"^-?\d+$")
(def ^:private float-re #"^-?\d+(\.\d+)?$")

(defn- safe-long [v]
  "Safely coerce various config values to a long.
   Returns 0 on nil, non-number, or parse failure."
  (cond
    (nil? v) 0
    (number? v) (long v)
    (string? v) (try (Long/parseLong (st/trim v)) (catch Exception _ 0))
    :else 0))

;; Try to load SQLite driver eagerly (used by pickdict)
(try (Class/forName "org.sqlite.JDBC") (catch Throwable _))

;; --- configuration and connection management ---
(defn- read-edn-resource [path]
  "Read an EDN resource from classpath safely; returns nil on failure."
  (when-let [r (io/resource path)]
    (try
      (binding [*read-eval* false]
        (edn/read-string (slurp r)))
      (catch Throwable e
        (println "[WARN] Failed to read EDN resource" path ":" (.getMessage e))
        nil))))

(defn get-config []
  "Load configuration from `config/app-config.edn` and optionally
   merge values from `private/config.clj` if present. Returns an empty
   map when no config is found or parsed."
  (let [base (read-edn-resource "config/app-config.edn")
        private (read-edn-resource "private/config.clj")]
    (or (merge (or base {}) (or private {})) {})))

(def config (or (get-config) {}))

;; 16-byte session encryption key for Ring cookie-store
(defn- ensure-16-bytes [^String s]
  (let [^bytes bs (.getBytes (or s "") "UTF-8")]
    (if (>= (alength bs) 16)
      (java.util.Arrays/copyOf bs 16)
      (let [padded (byte-array 16)]
        (System/arraycopy bs 0 padded 0 (alength bs))
        padded))))

(def KEY
  (let [secret (or (:session-secret config)
                   (get-in config [:security :session-secret])
                   "rs-session-key")]
    (ensure-16-bytes secret)))

;; Ensure SQLite enforces foreign keys on every connection by appending a
;; connection parameter to the JDBC URL. SQLite's PRAGMA foreign_keys is
;; per-connection and not persisted in the DB file, so setting it at the
;; URL level ensures all connections created by java.jdbc have it ON.
;; Supported by org.xerial/sqlite-jdbc.
(defn- sqlite-ensure-fk-param [subname]
  (let [s (str subname)]
    (if (re-find #"(?:\?|&)foreign_keys=(?:on|true|1)" s)
      s
      (str s (if (st/includes? s "?") "&" "?") "foreign_keys=on"))))

(defn build-db-spec
  "Build a pickdict-compatible db spec (SQLite only).
   Pickdict expects {:classname .. :subprotocol .. :subname ..}."
  [cfg]
  (let [dbname (or (:db-name cfg) "db/pickgen.sqlite")]
    {:classname   "org.sqlite.JDBC"
     :subprotocol "sqlite"
     :subname     (sqlite-ensure-fk-param dbname)}))

;; Helper to resolve keyword indirection in :connections
(defn- resolve-conn [connections v]
  (loop [val v]
    (if (and (keyword? val) (contains? connections val))
      (recur (get connections val))
      val)))

(def dbs
  (let [conn-cands (cond
                     (and (:connections config) (map? (:connections config)))
                     (:connections config)

                     (and (map? config) (or (:db-name config)))
                     {:default config}

                     ;; Fallback to a sensible local SQLite file for development
                     :else
                     {:default {:db-name "db/pickgen.sqlite"}})]
    (into {}
          (keep (fn [[k v]]
                  (let [resolved (resolve-conn conn-cands v)]
                    (when (map? resolved)
                      [k (build-db-spec resolved)])))
                conn-cands))))

(def db (or (get dbs :default) (first (vals dbs))))
(doseq [[k v] dbs]
  (when (not= k :default)
    (intern *ns* (symbol (str "db_" (name k))) v)))

;; --- helpers ---
(defn- resolve-db
  ([] db)
  ([conn] (or (get dbs (or conn :default)) db)))

;; --- small helpers for Save / CRUD ---
(defn- row-exists? [db* table wherev]
  (let [clause (first wherev)
        values (rest wherev)
        sql (str "SELECT 1 FROM " (name table) " WHERE " clause " LIMIT 1")
        rs (pick-db/execute-query db* sql (vec values))]
    (seq rs)))

(defn- save-with-db [db* table row where]
  (let [tname (name table)
        columns (keys row)
        set-clause (st/join ", " (map #(str (name %) " = ?") columns))
        where-clause (first where)
        where-params (rest where)
        update-sql (str "UPDATE " tname " SET " set-clause " WHERE " where-clause)
        update-params (vec (concat (vals row) where-params))
        result (pick-db/execute-command db* update-sql update-params)
        cnt (if (number? result) (long result) 0)]
    (if (zero? cnt)
      (let [exists? (row-exists? db* table where)]
        (if exists?
          true
          (let [ins-id (pick-db/insert-record db* tname row)]
            (or (when ins-id {:id ins-id})
                true))))
      (pos? cnt))))

;; --- CRUD wrappers (multi-arity) ---
(defn Query [& args]
  (cond
    ;; (Query db sql)
    (and (= 2 (count args)) (map? (first args)))
    (let [db* (first args)
          sql (second args)]
      (if (vector? sql)
        (pick-db/execute-query db* (first sql) (vec (rest sql)))
        (pick-db/execute-query db* sql [])))

    :else
    (let [sql (first args)
          opts (apply hash-map (rest args))
          db* (resolve-db (:conn opts))]
      (if (vector? sql)
        (pick-db/execute-query db* (first sql) (vec (rest sql)))
        (pick-db/execute-query db* sql [])))))

(defn Query! [& args]
  (cond
    ;; (Query! db sql)
    (and (= 2 (count args)) (map? (first args)))
    (let [db* (first args)
          sql (second args)]
      (if (vector? sql)
        (pick-db/execute-command db* (first sql) (vec (rest sql)))
        (pick-db/execute-command db* sql [])))

    :else
    (let [sql (first args)
          opts (apply hash-map (rest args))
          db* (resolve-db (:conn opts))]
      (if (vector? sql)
        (pick-db/execute-command db* (first sql) (vec (rest sql)))
        (pick-db/execute-command db* sql [])))))

(defn Insert [& args]
  (cond
    ;; (Insert db table row)
    (and (= 3 (count args)) (map? (first args)))
    (let [db* (first args)
          table (second args)
          row (nth args 2)]
      (pick-db/insert-record db* (name table) row))

    :else
    (let [table (first args)
          row (second args)
          opts (apply hash-map (drop 2 args))
          db* (resolve-db (:conn opts))]
      (pick-db/insert-record db* (name table) row))))

(defn Insert-multi [& args]
  (cond
    ;; (Insert-multi db table rows)
    (and (= 3 (count args)) (map? (first args)))
    (pick-db/batch-insert (first args) (name (second args)) (nth args 2))

    :else
    (let [table (first args)
          rows (second args)
          opts (apply hash-map (drop 2 args))
          db* (resolve-db (:conn opts))]
      (pick-db/batch-insert db* (name table) rows))))

(defn Update [& args]
  (cond
    ;; (Update db table row where)
    (and (= 4 (count args)) (map? (first args)))
    (let [db* (first args)
          table (name (second args))
          row (nth args 2)
          where (nth args 3)
          columns (keys row)
          set-clause (st/join ", " (map #(str (name %) " = ?") columns))
          where-clause (first where)
          where-params (rest where)
          sql (str "UPDATE " table " SET " set-clause " WHERE " where-clause)
          params (vec (concat (vals row) where-params))]
      (pick-db/execute-command db* sql params))

    :else
    (let [table (name (first args))
          row (second args)
          where (nth args 2)
          opts (apply hash-map (drop 3 args))
          db* (resolve-db (:conn opts))
          columns (keys row)
          set-clause (st/join ", " (map #(str (name %) " = ?") columns))
          where-clause (first where)
          where-params (rest where)
          sql (str "UPDATE " table " SET " set-clause " WHERE " where-clause)
          params (vec (concat (vals row) where-params))]
      (pick-db/execute-command db* sql params))))

(defn Delete [& args]
  (try
    (cond
      ;; (Delete db table where)
      (and (= 3 (count args)) (map? (first args)))
      (let [db* (first args)
            table (name (second args))
            where (nth args 2)
            where-clause (first where)
            where-params (vec (rest where))
            sql (str "DELETE FROM " table " WHERE " where-clause)]
        (pick-db/execute-command db* sql where-params))

      :else
      (let [table (name (first args))
            where (second args)
            opts (apply hash-map (drop 2 args))
            db* (resolve-db (:conn opts))
            where-clause (first where)
            where-params (vec (rest where))
            sql (str "DELETE FROM " table " WHERE " where-clause)]
        (pick-db/execute-command db* sql where-params)))
    (catch Exception e
      (println "[ERROR] Delete failed:" (.getMessage e))
      (println "[ERROR] Exception details:" e)
      nil)))

(defn Save [& args]
  (cond
    ;; (Save db table row where)
    (and (= 4 (count args)) (map? (first args)))
    (let [db* (first args)
          table (second args)
          row (nth args 2)
          where (nth args 3)]
      (save-with-db db* table row where))

    :else
    (let [table (first args)
          row (second args)
          where (nth args 2)
          opts (apply hash-map (drop 3 args))
          db* (resolve-db (:conn opts))]
      (save-with-db db* table row where))))

;; --- schema discovery ---
(defn get-table-describe [table & {:keys [conn]}]
  (let [db* (resolve-db conn)
        rows (pick-db/execute-query db* (str "PRAGMA table_info(" table ")") [])]
    (map (fn [r]
           {:field (:name r)
            :type  (or (:type r) "TEXT")
            :null  (if (= 1 (:notnull r)) "NO" "YES")
            :default (:dflt_value r)
            :extra ""
            :key (when (= 1 (:pk r)) "PRI")})
         rows)))

(defn get-table-columns [table & {:keys [conn]}]
  (map #(keyword (:field %)) (get-table-describe table :conn conn)))

(defn get-table-types [table & {:keys [conn]}]
  (map #(keyword (:type %)) (get-table-describe table :conn conn)))

;; --- temporal parsers ---
(defn- parse-sql-date [s]
  (let [s (some-> s st/trim)]
    (when (and s (not (st/blank? s)))
      (or (when (re-matches #"\d{4}-\d{2}-\d{2}" s)
            (java.sql.Date/valueOf s))
          (try
            (let [fmt (java.time.format.DateTimeFormatter/ofPattern "MM/dd/yyyy")
                  ld  (java.time.LocalDate/parse s fmt)]
              (java.sql.Date/valueOf ld))
            (catch Exception _ nil))
          (try (-> s java.time.LocalDate/parse java.sql.Date/valueOf)
               (catch Exception _ nil))
          (when (>= (count s) 10)
            (let [x (subs s 0 10)]
              (when (re-matches #"\d{4}-\d{2}-\d{2}" x)
                (java.sql.Date/valueOf x))))))))

(defn- parse-sql-time [s]
  (let [s (some-> s st/trim)]
    (when (and s (not (st/blank? s)))
      (or (when (re-matches #"\d{2}:\d{2}:\d{2}" s)
            (java.sql.Time/valueOf s))
          (when (re-matches #"\d{2}:\d{2}" s)
            (java.sql.Time/valueOf (str s ":00")))))))

(defn- parse-sql-timestamp [s]
  (let [s (some-> s st/trim)]
    (when (and s (not (st/blank? s)))
      (or (try
            (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS")
                  ldt (java.time.LocalDateTime/parse s fmt)]
              (java.sql.Timestamp/valueOf ldt))
            (catch Exception _ nil))
          (try
            (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                  ldt (java.time.LocalDateTime/parse s fmt)]
              (java.sql.Timestamp/valueOf ldt))
            (catch Exception _ nil))
          (try
            (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
                  ldt (java.time.LocalDateTime/parse s fmt)]
              (java.sql.Timestamp/valueOf ldt))
            (catch Exception _ nil))
          (try
            (-> s java.time.OffsetDateTime/parse .toLocalDateTime java.sql.Timestamp/valueOf)
            (catch Exception _ nil))
          (try
            (-> s java.time.LocalDateTime/parse java.sql.Timestamp/valueOf)
            (catch Exception _ nil))))))

;; --- field processing ---

(defn- normalize-ftype [field-type]
  (-> field-type st/lower-case (st/replace #"\(.*\)" "") st/trim))

(defn- char->value [^String v]
  (let [v (st/trim v)
        vu (st/upper-case v)]
    (cond
      (st/blank? v) nil
      (re-matches true-re v)  "T"
      (re-matches false-re v) "F"
      :else vu)))

(defn- parse-int-like [^String s]
  (cond
    (st/blank? s) 0
    (re-matches true-re s)  1
    (re-matches false-re s) 0
    (re-matches int-re s) (try (Long/parseLong s) (catch Exception _ 0))
    :else 0))

(defn- parse-float-like [^String s]
  (cond
    (st/blank? s) 0.0
    (re-matches float-re s) (try (Double/parseDouble s) (catch Exception _ 0.0))
    :else 0.0))

(defn- parse-bool-like [^String s]
  (cond
    (st/blank? s) false
    (re-matches true-re s)  true
    (re-matches false-re s) false
    (re-matches int-re s) (not= s "0")
    :else false))

(defn process-field [params field field-type]
  (let [value (str ((keyword field) params))
        ftype (normalize-ftype field-type)]
    (cond
      ;; String-like (include Postgres types)
      (or (st/includes? ftype "varchar")
          (= ftype "character varying")
          (st/includes? ftype "character varying")
          (= ftype "character")
          (st/includes? ftype "text")
          (st/includes? ftype "enum")
          (st/includes? ftype "set")) value

      ;; Strict CHAR only (likely MySQL char(N)). Normalize booleans if applicable; otherwise, don't truncate unless it is clearly a single char input.
      (= ftype "char")
      (char->value value)

      ;; Integer types
      (or (st/includes? field-type "int")
          (st/includes? field-type "tinyint")
          (st/includes? field-type "smallint")
          (st/includes? field-type "mediumint")
          (st/includes? field-type "bigint"))
      (parse-int-like value)

      ;; Floating point
      (or (st/includes? field-type "float")
          (st/includes? field-type "double")
          (st/includes? field-type "decimal"))
      (parse-float-like value)

      ;; Year
      (st/includes? field-type "year")
      (if (st/blank? value) nil (subs value 0 (min 4 (count value))))

      ;; Date/timestamp
      (or (st/includes? field-type "date")
          (st/includes? field-type "datetime")
          (st/includes? field-type "timestamp"))
      (cond
        (st/blank? value) nil
        (st/includes? field-type "date")      (parse-sql-date value)
        (st/includes? field-type "timestamp") (parse-sql-timestamp value)
        (st/includes? field-type "datetime")  (or (parse-sql-timestamp value)
                                                  (parse-sql-date value))
        :else nil)

      ;; Time
      (st/includes? field-type "time")
      (if (st/blank? value) nil (or (parse-sql-time value) value))

      ;; Binary/JSON
      (or (st/includes? field-type "blob")
          (st/includes? field-type "binary")
          (st/includes? field-type "varbinary")) value

      (or (st/includes? field-type "json") (st/includes? field-type "jsonb"))
      (if (st/blank? value) nil value)

      ;; Boolean
      (or (st/includes? field-type "bit")
          (st/includes? field-type "bool")
          (st/includes? field-type "boolean"))
      (parse-bool-like value)

      :else value)))

(defn build-postvars [table params & {:keys [conn]}]
  (let [td (get-table-describe table :conn conn)
        ;; normalize keys
        params (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) params))]
    (when (empty? td)
      (println "[WARN] get-table-describe returned no columns for table" table "conn" (or conn :default)))
    (let [m (into {}
                  (keep (fn [x]
                          (when ((keyword (:field x)) params)
                            {(keyword (:field x))
                             (process-field params (:field x) (:type x))}))
                        td))]
      (when (empty? m)
        (println "[WARN] build-postvars empty for" table "params" (keys params) "cols" (map :field td) "conn" (or conn :default)))
      m)))

;; SQLite time field projection for SELECTs used by forms
(defn build-form-field [_db* d]
  (let [ftype (some-> (:type d) st/lower-case st/trim)]
    (if (and ftype (re-find #"(^|\s)time(\s|$)" ftype) (not (st/includes? ftype "timestamp")))
      (str "strftime('%H:%M', " (:field d) ") AS " (:field d))
      (:field d))))

(defn get-table-key [d]
  (when (seq d)
    (when-let [pk (first (filter #(or (= (:key %) "PRI") (= (:key %) "PRIMARY")) d))]
      (:field pk))))

(defn get-table-primary-keys
  ([table] (get-table-primary-keys table :conn :default))
  ([table & {:keys [conn]}]
   (let [describe (get-table-describe table :conn conn)
         pks (->> describe
                  (filter #(= (:key %) "PRI"))
                  (map :field)
                  vec)]
     (vec (or (seq pks)
              (when (some #(= (:field %) "id") describe)
                ["id"])
              [])))))

(defn get-primary-key-map
  ([table params] (get-primary-key-map table params :conn :default))
  ([table params & {:keys [conn]}]
   (let [pk-fields (get-table-primary-keys table :conn conn)]
     (into {}
           (keep (fn [field]
                   (when-let [value ((keyword field) params)]
                     [(keyword field) value]))
                 pk-fields)))))

(defn build-pk-where-clause [pk-map]
  (when (seq pk-map)
    (let [conditions (map (fn [[k _]] (str (name k) " = ?")) pk-map)
          values (vals pk-map)]
      [(str (st/join " AND " conditions)) values])))

(defn build-form-row
  ([table id-or-pk-map] (build-form-row table id-or-pk-map :conn :default))
  ([table id-or-pk-map & {:keys [conn]}]
   (let [describe (get-table-describe table :conn conn)
         pk-fields (get-table-primary-keys table :conn conn)]
     (when (seq pk-fields)
       (let [db* (resolve-db conn)
             head "SELECT "
             body (apply str (interpose "," (map #(build-form-field db* %) describe)))
             pk-map (cond
                      (map? id-or-pk-map) id-or-pk-map
                      (= 1 (count pk-fields)) {(keyword (first pk-fields)) id-or-pk-map}
                      :else nil)]
         (when pk-map
           (let [[where-clause values] (build-pk-where-clause pk-map)
                 foot (str " FROM " table " WHERE " where-clause)
                 sql (str head body foot)
                 row (Query db* (into [sql] values))]
             (first row)))))
     (when (empty? pk-fields)
       (try (println "[WARN] No primary key detected for table" table "conn" (or conn :default) "describe fields" (map :field describe)) (catch Throwable _))))))

(defn blank->nil [m]
  (into {} (for [[k v] m] [k (if (and (string? v) (st/blank? v)) nil v)])))

(defn crud-fix-id [id]
  (cond
    (nil? id) 0
    (number? id) (long id)
    (string? id) (let [s (st/trim id)]
                   (if (or (empty? s) (= s "0"))
                     0
                     (try (Long/parseLong s) (catch Exception _ 0))))
    :else 0))

(defn remove-emptys [postvars]
  (if (map? postvars)
    (apply dissoc postvars (for [[k v] postvars :when (nil? v)] k))
    {}))

(defn process-regular-form [params table & {:keys [conn]}]
  (letfn [(single-id-where [id] (if (= id 0) ["1 = 0"] ["id = ?" id]))
          (pk-is-new? [m]
            (or (empty? m)
                (every? (fn [[_ v]]
                          (or (nil? v)
                              (and (string? v) (st/blank? v))
                              (and (number? v) (= v 0))
                              (= (str v) "0"))) m)))
          (try-save [db* table postvars where-clause]
            (try
              (if (and (map? postvars) (seq postvars))
                (let [r (Save db* (keyword table) postvars where-clause)]
                  ;; Preserve {:id N} on insert so callers can get the new ID
                  (if (map? r) r (boolean r)))
                false)
              (catch Exception e
                (let [cause (or (.getCause e) e)]
                  (println "[ERROR] Save failed for" table "where" where-clause "message" (.getMessage cause))
                  (throw e)))))]
    (let [pk-fields (get-table-primary-keys table :conn conn)
          db* (resolve-db conn)]
      (if (= 1 (count pk-fields))
        (let [id (crud-fix-id (:id params))
              postvars (cond-> (-> (build-postvars table params :conn conn)
                                   blank->nil)
                         (= id 0) (dissoc :id))
              where-clause (single-id-where id)]
          (try-save db* table postvars where-clause))
        (let [pk-map (get-primary-key-map table params :conn conn)
              is-new? (pk-is-new? pk-map)
              base-postvars (-> (build-postvars table params :conn conn) blank->nil)
              postvars (if is-new?
                         (apply dissoc base-postvars (map keyword pk-fields))
                         base-postvars)]
          (try
            (if (and (map? postvars) (seq postvars))
              (let [[clause values] (when-not is-new? (build-pk-where-clause pk-map))
                    where-clause (if is-new? ["1 = 0"] (into [clause] values))]
                (boolean (Save db* (keyword table) postvars where-clause)))
              false)
            (catch Exception e
              (let [cause (or (.getCause e) e)]
                (println "[ERROR] Save failed for" table "message" (.getMessage cause))
                (throw e)))))))))

(defn crud-upload-image [table file id path]
  (let [cfg-exts (set (map st/lower-case (or (:allowed-image-exts config) ["jpg" "jpeg" "png" "gif" "bmp" "webp"])))
        valid-exts cfg-exts
        max-mb (safe-long (or (:max-file-size-mb config) (:max-upload-mb config) 0))
        tempfile   (:tempfile file)
        size       (:size file)
        orig-name  (:filename file)
        ext-from-name (when orig-name
                        (-> orig-name (st/split #"\.") last st/lower-case))]
    (when (and tempfile (pos? (or size 0)))
      (when (and (pos? max-mb) (> size (* max-mb 1024 1024)))
        (throw (ex-info (str "File too large (max " max-mb "MB)") {:type :upload-too-large :maxMB max-mb})))
      (let [ext (if (and ext-from-name (valid-exts ext-from-name))
                  (if (= ext-from-name "jpeg") "jpg" ext-from-name)
                  "jpg")
            image-name (str table "_" id "." ext)
            target-file (io/file (str path image-name))]
        (io/make-parents target-file)
        (io/copy tempfile target-file)
        image-name))))

;; --- uploads housekeeping ---
(defn- safe-delete-upload! [^String imagen]
  (when (and (string? imagen)
             (not (st/blank? imagen))
             (not (re-find #"[\\/]" imagen)))
    (let [f (io/file (str (:uploads config) imagen))]
      (when (.exists f)
        (try
          (.delete f)
          (catch Exception _))))))

(defn get-id [pk-values-or-id postvars table & {:keys [conn]}]
  (let [pk-fields (get-table-primary-keys table :conn conn)
        db* (resolve-db conn)]
    (cond
      (and (= 1 (count pk-fields)) (number? pk-values-or-id))
      (if (= pk-values-or-id 0)
        (when (map? postvars)
          (let [res (Save db* (keyword table) postvars ["1 = 0"])
                m (cond (map? res) res (sequential? res) (first res) :else nil)]
            (or (:id m) (:last_insert_rowid m))))
        pk-values-or-id)

      (map? pk-values-or-id)
      (let [is-new? (every? (fn [[_ v]] (or (nil? v) (= v 0)
                                            (and (string? v) (st/blank? v))))
                            pk-values-or-id)]
        (if is-new?
          (when (map? postvars)
            (let [res (Save db* (keyword table) postvars ["1 = 0"])
                  m (cond (map? res) res (sequential? res) (first res) :else nil)]
              (or (:id m) (:last_insert_rowid m) pk-values-or-id)))
          pk-values-or-id))

      :else pk-values-or-id)))

(defn process-upload-form [params table _folder & {:keys [conn]}]
  (letfn [(insert-then-upload-and-update! [db* table pk-name postvars file]
            (let [ins-id (pick-db/insert-record db* (name table) postvars)]
              (when-not ins-id (throw (ex-info "Could not retrieve inserted ID" {:table table})))
              (let [the-id (str ins-id)
                    path (str (:uploads config))
                    image-name (when (and the-id (not (st/blank? the-id)))
                                 (crud-upload-image table file the-id path))]
                (when image-name
                  (pick-db/execute-command db* (str "UPDATE " (name table) " SET imagen = ? WHERE " (name pk-name) " = ?")
                                           [image-name (try (Long/parseLong the-id) (catch Exception _ the-id))]))
                true)))
          (existing-or-composite-upload! [db* table pk-fields pk-map postvars is-new? file conn]
            (let [single-pk? (= 1 (count pk-fields))
                  the-id (if single-pk?
                           (str (or ((keyword (first pk-fields)) pk-map) ""))
                           (str (or (some identity (vals pk-map)) "")))
                  path (str (:uploads config))
                  image-name (when (and the-id (not (st/blank? the-id)))
                               (crud-upload-image table file the-id path))
                  effective-pk-map (if (and (not is-new?) single-pk?)
                                     {(keyword (first pk-fields)) (if (re-matches int-re the-id)
                                                                    (Long/parseLong the-id)
                                                                    the-id)}
                                     pk-map)
                  prev-row (when (and (not is-new?) (seq effective-pk-map))
                             (build-form-row table effective-pk-map :conn conn))
                  postvars (cond-> postvars image-name (assoc :imagen image-name))
                  [clause values] (build-pk-where-clause effective-pk-map)
                  where-clause (into [clause] values)
                  postvars* (apply dissoc postvars (map keyword pk-fields))
                  result (Save db* (keyword table) postvars* where-clause)]
              (when (and result image-name prev-row)
                (let [old (:imagen prev-row)]
                  (when (and (string? old) (not= old image-name))
                    (safe-delete-upload! old))))
              (boolean result)))]
    (let [pk-fields (get-table-primary-keys table :conn conn)
          pk-map (get-primary-key-map table params :conn conn)
          file (:file params)
          postvars (dissoc (build-postvars table params :conn conn) :file)
          is-new? (or (empty? pk-map)
                      (every? (fn [[_ v]]
                                (or (nil? v)
                                    (and (string? v) (st/blank? v))
                                    (and (number? v) (= v 0))
                                    (= (str v) "0"))) pk-map))
          postvars (-> (if is-new?
                         (apply dissoc postvars (map keyword pk-fields))
                         postvars)
                       blank->nil)
          db* (resolve-db conn)]
      (if (and (map? postvars) (seq postvars))
        (let [single-pk? (= 1 (count pk-fields))]
          (if (and is-new? single-pk?)
            (boolean (insert-then-upload-and-update! db* table (keyword (first pk-fields)) postvars file))
            (existing-or-composite-upload! db* table pk-fields pk-map postvars is-new? file conn)))
        (let [[clause values] (build-pk-where-clause pk-map)
              result (Delete db* (keyword table) (into [clause] values))]
          (boolean result))))))

;; --- public API ---
(defn build-form-save
  ([params table] (build-form-save params table :conn nil))
  ([params table & {:keys [conn]}]
   (let [file* (or (:file params) (get params "file"))
         non-empty-file? (and (map? file*) (pos? (or (:size file*) 0)))]

     (if non-empty-file?
       ;; normalize to keyword key to keep downstream logic consistent
       (process-upload-form (assoc params :file file*) table table :conn conn)
       (process-regular-form params table :conn conn)))))

(defn- select-row [db* table id-or-pk pk-fields]
  (if (= 1 (count pk-fields))
    (first (Query db* (into [(str "SELECT * FROM " table " WHERE id = ?")] [(crud-fix-id id-or-pk)])))
    (when (map? id-or-pk)
      (let [[clause values] (build-pk-where-clause id-or-pk)]
        (first (Query db* (into [(str "SELECT * FROM " table " WHERE " clause)] values)))))))

(defn- cascade-delete-images! [db* table row]
  (let [query-fn (fn [sql]
                   (if (vector? sql)
                     (Query db* sql)
                     (Query db* sql)))]
    (try
      ;; Use SQLite PRAGMA to find child tables with FK references
      (let [ti         (query-fn (str "PRAGMA table_info(" table ")"))
            pk-field   (:name (first (filter #(= 1 (:pk %)) ti)))
            parent-col (or pk-field "id")
            tables     (map :name (query-fn ["SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> ?" table]))]
        (doseq [t tables]
          (let [fklist (query-fn (str "PRAGMA foreign_key_list(" t ")"))
                refs   (filter #(= (name table) (name (:table %))) fklist)]
            (doseq [r* refs]
              (let [fkcol      (or (:from r*) (str (name table) "_id"))
                    tocol      (or (:to r*) parent-col)
                    parent-val ((keyword tocol) row)
                    cols       (query-fn (str "PRAGMA table_info(" t ")"))
                    has-imagen (boolean (some #(= (name (:name %)) "imagen") cols))]
                (when (and has-imagen parent-val)
                  (doseq [cr (query-fn [(str "SELECT imagen FROM " t " WHERE " fkcol " = ?") parent-val])]
                    (when-let [im (:imagen cr)] (safe-delete-upload! im)))))))))
      ;; Also handle config-based cascade
      (when-let [childs (get (:cascade-image-delete config) (keyword table))]
        (doseq [{:keys [table fk image-col]} childs]
          (let [fkcol (or fk "id")
                icol  (or image-col "imagen")
                pval  ((keyword (or (first (get-table-primary-keys table)) "id")) row)
                sql   (str "SELECT " icol " FROM " table " WHERE " fkcol " = ?")
                rows  (Query db* [sql pval])]
            (doseq [r rows]
              (when-let [im ((keyword icol) r)]
                (safe-delete-upload! im))))))
      (catch Exception _ nil))))

(defn- perform-delete [db* table id-or-pk pk-fields]
  (if (= 1 (count pk-fields))
    (let [id (crud-fix-id id-or-pk)]
      (Delete db* (keyword table) ["id = ?" id]))
    (if (map? id-or-pk)
      (let [pk-map id-or-pk
            [clause values] (build-pk-where-clause pk-map)]
        (Delete db* (keyword table) (into [clause] values)))
      nil)))

(defn- build-form-delete* [db* table id-or-pk pk-fields]
  (try
    (let [row (select-row db* table id-or-pk pk-fields)]
      (when-let [img (:imagen row)] (safe-delete-upload! img))
      (when row (cascade-delete-images! db* table row))
      (boolean (perform-delete db* table id-or-pk pk-fields)))
    (catch Exception e
      (println "[ERROR] build-form-delete failed:" (.getMessage e))
      false)))

(defn build-form-delete
  ([table id-or-pk]
   (let [pk-fields (get-table-primary-keys table)]
     (build-form-delete* db table id-or-pk pk-fields)))
  ([table id-or-pk & {:keys [conn]}]
   (let [pk-fields (get-table-primary-keys table :conn conn)
         db* (resolve-db conn)]
     (build-form-delete* db* table id-or-pk pk-fields))))

;; --- small helpers for composite keys ---
(defn has-composite-primary-key?
  ([table] (has-composite-primary-key? table :conn :default))
  ([table & {:keys [conn]}] (> (count (get-table-primary-keys table :conn conn)) 1)))

(defn validate-primary-key-params
  ([table params] (validate-primary-key-params table params :conn :default))
  ([table params & {:keys [conn]}]
   (let [pk-fields (get-table-primary-keys table :conn conn)
         pk-map (get-primary-key-map table params :conn conn)]
     (= (count pk-fields) (count pk-map)))))

(defn build-pk-string [pk-map]
  (when (seq pk-map)
    (st/join "_" (map (fn [[k v]] (str (name k) "-" v)) pk-map))))
