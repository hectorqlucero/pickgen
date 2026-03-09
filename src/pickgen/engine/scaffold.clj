(ns pickgen.engine.scaffold
  "Scaffolding engine - generates entity configurations from database tables.
   
   Usage:
     lein scaffold products
     lein scaffold products --rights [A S]
     lein scaffold --all
     lein scaffold products --interactive"
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [pickdict.database :as pick-db]
   [pickgen.models.crud :as crud]))

(defn get-base-ns
  "Gets the base namespace (project name) from the current namespace"
  []
  (-> (namespace ::_)
      (str/split #"\.")
      first))

(defn hooks-path
  "Returns the path to hooks directory for this project"
  []
  (str "src/" (get-base-ns) "/hooks/"))

(def sql-type-map
  "Maps SQL types to entity field types"
  {;; Text types
   "VARCHAR" :text
   "CHAR" :text
   "TEXT" :textarea
   "LONGTEXT" :textarea
   "MEDIUMTEXT" :textarea
   "TINYTEXT" :text

   ;; Numeric types
   "INT" :number
   "INTEGER" :number
   "BIGINT" :number
   "SMALLINT" :number
   "TINYINT" :number
   "DECIMAL" :decimal
   "NUMERIC" :decimal
   "FLOAT" :decimal
   "DOUBLE" :decimal
   "REAL" :decimal

   ;; Date/Time types
   "DATE" :date
   "DATETIME" :datetime
   "TIMESTAMP" :datetime
   "TIME" :text

   ;; Boolean types
   "BOOLEAN" :checkbox
   "BOOL" :checkbox
   "BIT" :checkbox

   ;; Binary types
   "BLOB" :file
   "BYTEA" :file
   "BINARY" :file})

(defn detect-field-type
  "Detects field type from SQL column info"
  [column-info]
  (let [sql-type (str/upper-case (or (:type_name column-info)
                                     (:column_type column-info)
                                     "TEXT"))
        column-name (str/lower-case (:column_name column-info))
        size (:column_size column-info 0)]

    (cond
      ;; Convention-based detection
      (re-find #"email|mail|e_mail" column-name) :email
      (re-find #"password|passwd|pwd" column-name) :password
      (re-find #"phone|tel|mobile|cell" column-name) :text
      (re-find #"url|website|link|uri" column-name) :text
      (re-find #"image|photo|picture|img" column-name) :file
      (re-find #"description|comment|note|memo|text" column-name) :textarea
      (and (re-find #"VARCHAR|CHAR" sql-type) (> size 255)) :textarea

      ;; Type-based detection
      :else (or (get sql-type-map (first (str/split sql-type #"[(]")))
                :text))))

(defn humanize-label
  "Converts field name to human-readable label"
  [field-name]
  (->> (str/split (name field-name) #"[_-]")
       (map str/capitalize)
       (str/join " ")))

(defn- resolve-db [conn-key]
  (or (get crud/dbs conn-key) (get crud/dbs :default) crud/db))

(defn get-table-columns
  "Gets column information for a table using SQLite PRAGMA"
  [table-name conn-key]
  (let [db* (resolve-db conn-key)
        table (name table-name)]
    (try
      (let [cols (pick-db/execute-query db* (str "PRAGMA table_info(" table ")") [])]
        (vec (map (fn [c]
                    {:column_name (:name c)
                     :type_name   (or (:type c) "TEXT")
                     :column_size (if-let [m (re-find #"\((\d+)" (or (:type c) ""))]
                                    (Integer/parseInt (second m))
                                    0)
                     :nullable    (if (= 1 (:notnull c)) 0 1)
                     :is_autoincrement (if (= 1 (:pk c)) "YES" "NO")})
                  cols)))
      (catch Exception e
        (println "[ERROR] Failed to introspect table:" table-name)
        (.printStackTrace e)
        []))))

(defn get-primary-key
  "Gets primary key column for a table using SQLite PRAGMA"
  [table-name conn-key]
  (let [db* (resolve-db conn-key)
        table (name table-name)]
    (try
      (let [cols (pick-db/execute-query db* (str "PRAGMA table_info(" table ")") [])
            pk-col (first (filter #(= 1 (:pk %)) cols))]
        (or (:name pk-col) "id"))
      (catch Exception e
        (println "[WARN] Failed to get primary key for" table-name "- using 'id'")
        "id"))))

(defn get-foreign-keys
  "Gets foreign key relationships for a table using SQLite PRAGMA"
  [table-name conn-key]
  (let [db* (resolve-db conn-key)
        table (name table-name)]
    (try
      (let [fks (pick-db/execute-query db* (str "PRAGMA foreign_key_list(" table ")") [])]
        (mapv (fn [fk]
                {:column (keyword (str/lower-case (:from fk)))
                 :references-table (keyword (str/lower-case (:table fk)))
                 :references-column (keyword (str/lower-case (:to fk)))})
              fks))
      (catch Exception e
        (println "[WARN] Failed to get foreign keys for" table-name)
        []))))

(defn get-all-tables
  "Gets list of all tables in database (excluding system and DICT tables)"
  [conn-key]
  (let [db* (resolve-db conn-key)]
    (try
      (let [tables (pick-db/execute-query db* "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name" [])
            filtered (remove #(let [n (str/lower-case (:name %))]
                                (or (str/starts-with? n "sqlite_")
                                    (str/ends-with? n "_dict")
                                    (= n "schema_migrations")
                                    (= n "ragtime_migrations")))
                             tables)]
        (mapv #(keyword (str/lower-case (:name %))) filtered))
      (catch Exception e
        (println "[ERROR] Failed to get table list")
        (.printStackTrace e)
        []))))

(defn get-referencing-tables
  "Gets tables that reference the given table (reverse foreign keys for subgrids).
   Returns [{:table :alquileres :foreign-key :id_propiedad} ...]"
  [table-name conn-key]
  (let [db* (resolve-db conn-key)
        target (str/lower-case (name table-name))
        all-tables (get-all-tables conn-key)]
    (try
      (vec (for [t all-tables
                 :let [fks (pick-db/execute-query db* (str "PRAGMA foreign_key_list(" (name t) ")") [])
                       refs (filter #(= (str/lower-case (:table %)) target) fks)]
                 ref refs]
             {:table t
              :foreign-key (keyword (str/lower-case (:from ref)))
              :column (keyword (str/lower-case (:to ref)))}))
      (catch Exception e
        (println "[WARN] Failed to get referencing tables for" table-name)
        []))))

;; ---------------------------------------------------------------------------
;; Pick/D3 T-dictionary detection
;; ---------------------------------------------------------------------------

(defn- parse-dict-attributes
  "Parses a DICT attributes string like 'TYPE=T|POSITION=6|CONVERSION=Tsiblings;NAME'
   into a map {:type \"T\" :position \"6\" :conversion \"Tsiblings;NAME\"}."
  [attrs-str]
  (when attrs-str
    (into {}
          (for [part (str/split attrs-str #"\|")
                :let [[k v] (str/split part #"=" 2)]
                :when (and k v)]
            [(keyword (str/lower-case k)) v]))))

(defn- parse-t-conversion
  "Parses a T-dictionary CONVERSION like 'Tsiblings;NAME' → {:child-table \"siblings\" :child-field \"name\"}."
  [conversion]
  (when (and conversion (str/starts-with? conversion "T"))
    (let [rest-str (subs conversion 1)
          [child-table child-field] (str/split rest-str #";" 2)]
      (when (and child-table (not (str/blank? child-table)))
        (cond-> {:child-table (str/lower-case child-table)}
          (and child-field (not (str/blank? child-field)))
          (assoc :child-field (str/lower-case child-field)))))))

(defn get-subgrid-icon
  "Returns appropriate Bootstrap icon for a table based on its name"
  [table-name]
  (let [name-str (str/lower-case (name table-name))]
    (cond
      (re-find #"alquiler|rent" name-str) "bi bi-house-door"
      (re-find #"venta|sale|compra|purchase" name-str) "bi bi-currency-dollar"
      (re-find #"pago|payment" name-str) "bi bi-cash-stack"
      (re-find #"comision|commission" name-str) "bi bi-cash-coin"
      (re-find #"avaluo|appraisal|valuacion" name-str) "bi bi-calculator"
      (re-find #"contrato|contract" name-str) "bi bi-file-earmark-text"
      (re-find #"documento|document" name-str) "bi bi-file-earmark-pdf"
      (re-find #"tramite|procedure" name-str) "bi bi-clipboard-check"
      (re-find #"bitacora|log|audit" name-str) "bi bi-journal-text"
      (re-find #"cliente|client|customer" name-str) "bi bi-person"
      (re-find #"agente|agent" name-str) "bi bi-briefcase"
      (re-find #"propiedad|property" name-str) "bi bi-building"
      (re-find #"fiador|guarantor" name-str) "bi bi-shield-check"
      :else "bi bi-list-ul")))

(defn get-mv-subgrids-from-dict
  "Reads the _DICT table for a given table and detects multivalue subgrids
   from T-type dictionary entries.
   Returns [{:entity :cars :multivalue-field :car_ids :title \"Cars\"} ...]"
  [table-name conn-key]
  (let [db* (resolve-db conn-key)
        dict-table (str (name table-name) "_DICT")]
    (try
      (let [dict-exists? (pos? (count (pick-db/execute-query
                                       db*
                                       "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                                       [dict-table])))
            rows (when dict-exists?
                   (pick-db/execute-query db* (str "SELECT * FROM " dict-table) []))
            ;; Parse all entries into maps with parsed attributes
            parsed (map (fn [row]
                          (let [attrs (parse-dict-attributes (:attributes row))]
                            (assoc attrs :key (:key row))))
                        rows)
            ;; Separate A-type and T-type entries
            a-entries (filter #(= "A" (:type %)) parsed)
            t-entries (filter #(= "T" (:type %)) parsed)
            ;; Build position→field-name map from A-entries
            ;; Both A-type and T-type positions use the same 1-based scheme (after id)
            pos->field (into {}
                             (map (fn [a] [(:position a)
                                           (keyword (str/lower-case (:key a)))])
                                  a-entries))
            ;; Group T-entries by child table (deduplicate: one subgrid per child)
            t-by-child (group-by (fn [t]
                                   (:child-table (parse-t-conversion (:conversion t))))
                                 t-entries)]
        ;; Build subgrid config for each unique child table
        (vec (for [[child-table t-group] t-by-child
                   :when child-table
                   :let [;; All T-entries for this child share the same POSITION
                         position (:position (first t-group))
                         mv-field (get pos->field position)
                         ;; Collect display fields from all T-entries for this child
                         display-fields (vec (keep #(:child-field (parse-t-conversion (:conversion %)))
                                                   t-group))]]
               (cond-> {:entity (keyword child-table)
                        :title (humanize-label child-table)
                        :multivalue-field mv-field
                        :icon (get-subgrid-icon (keyword child-table))
                        :label (humanize-label child-table)}
                 (seq display-fields)
                 (assoc :display-fields (mapv keyword display-fields))))))
      (catch Exception _
        []))))

(defn get-mv-child-tables
  "Returns a set of table keywords that are children of any parent via T-dictionaries.
   Used to detect if the current table should be hidden from the menu."
  [table-name conn-key]
  (let [db* (resolve-db conn-key)
        all-tables (get-all-tables conn-key)]
    (into #{}
          (for [parent all-tables
                :let [mv-subs (get-mv-subgrids-from-dict parent conn-key)
                      is-child? (some #(= (:entity %) (keyword table-name)) mv-subs)]
                :when is-child?]
            parent))))

(defn get-parent-tables
  "Gets tables that this table is a subgrid of — via FK or T-dictionary.
   Returns set of parent table keywords."
  [table-name conn-key]
  (let [all-tables (get-all-tables conn-key)
        ;; Legacy FK-based parents
        fk-parents (into #{}
                         (for [parent-table all-tables
                               :let [refs (get-referencing-tables parent-table conn-key)
                                     has-this? (some #(= (:table %) (keyword table-name)) refs)]
                               :when has-this?]
                           parent-table))
        ;; Pick/D3 T-dictionary parents
        mv-parents (get-mv-child-tables table-name conn-key)]
    (into fk-parents mv-parents)))

(defn generate-field
  "Generates field configuration from column info"
  [column-info foreign-keys parent-tables]
  (let [column-name (keyword (str/lower-case (:column_name column-info)))
        field-type (detect-field-type column-info)
        is-nullable (not= 0 (:nullable column-info))
        is-pk (= "YES" (:is_autoincrement column-info))
        fk (first (filter #(= (:column %) column-name) foreign-keys))]

    (cond
      ;; Primary key
      is-pk
      {:id column-name
       :label "ID"
       :type :hidden}

      ;; Foreign key
      fk
      (let [is-parent-ref? (contains? parent-tables (:references-table fk))]
        (if is-parent-ref?
          ;; This FK references a parent table (subgrid relationship) - hide it
          {:id column-name
           :label (str (humanize-label (:references-table fk)) " ID")
           :type :hidden}
          ;; This FK is a lookup/reference - show as select
          {:id column-name
           :label (humanize-label (:references-table fk))
           :type :select
           :required? (not is-nullable)
           :options []
           :hidden-in-grid? true  ;; Hide FK ID in grid, show display name instead
           :foreign-key {:table (:references-table fk)
                         :column (:references-column fk)}}))

      ;; Multivalue ID field (Pick/D3 style — e.g. sibling_ids, car_ids)
      (re-find #"_ids$" (name column-name))
      {:id column-name
       :label (humanize-label column-name)
       :type :hidden}

      ;; Regular field
      :else
      (let [base {:id column-name
                  :label (humanize-label column-name)
                  :type field-type}]
        (cond-> base
          (not is-nullable) (assoc :required? true)
          (= field-type :text) (assoc :placeholder (str (humanize-label column-name) "..."))
          (= field-type :textarea) (assoc :placeholder (str (humanize-label column-name) "..."))
          (= field-type :email) (assoc :placeholder "user@example.com"))))))

(defn get-display-field-name
  "Gets the display field name for a foreign key reference.
   Examples: :propiedades → nombre or titulo, :clientes → nombre + apellido, :agentes → nombre"
  [fk-table conn-key]
  (let [columns (get-table-columns fk-table conn-key)
        column-names (map #(keyword (str/lower-case (:column_name %))) columns)]
    (cond
      ;; Check for titulo (properties, etc)
      (some #{:titulo :title} column-names)
      (if (some #{:titulo} column-names) :titulo :title)

      ;; Check for nombre/name (agents, clients, etc)
      (some #{:nombre :name} column-names)
      (if (some #{:nombre} column-names) :nombre :name)

      ;; Check for descripcion
      (some #{:descripcion :description} column-names)
      (if (some #{:descripcion} column-names) :descripcion :description)

      ;; Fallback to second column (skip id)
      :else (second column-names))))

(defn needs-name-concatenation?
  "Checks if a table needs CONCAT for full names (has nombre + apellido_paterno)"
  [fk-table conn-key]
  (let [columns (get-table-columns fk-table conn-key)
        column-names (map #(keyword (str/lower-case (:column_name %))) columns)]
    (and (some #{:nombre} column-names)
         (some #{:apellido_paterno} column-names))))

(defn generate-display-field
  "Generates a display field for a foreign key"
  [fk-info]
  (let [fk-table (:table fk-info)
        base-name (name fk-table)
        ;; Remove plural 's' for singular form
        singular (if (str/ends-with? base-name "s")
                   (subs base-name 0 (dec (count base-name)))
                   base-name)
        display-field-id (keyword (str singular "_nombre"))]
    {:id display-field-id
     :label (humanize-label fk-table)
     :type :text
     :grid-only? true}))

(defn generate-queries
  "Generates queries for an entity.
   For Pick/D3 tables (no FKs): uses :pickdict/list and :pickdict/get which
   resolve T-dictionary translations automatically.
   For legacy FK tables: builds SQL JOIN queries."
  [table-name foreign-keys conn-key]
  (if (empty? foreign-keys)
    ;; No FKs — use pickdict dictionary-aware queries
    {:list :pickdict/list
     :get  :pickdict/get}

    ;; Has FKs - build JOIN query (legacy path)
    (let [table-alias (let [n (name table-name)]
                        (if (> (count n) 2)
                          (subs n 0 3)
                          n))
          ;; Track used aliases to avoid duplicates
          used-aliases (atom #{table-alias})
          joins (for [fk foreign-keys]
                  (let [fk-table (:references-table fk)
                        fk-col (:column fk)
                        ;; Generate unique alias (3 letters)
                        base-alias (let [n (name fk-table)]
                                     (if (> (count n) 2)
                                       (subs n 0 3)
                                       n))
                        fk-alias (loop [alias base-alias
                                        suffix 1]
                                   (if (contains? @used-aliases alias)
                                     (recur (str base-alias suffix) (inc suffix))
                                     (do
                                       (swap! used-aliases conj alias)
                                       alias)))
                        display-field (get-display-field-name fk-table conn-key)
                        singular (if (str/ends-with? (name fk-table) "s")
                                   (subs (name fk-table) 0 (dec (count (name fk-table))))
                                   (name fk-table))
                        display-col (str singular "_nombre")]
                    {:join (str " LEFT JOIN " (name fk-table) " " fk-alias
                                " ON " table-alias "." (name fk-col) " = " fk-alias ".id")
                     :select (if (needs-name-concatenation? fk-table conn-key)
                               (str fk-alias ".nombre || ' ' || " fk-alias ".apellido_paterno as " display-col)
                               (str fk-alias "." (name display-field) " as " display-col))}))
          select-clause (str "SELECT " table-alias ".*"
                             (when (seq joins)
                               (str ", " (str/join ", " (map :select joins)))))
          from-clause (str " FROM " (name table-name) " " table-alias)
          join-clause (str/join "" (map :join joins))
          order-clause (str " ORDER BY " table-alias ".id DESC")]

      {:list (str select-clause from-clause join-clause order-clause)
       :get (str "SELECT * FROM " (name table-name) " WHERE id = ?")})))

(defn generate-subgrids
  "Generates subgrid configurations for tables that reference this table.
   Checks both FK references (legacy) and T-dictionaries (Pick/D3 multivalue)."
  [table-name conn-key]
  (let [fk-refs (get-referencing-tables table-name conn-key)
        fk-subgrids (mapv (fn [ref]
                            {:entity (:table ref)
                             :title (humanize-label (:table ref))
                             :foreign-key (:foreign-key ref)
                             :icon (get-subgrid-icon (:table ref))
                             :label (humanize-label (:table ref))})
                          fk-refs)
        mv-subgrids (get-mv-subgrids-from-dict table-name conn-key)
        ;; Merge both, MV subgrids take precedence over FK for the same child
        fk-entities (set (map :entity fk-subgrids))
        combined (into (vec mv-subgrids)
                       (remove #(some #{(:entity %)} (set (map :entity mv-subgrids)))
                               fk-subgrids))]
    (when (seq combined)
      combined)))

(defn generate-entity-config
  "Generates complete entity configuration from table schema"
  [table-name & {:keys [conn rights title]
                 :or {conn :default
                      rights ["U" "A" "S"]
                      title nil}}]
  (let [columns (get-table-columns table-name conn)
        pk (get-primary-key table-name conn)
        fks (get-foreign-keys table-name conn)
        parent-tables (get-parent-tables table-name conn)
        is-child? (seq parent-tables)
        base-fields (mapv #(generate-field % fks parent-tables) columns)

        ;; Generate display fields for each FK (insert after the FK field)
        fields-with-display (reduce
                             (fn [acc field]
                               (if (:foreign-key field)
                                 ;; FK field - add it and its display field
                                 (conj acc
                                       field
                                       (generate-display-field (:foreign-key field)))
                                 ;; Regular field - just add it
                                 (conj acc field)))
                             []
                             base-fields)

        table-title (or title (humanize-label table-name))
        subgrids (generate-subgrids table-name conn)
        ;; Collect MV field names from subgrids to mark as :hidden
        mv-field-ids (into #{} (keep :multivalue-field subgrids))
        ;; Mark MV fields as :hidden (managed via subgrids, not edited directly)
        fields-final (mapv (fn [f]
                             (if (mv-field-ids (:id f))
                               (-> f
                                   (assoc :type :hidden)
                                   (dissoc :placeholder :required?))
                               f))
                           fields-with-display)
        queries (generate-queries table-name fks conn)]

    (cond-> {:entity (keyword table-name)
             :title table-title
             :table (name table-name)
             :connection conn
             :rights rights
             :mode :parameter-driven

             :fields fields-final

             :queries queries

             :actions {:new true :edit true :delete true}}

      ;; Child entities: hide from menu
      is-child? (assoc :menu-hidden? true)
      ;; Top-level entities: add menu category
      (not is-child?) (assoc :menu-category (keyword (humanize-label table-name)))
      ;; Add subgrids if any tables reference this one
      (seq subgrids) (assoc :subgrids subgrids)

      ;; Auto-enable hooks when entity has :file type fields
      (some #(= :file (:type %)) fields-final)
      (assoc :hooks {:after-load (keyword (str "pickgen.hooks." (name table-name)) "after-load")
                     :before-save (keyword (str "pickgen.hooks." (name table-name)) "before-save")}))))

(defn generate-hook-stub
  "Generates a hook stub file for an entity"
  [entity-name & [fields]]
  (let [file-fields (filter #(= :file (:type %)) fields)
        has-file-fields? (seq file-fields)
        file-field-names (map :id file-fields)]
    (str "(ns pickgen.hooks." entity-name "\n"
         "  \"Business logic hooks for " entity-name " entity.\n"
         "   \n"
         "   SENIOR DEVELOPER: Implement custom business logic here.\n"
         "   \n"
         "   See: HOOKS_GUIDE.md for detailed documentation and examples.\n"
         "   Example: " (hooks-path) "alquileres.clj\n"
         "   \n"
         "   Uncomment the hooks you need and implement the logic.\""
         (when has-file-fields? "\n  (:require [pickgen.models.util :refer [image-link]])") ")\n"
         "\n"
         ";; =============================================================================\n"
         ";; Validators\n"
         ";; =============================================================================\n"
         "\n"
         ";; Example validator function:\n"
         ";; (defn validate-dates\n"
         ";;   \"Validates that end date is after start date\"\n"
         ";;   [params]\n"
         ";;   (let [start (:start_date params)\n"
         ";;         end (:end_date params)]\n"
         ";;     (when (and start end)\n"
         ";;       ;; Add your validation logic here\n"
         ";;       nil)))  ; Return nil if valid, or {:field \"error message\"}\n"
         "\n"
         ";; =============================================================================\n"
         ";; Computed Fields\n"
         ";; =============================================================================\n"
         "\n"
         ";; Example computed field:\n"
         ";; (defn compute-total\n"
         ";;   \"Computes total from quantity and price\"\n"
         ";;   [row]\n"
         ";;   (* (or (:quantity row) 0)\n"
         ";;      (or (:price row) 0)))\n"
         "\n"
         ";; =============================================================================\n"
         ";; Lifecycle Hooks\n"
         ";; =============================================================================\n"
         "\n"
         "(defn before-load\n"
         "  \"Hook executed before loading records.\n"
         "   \n"
         "   Use cases:\n"
         "   - Filter by user permissions\n"
         "   - Add default filters\n"
         "   - Log access\n"
         "   \n"
         "   Args: [params] - Query parameters\n"
         "   Returns: Modified params map\"\n"
         "  [params]\n"
         "  ;; TODO: Add your logic here\n"
         "  (println \"[INFO] Loading " entity-name " with params:\" params)\n"
         "  params)\n"
         "\n"
         "(defn after-load\n"
         "  \"Hook executed after loading records.\n"
         "   \n"
         "   Use cases:\n"
         "   - Add computed fields\n"
         "   - Format data\n"
         "   - Enrich with lookups\n"
         "   \n"
         "   Args: [rows params] - Loaded rows and query params\n"
         "   Returns: Modified rows vector\"\n"
         "  [rows params]\n"
         "  (println \"[INFO] Loaded\" (count rows) \"" entity-name " record(s)\")\n"
         (if has-file-fields?
           (str "  ;; Transform file fields to image links\n"
                "  (map #(-> %\n"
                (apply str (map (fn [field-id]
                                  (str "            (assoc :" (name field-id) " (image-link (:" (name field-id) " %)))\n"))
                                file-field-names))
                "        ) rows))\n")
           (str "  ;; TODO: Add your transformations here, then return the result\n"
                "  ;; Example: (map #(assoc % :full-name (str (:first-name %) \" \" (:last-name %))) rows)\n"
                "  rows)\n"))
         "\n"
         "(defn before-save\n"
         "  \"Hook executed before saving a record.\n"
         "   \n"
         "   Use cases:\n"
         "   - Validate data\n"
         "   - Set defaults\n"
         "   - Transform values\n"
         "   - Check permissions\n"
         "   \n"
         "   Args: [params] - Form data to be saved\n"
         "   Returns: Modified params map OR {:errors {...}} if validation fails\"\n"
         "  [params]\n"
         "  (println \"[INFO] Saving " entity-name "...\")\n"
         (if has-file-fields?
           (let [first-file-field (first file-field-names)]
             (str "\n"
                  "  ;; Handle file upload for " (name first-file-field) " field\n"
                  "  ;; The system expects :file key, but our field is named :" (name first-file-field) "\n"
                  "  (if-let [file-data (:" (name first-file-field) " params)]\n"
                  "    (if (and (map? file-data) (:tempfile file-data))\n"
                  "      ;; It's a file upload - move it to :file key so build-form-save finds it\n"
                  "      (-> params\n"
                  "          (assoc :file file-data)\n"
                  "          (dissoc :" (name first-file-field) "))\n"
                  "      ;; It's already a string (existing filename) - keep as is\n"
                  "      params)\n"
                  "    params))\n"))
           "  ;; TODO: Add validation and transformation logic\n  params)\n")
         "\n"
         "(defn after-save\n"
         "  \"Hook executed after successfully saving a record.\n"
         "   \n"
         "   Use cases:\n"
         "   - Send notifications\n"
         "   - Update related records\n"
         "   - Create audit logs\n"
         "   - Trigger workflows\n"
         "   \n"
         "   Args: [entity-id params] - Saved record ID and data\n"
         "   Returns: {:success true}\"\n"
         "  [entity-id params]\n"
         "  ;; TODO: Add post-save logic\n"
         "  (println \"[INFO] " (str/capitalize entity-name) " saved successfully. ID:\" entity-id)\n"
         "  {:success true})\n"
         "\n"
         "(defn before-delete\n"
         "  \"Hook executed before deleting a record.\n"
         "   \n"
         "   Use cases:\n"
         "   - Check for related records\n"
         "   - Verify permissions\n"
         "   - Prevent deletion if constraints\n"
         "   \n"
         "   Args: [entity-id] - ID of record to delete\n"
         "   Returns: {:success true} to allow, or {:errors {...}} to prevent\"\n"
         "  [entity-id]\n"
         "  ;; TODO: Add pre-delete checks\n"
         "  (println \"[INFO] Checking if " entity-name " can be deleted. ID:\" entity-id)\n"
         "  {:success true})\n"
         "\n"
         "(defn after-delete\n"
         "  \"Hook executed after successfully deleting a record.\n"
         "   \n"
         "   Use cases:\n"
         "   - Delete related files\n"
         "   - Update related records\n"
         "   - Send notifications\n"
         "   - Archive data\n"
         "   \n"
         "   Args: [entity-id] - ID of deleted record\n"
         "   Returns: {:success true}\"\n"
         "  [entity-id]\n"
         "  ;; TODO: Add post-delete logic\n"
         "  (println \"[INFO] " (str/capitalize entity-name) " deleted successfully. ID:\" entity-id)\n"
         "  {:success true})\n")))

(defn write-hook-stub
  "Writes hook stub file. Overwrites when force? is true, skips with message otherwise."
  [entity-name fields & {:keys [force?] :or {force? false}}]
  (let [filename (str (hooks-path) entity-name ".clj")
        file (io/file filename)]
    (if (and (.exists file) (not force?))
      (println (str "   Hook file already exists, skipping: " filename))
      (do
        (io/make-parents file)
        (spit file (generate-hook-stub entity-name fields))
        (println (str "   Generated hook stub: " filename))
        (println (str "      Senior developer: Implement business logic here"))))))

(defn format-field
  "Formats a field configuration for EDN output"
  [field]
  (let [base (str "\n          {:id " (:id field)
                  " :label \"" (:label field) "\""
                  " :type " (:type field))]
    (str base
         (when (:required? field) " :required? true")
         (when (:placeholder field) (str " :placeholder \"" (:placeholder field) "\""))
         (when (:options field) " :options []")
         (when (:hidden-in-grid? field) " :hidden-in-grid? true")
         (when (:grid-only? field) " :grid-only? true")
         "}"
         (when (:foreign-key field)
           (str " ;; FK: " (get-in field [:foreign-key :table]))))))

(defn format-subgrid
  "Formats a subgrid configuration for EDN output"
  [subgrid]
  (str "\n            {:entity " (:entity subgrid)
       "\n             :title \"" (:title subgrid) "\""
       (if (:multivalue-field subgrid)
         (str "\n             :multivalue-field " (:multivalue-field subgrid))
         (str "\n             :foreign-key " (:foreign-key subgrid)))
       (when (seq (:display-fields subgrid))
         (str "\n             :display-fields " (pr-str (vec (:display-fields subgrid)))))
       "\n             :icon \"" (:icon subgrid) "\""
       "\n             :label \"" (:label subgrid) "\"}"))

(defn generate-edn-content
  "Generates EDN file content with nice formatting and comments"
  [config]
  (let [entity-name (name (:entity config))
        has-subgrids? (seq (:subgrids config))]
    (str ";; AUTO-GENERATED entity configuration for " (:table config) "\n"
         ";; Generated: " (java.time.LocalDateTime/now) "\n"
         ";; \n"
         ";; This file was scaffolded from the database schema.\n"
         ";; Feel free to edit and customize as needed.\n"
         ";; \n\n"

         "{:entity " (:entity config) "\n"
         " :title \"" (:title config) "\"  ;; Edit this to your preference\n"
         " :table \"" (:table config) "\"\n"
         " :connection " (:connection config) "\n"
         " :rights " (vec (:rights config)) "  ;; User levels: U=User, A=Admin, S=System\n"
         " :mode " (:mode config) "\n"
         (if (:menu-hidden? config)
           " :menu-hidden? true  ;; Hide from main menu (child entity accessed via subgrids)\n"
           (str " :menu-category " (:menu-category config) "\n"))
         " \n"
         " ;; Fields auto-detected from database schema\n"
         " :fields ["
         (str/join "" (map format-field (:fields config)))
         "]\n"
         " \n"
         " ;; Queries — pickdict dictionary-aware or raw SQL\n"
         " ;; :pickdict/list and :pickdict/get auto-resolve T-dictionary translations\n"
         " :queries {:list "
         (let [list-q (get-in config [:queries :list])
               get-q  (get-in config [:queries :get])]
           (if (keyword? list-q)
             (str (pr-str list-q) "\n           :get " (pr-str get-q))
             (str "\"" list-q "\"\n           :get \"" get-q "\"")))
         "}\n"
         " \n"
         " ;; Available actions\n"
         " :actions {:new " (get-in config [:actions :new])
         " :edit " (get-in config [:actions :edit])
         " :delete " (get-in config [:actions :delete]) "}\n"
         " \n"
         " ;; Enable audit trail (tracks who created/modified and when)\n"
         " ;; Uncomment to enable:\n"
         " ;; :audit? true\n"
         " \n"
         " ;; Lifecycle hooks for business logic\n"
         " ;; Implement in " (hooks-path) entity-name ".clj\n"
         (let [hooks (:hooks config)
               base-ns (str "pickgen.hooks." entity-name)
               all-hook-keys [:before-load :after-load :before-save :after-save :before-delete :after-delete]
               active-keys (set (keys hooks))
               active-hooks (filter #(contains? active-keys %) all-hook-keys)
               inactive-hooks (remove #(contains? active-keys %) all-hook-keys)]
           (if (seq active-hooks)
             (str " :hooks {"
                  (str/join "\n" (map-indexed
                                  (fn [i k]
                                    (let [prefix (if (zero? i) "" "          ")]
                                      (str prefix ":" (name k) " :" base-ns "/" (name k))))
                                  active-hooks))
                  "}\n"
                  (when (seq inactive-hooks)
                    (str " ;; Additional hooks (uncomment as needed):\n"
                         (str/join "\n" (map (fn [k]
                                               (str " ;;   " (name k) " :" base-ns "/" (name k)))
                                             inactive-hooks))
                         "\n")))
             (str " ;; Uncomment and implement as needed:\n"
                  " ;; :hooks {:before-load :" base-ns "/before-load\n"
                  " ;;         :after-load :" base-ns "/after-load\n"
                  " ;;         :before-save :" base-ns "/before-save\n"
                  " ;;         :after-save :" base-ns "/after-save\n"
                  " ;;         :before-delete :" base-ns "/before-delete\n"
                  " ;;         :after-delete :" base-ns "/after-delete}\n")))

         ;; Add subgrids if they exist
         (if has-subgrids?
           (str " \n"
                " ;; Subgrids for related tables (auto-detected from T-dictionaries)\n"
                " :subgrids ["
                (str/join "" (map format-subgrid (:subgrids config)))
                "]}\n")
           ;; No subgrids - show examples
           (str " \n"
                " ;; Subgrids (parent-child relationships via Pick/D3 multivalue fields)\n"
                " ;; Uncomment if this entity has child records:\n"
                " ;; :subgrids [{:entity :related-table\n"
                " ;;             :title \"Related Records\"\n"
                " ;;             :multivalue-field :related_ids\n"
                " ;;             :icon \"bi bi-list\"\n"
                " ;;             :label \"Related Records\"}]\n"
                "}\n")))))

(defn write-entity-config
  "Writes entity configuration to file. Overwrites when force? is true, skips with message otherwise."
  [config & {:keys [force?] :or {force? false}}]
  (let [filename (str "resources/entities/" (name (:entity config)) ".edn")
        file (io/file filename)]

    (if (and (.exists file) (not force?))
      (do
        (println (str "   Entity config already exists, skipping: " filename))
        nil)
      (do
        (io/make-parents file)
        (spit file (generate-edn-content config))
        filename))))

(defn scaffold-table
  "Scaffolds a single table"
  [table-name & {:keys [conn rights title force? with-hooks?]
                 :or {conn :default
                      rights ["U" "A" "S"]
                      title nil
                      force? false
                      with-hooks? true}}]
  (try
    (println (str "Scaffolding " table-name "..."))

    (let [config (generate-entity-config table-name
                                         :conn conn
                                         :rights rights
                                         :title title)
          filename (write-entity-config config :force? force?)
          field-count (count (:fields config))
          subgrid-count (count (:subgrids config))]

      (if filename
        (do
          (println (str "Generated " filename))
          (println (str "   - " field-count " fields detected"))
          (when (pos? subgrid-count)
            (println (str "   - " subgrid-count " subgrid(s) detected")))
          (println (str "   - Default queries created"))

          ;; Generate hook stub file
          (when with-hooks?
            (write-hook-stub (name table-name) (:fields config) :force? force?))

          (println)
          (println "JUNIOR DEVELOPER - Next steps:")
          (println (str "  1. Edit " filename))
          (println (str "     - Customize field labels"))
          (println (str "     - Mark required fields"))
          (println (str "     - Test at: /admin/" (name table-name)))
          (println)
          (println "SENIOR DEVELOPER - When needed:")
          (println (str "  2. Implement hooks in: " (hooks-path) (name table-name) ".clj"))
          (println (str "  3. Uncomment :hooks in " filename))
          (println (str "  4. See: HOOKS_GUIDE.md for examples"))
          (println))

        ;; Entity was skipped, still check hooks
        (when with-hooks?
          (write-hook-stub (name table-name) (:fields config) :force? force?))))

    (catch Exception e
      (println (str "Failed to scaffold " table-name ": " (.getMessage e)))
      (.printStackTrace e))))

(defn scaffold-all
  "Scaffolds all tables in database"
  [& {:keys [conn exclude force? with-hooks?]
      :or {conn :default
           exclude []
           force? false
           with-hooks? true}}]
  (let [tables (get-all-tables conn)
        excluded-set (set (map keyword exclude))
        tables-to-scaffold (remove excluded-set tables)]

    (println (str "Found " (count tables) " tables"))
    (println (str "Scaffolding " (count tables-to-scaffold) " tables..."))
    (println)

    (doseq [table tables-to-scaffold]
      (scaffold-table table :conn conn :force? force? :with-hooks? with-hooks?))

    (println (str "Scaffolded " (count tables-to-scaffold) " entities"))
    (println)
    (println "All entity configurations created!")
    (println "Review and customize them in resources/entities/")
    (when with-hooks?
      (println (str "Hook stubs created in " (hooks-path))))))

(defn print-usage
  "Prints usage information"
  []
  (println "Scaffold - Generate entity configurations from database tables")
  (println)
  (println "Usage:")
  (println "  lein scaffold <table>              # Scaffold single table")
  (println "  lein scaffold <table> --force      # Overwrite existing config")
  (println "  lein scaffold <table> --no-hooks   # Skip hook stub generation")
  (println "  lein scaffold --all                # Scaffold all tables")
  (println "  lein scaffold --all --exclude sessions,migrations")
  (println)
  (println "Options:")
  (println "  --conn <key>        Database connection (:default, :pg, :localdb)")
  (println "  --rights [U A S]    User rights (default: U A S)")
  (println "  --title \"Title\"     Custom title")
  (println "  --force             Overwrite existing config")
  (println "  --no-hooks          Skip hook stub generation")
  (println "  --all               Scaffold all tables")
  (println "  --exclude a,b,c     Tables to exclude (with --all)")
  (println)
  (println "Examples:")
  (println "  lein scaffold products")
  (println "  lein scaffold users --rights [A S] --title \"User Management\"")
  (println "  lein scaffold orders --no-hooks")
  (println "  lein scaffold --all --exclude sessions,schema_migrations")
  (println)
  (println "What gets generated:")
  (println "  Entity EDN config in resources/entities/")
  (println (str "  Hook stub file in " (hooks-path) " (unless --no-hooks)"))
  (println "  Auto-detected fields, foreign keys, subgrids")
  (println "  Junior/Senior handoff comments")
  (println))

(defn -main
  "Main entry point for scaffold command"
  [& args]
  (if (empty? args)
    (print-usage)
    (let [args-vec (vec args)
          all? (some #{"--all"} args)
          force? (some #{"--force"} args)
          with-hooks? (not (some #{"--no-hooks"} args))
          conn-idx (.indexOf args-vec "--conn")
          conn (if (>= conn-idx 0)
                 (keyword (get args-vec (inc conn-idx)))
                 :default)
          rights-idx (.indexOf args-vec "--rights")
          rights (if (>= rights-idx 0)
                   (read-string (get args-vec (inc rights-idx)))
                   ["U" "A" "S"])
          title-idx (.indexOf args-vec "--title")
          title (if (>= title-idx 0)
                  (get args-vec (inc title-idx))
                  nil)
          exclude-idx (.indexOf args-vec "--exclude")
          exclude (if (>= exclude-idx 0)
                    (str/split (get args-vec (inc exclude-idx)) #",")
                    [])
          table-name (when-not all?
                       (first (remove #(str/starts-with? % "--") args)))]

      (if all?
        (scaffold-all :conn conn :exclude exclude :force? force? :with-hooks? with-hooks?)
        (if table-name
          (scaffold-table (keyword table-name)
                          :conn conn
                          :rights rights
                          :title title
                          :force? force?
                          :with-hooks? with-hooks?)
          (print-usage))))))

(comment
  ;; Usage examples
  (scaffold-table :products)
  (scaffold-table :users :rights ["A" "S"] :title "User Management")
  (scaffold-all)
  (scaffold-all :exclude [:sessions :schema_migrations])

  ;; Test introspection
  (get-all-tables :default)
  (get-table-columns :users :default)
  (get-foreign-keys :orders :default))
