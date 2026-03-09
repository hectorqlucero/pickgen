# Pickgen

A data-driven Clojure CRUD application template using the **Pick/D3 multivalue database philosophy** — no foreign keys, parent records store child IDs as delimited lists.

Uses [pickdict](https://clojars.org/org.clojars.hector/pickdict) for database operations and SQLite as the database.

Includes a working demo app (contacts, siblings, cars) to show how everything fits together. Run `init.sh` to rename it to your own project.

---

## Table of Contents

- [Quick Start](#quick-start-new-project)
- [Prerequisites](#prerequisites)
- [Login Accounts](#login-accounts)
- [Commands](#commands)
- [How the App Works](#how-the-app-works)
- [Project Structure](#project-structure)
- [Entity Configuration Cheat Sheet](#entity-configuration-cheat-sheet)
- [Field Types Cheat Sheet](#field-types-cheat-sheet)
- [Field Options Cheat Sheet](#field-options-cheat-sheet)
- [Foreign Key (FK) Fields](#foreign-key-fk-fields)
- [Queries Cheat Sheet](#queries-cheat-sheet)
- [Actions Cheat Sheet](#actions-cheat-sheet)
- [Subgrids Cheat Sheet](#subgrids-cheat-sheet)
- [Hooks Cheat Sheet](#hooks-cheat-sheet)
- [Scaffold Command](#scaffold-command)
- [How to Add a New Entity (Step by Step)](#how-to-add-a-new-entity-step-by-step)
- [Complete Entity Examples](#complete-entity-examples)
- [Complete Hook Examples](#complete-hook-examples)
- [Menu System](#menu-system)
- [Configuration (app-config.edn)](#configuration-app-configedn)
- [i18n (Translations)](#i18n-translations)
- [URL Routing](#url-routing)
- [Pick/D3 Multivalue Philosophy](#pickd3-multivalue-philosophy)
- [License](#license)

---

## Quick Start (New Project)

```bash
# 1. Clone the template
git clone <repo-url> my-app
cd my-app

# 2. Rename to your project (replaces all namespaces, configs, directories)
./init.sh my-app

# 3. Create the database
lein migrate

# 4. Seed default user accounts
lein database

# 5. Start the app
lein run
```

Open **http://localhost:3000** — log in with `admin@example.com` / `admin`.

The demo entities (contacts, siblings, cars) are included as working examples. You can modify or remove them and add your own entities.

---

## Prerequisites

- **Java** 11 or newer (`java -version`)
- **Leiningen** ([install guide](https://leiningen.org/#install))

That's it. No other databases or services needed — everything runs on SQLite.

---

## Login Accounts

After running `lein database`, three accounts are available:

| Email               | Password | Role          |
|---------------------|----------|---------------|
| user@example.com    | user     | User (U)      |
| admin@example.com   | admin    | Admin (A)     |
| system@example.com  | system   | System (S)    |

Roles control what a user can do:
- **U** — Read only
- **A** — Create, read, update, delete
- **S** — Everything (full access)

---

## Commands

| Command            | What it does                                          |
|--------------------|-------------------------------------------------------|
| `lein migrate`     | Creates all tables, dictionaries, and views (safe to run multiple times) |
| `lein database`    | Seeds the default user accounts                       |
| `lein run`         | Starts the web server on port 3000                    |
| `lein test`        | Runs the test suite                                   |
| `lein check`       | Checks for compilation errors                         |
| `lein scaffold`    | Generates entity config + hook files from database    |

---

## How the App Works

### Everything is Driven by EDN Config Files

Each entity (users, contactos, siblings, cars) has a `.edn` file under `resources/entities/`. These files control:

- What table to query
- What fields to show in forms
- What queries to run for listing and fetching
- What actions are allowed (new, edit, delete)
- What subgrids (child records) to display

The engine reads these files at runtime. You don't write route handlers or HTML templates for each entity — the engine generates everything from the EDN config.

### Pick/D3 Multivalue Philosophy (No Foreign Keys)

This app does **not** use foreign keys. Instead, it follows the Pick/D3 database model:

1. **Parent records store child IDs.** The `contactos` table has `sibling_ids` and `car_ids` columns (TEXT type). These store multiple IDs separated by the `]` character.

   Example: A contact with 3 cars has `car_ids = "1]4]7"`.

2. **Child tables are standalone.** The `siblings` and `cars` tables have no `contacto_id` column. They don't know which contact they belong to.

3. **T-dictionaries translate IDs.** When the app needs to show a contact's car companies, it looks up `contactos_DICT.CAR_COMPANIES`, which is a T-type (translation) dictionary that maps `car_ids` → `cars.COMPANY`.

When you add a car through a contact's subgrid:
- The car is inserted into the `cars` table
- The new car's ID is appended to the contact's `car_ids` field

When you delete a car from a contact's subgrid:
- The car is deleted from the `cars` table
- That ID is removed from the contact's `car_ids` field

---

## Project Structure

```
pickgen/
├── project.clj                  # Dependencies and build config
├── resources/
│   ├── config/
│   │   ├── app-config.edn       # App settings (port, themes, security, DB)
│   │   └── messages.edn         # UI messages
│   ├── entities/                 # Entity definitions (one .edn per entity)
│   │   ├── users.edn
│   │   ├── contactos.edn
│   │   ├── siblings.edn
│   │   └── cars.edn
│   ├── i18n/                     # Translations (en.edn, es.edn)
│   └── public/                   # Static assets (CSS, JS, images)
├── src/pickgen/
│   ├── core.clj                  # App entry point, middleware, Jetty server
│   ├── layout.clj                # HTML layout (Hiccup templates)
│   ├── menu.clj                  # Navigation menu
│   ├── migrations.clj            # Schema creation (pickdict create-file!)
│   ├── config/
│   │   └── loader.clj            # Reads app-config.edn
│   ├── engine/
│   │   ├── config.clj            # Entity config loading and validation
│   │   └── router.clj            # CRUD route handlers (save, delete, list)
│   ├── hooks/                    # Lifecycle hooks (before-save, after-load, etc.)
│   │   ├── cars.clj
│   │   ├── contactos.clj
│   │   ├── siblings.clj
│   │   └── users.clj
│   ├── models/
│   │   ├── crud.clj              # Core DB operations (save, delete, query)
│   │   └── cdb.clj               # DB connection + user seeding
│   ├── routes/                   # Route definitions
│   ├── scaffold/                 # Entity code generator
│   └── tabgrid/                  # Subgrid UI (data loading, rendering)
│       ├── data.clj              # Fetches subgrid records (MV field logic)
│       ├── handlers.clj          # Subgrid AJAX handlers
│       └── render.clj            # Subgrid HTML rendering
├── dev/pickgen/
│   └── dev.clj                   # Dev profile with auto-reload
├── db/                           # SQLite database file lives here
├── uploads/                      # File uploads directory
└── test/                         # Tests
```

---

## Entity Configuration Cheat Sheet

Entity configs live in `resources/entities/<name>.edn`. Every key you can use:

| Key | Type | Required? | What it does |
|-----|------|-----------|--------------|
| `:entity` | keyword | **Yes** | Unique entity identifier (e.g. `:cars`) |
| `:title` | string | **Yes** | Display title shown in UI (e.g. `"Cars"`) |
| `:table` | string | **Yes** | Database table name (e.g. `"cars"`) |
| `:connection` | keyword | No | DB connection key (default: `:default`) |
| `:rights` | vector | No | Who can see this: `["U" "A" "S"]` |
| `:mode` | keyword | No | `:parameter-driven`, `:generated`, or `:hybrid` |
| `:fields` | vector | No | Field definitions (see Field Types below) |
| `:queries` | map | No | How to fetch records (see Queries below) |
| `:actions` | map | No | Which CRUD buttons to show (see Actions below) |
| `:hooks` | map | No | Lifecycle functions (see Hooks below) |
| `:subgrids` | vector | No | Child record tabs (see Subgrids below) |
| `:menu-category` | keyword | No | Menu group (e.g. `:Contactos`, `:system`) |
| `:menu-hidden?` | boolean | No | `true` = hide from menu (for child entities) |
| `:menu-order` | number | No | Sort order in menu (default: 999) |
| `:audit?` | boolean | No | `true` = track created_by/at, modified_by/at |
| `:ui` | map | No | Custom rendering functions (advanced) |

### Minimal Entity Config

```clojure
{:entity :products
 :title "Products"
 :table "products"
 :fields [{:id :id :label "ID" :type :hidden}
          {:id :name :label "Name" :type :text}
          {:id :price :label "Price" :type :decimal}]
 :queries {:list :pickdict/list
           :get :pickdict/get}
 :actions {:new true :edit true :delete true}}
```

---

## Field Types Cheat Sheet

Every field type you can use in `:fields`:

| Type | HTML Element | Use for | Example |
|------|-------------|---------|---------|
| `:text` | `<input type="text">` | Short text | Name, code, phone |
| `:textarea` | `<textarea>` | Long text | Notes, description |
| `:number` | `<input type="number">` | Integers | Age, quantity |
| `:decimal` | `<input type="number" step="0.01">` | Money, floats | Price, weight |
| `:email` | `<input type="email">` | Email addresses | user@example.com |
| `:password` | `<input type="password">` | Passwords (masked) | Login password |
| `:date` | `<input type="date">` | Dates | Birth date, due date |
| `:datetime` | `<input type="datetime-local">` | Date + time | Appointment, timestamp |
| `:select` | `<select>` dropdown | Pick one option | Status, category |
| `:radio` | Radio buttons | Pick one option (visible) | Active/Inactive |
| `:checkbox` | `<input type="checkbox">` | True/false toggle | Is active? |
| `:file` | `<input type="file">` | File uploads | Photo, PDF |
| `:hidden` | `<input type="hidden">` | Not shown to user | ID, multivalue IDs |
| `:computed` | Read-only text | Calculated values | Total, full name |
| `:fk` | Dynamic `<select>` | Foreign key lookup | Select a category |

### Examples of Every Field Type

```clojure
:fields [
  ;; Hidden — always include :id as hidden
  {:id :id :label "ID" :type :hidden}

  ;; Text — short single-line input
  {:id :phone :label "Phone" :type :text :placeholder "555-1234"}

  ;; Textarea — multi-line text
  {:id :notes :label "Notes" :type :textarea :placeholder "Enter notes..."}

  ;; Number — integers only
  {:id :age :label "Age" :type :number}

  ;; Decimal — floats/money
  {:id :price :label "Price" :type :decimal :placeholder "0.00"}

  ;; Email — with built-in validation
  {:id :email :label "Email" :type :email :placeholder "user@example.com"}

  ;; Password — masked input
  {:id :password :label "Password" :type :password}

  ;; Date — date picker
  {:id :birth_date :label "Birth Date" :type :date}

  ;; Datetime — date + time picker
  {:id :appointment :label "Appointment" :type :datetime}

  ;; Select — dropdown with static options
  {:id :status :label "Status" :type :select
   :options [{:value "active" :label "Active"}
             {:value "inactive" :label "Inactive"}
             {:value "pending" :label "Pending"}]}

  ;; Radio — radio buttons with static options
  {:id :priority :label "Priority" :type :radio
   :options [{:value "low" :label "Low"}
             {:value "medium" :label "Medium"}
             {:value "high" :label "High"}]}

  ;; Checkbox — true/false toggle
  {:id :is_active :label "Active?" :type :checkbox}

  ;; File — image/PDF upload
  {:id :imagen :label "Photo" :type :file}

  ;; Computed — read-only, calculated in hooks
  {:id :full_name :label "Full Name" :type :computed}

  ;; FK — dynamic select populated from another entity
  {:id :category_id :label "Category" :type :fk
   :fk :categories
   :fk-field [:name]
   :fk-sort :name}

  ;; Hidden multivalue field (for parent-child subgrids)
  {:id :car_ids :label "Car Ids" :type :hidden}]
```

---

## Field Options Cheat Sheet

Every option you can put on a field:

| Option | Type | Works with | What it does |
|--------|------|-----------|--------------|
| `:id` | keyword | **All** | Column name in database (required) |
| `:label` | string | **All** | Label shown in forms and grids (required) |
| `:type` | keyword | **All** | Field type from table above (required) |
| `:required?` | boolean | All | Shows red `*`, validation enforced |
| `:placeholder` | string | text, textarea, email, number, decimal | Ghost text in empty input |
| `:options` | vector | select, radio | List of `{:value "v" :label "L"}` maps |
| `:value` | any | All | Default value for new records |
| `:hidden-in-grid?` | boolean | All | Hide from grid/list, still in forms |
| `:hidden-in-form?` | boolean | All | Hide from forms, still in grid |
| `:grid-only?` | boolean | All | Show only in grid, exclude from forms |
| `:validation` | fn/keyword | All | Custom validation: `(fn [value data] -> bool)` |
| `:compute-fn` | fn/keyword | computed | Function: `(fn [row] -> value)` |

### Examples

```clojure
;; Required field with placeholder
{:id :name :label "Name" :type :text :required? true :placeholder "Full name..."}

;; Hidden in grid (too long for table), visible in form
{:id :description :label "Description" :type :textarea :hidden-in-grid? true}

;; Only visible in grid, not in add/edit forms
{:id :created_at :label "Created" :type :text :grid-only? true}

;; Default value for new records
{:id :status :label "Status" :type :select :value "active"
 :options [{:value "active" :label "Active"}
           {:value "inactive" :label "Inactive"}]}

;; Required email
{:id :email :label "Email" :type :email :required? true :placeholder "user@example.com"}
```

---

## Foreign Key (FK) Fields

Use `:type :fk` to create a dropdown that loads options from another entity.

| FK Option | Type | What it does |
|-----------|------|--------------|
| `:fk` | keyword | Target entity (e.g. `:categories`) |
| `:fk-field` | vector of keywords | Fields to display from target (e.g. `[:name]`) |
| `:fk-separator` | string | Separator between display fields (default `" — "`) |
| `:fk-sort` | keyword or vector | Sort FK options by field(s) |
| `:fk-filter` | vector | Filter FK options: `[:active "true"]` |
| `:fk-parent` | keyword | Parent FK field for dependent/cascading selects |
| `:fk-can-create?` | boolean | Allow creating new FK record inline |
| `:fk-form-fields` | vector of keywords | Fields for inline FK creation form |

### FK Examples

```clojure
;; Simple FK — dropdown of category names
{:id :category_id :label "Category" :type :fk
 :fk :categories
 :fk-field [:name]}

;; FK with multiple display fields
{:id :product_id :label "Product" :type :fk
 :fk :products
 :fk-field [:name :sku]
 :fk-separator " — "}

;; FK sorted and filtered
{:id :agent_id :label "Agent" :type :fk
 :fk :agents
 :fk-field [:name]
 :fk-sort :name
 :fk-filter [:active "true"]}

;; Dependent FK — city depends on state selection
{:id :state_id :label "State" :type :fk
 :fk :states
 :fk-field [:name]}
{:id :city_id :label "City" :type :fk
 :fk :cities
 :fk-field [:name]
 :fk-parent :state_id}

;; FK with inline creation
{:id :brand_id :label "Brand" :type :fk
 :fk :brands
 :fk-field [:name]
 :fk-can-create? true
 :fk-form-fields [:name]}
```

---

## Queries Cheat Sheet

The `:queries` map controls how records are fetched.

| Key | What it does |
|-----|--------------|
| `:list` | Fetch all records (for the grid/list view) |
| `:get` | Fetch a single record by ID (for the edit form) |

### Query Value Types

| Type | Example | When to use |
|------|---------|-------------|
| `:pickdict/list` | `:pickdict/list` | **Recommended.** Auto-resolves T-dictionary translations |
| `:pickdict/get` | `:pickdict/get` | **Recommended.** Fetches single record with translations |
| SQL string | `"SELECT * FROM products ORDER BY name"` | Custom SQL queries |
| Qualified keyword | `:myapp.queries/custom-list` | Call a function you write |

### Examples

```clojure
;; Pickdict queries (recommended — auto-resolves T-dictionaries)
:queries {:list :pickdict/list
          :get :pickdict/get}

;; Custom SQL queries
:queries {:list "SELECT * FROM products ORDER BY name ASC"
          :get  "SELECT * FROM products WHERE id = ?"}

;; SQL with JOIN (for legacy FK relationships)
:queries {:list "SELECT p.*, c.name as category_name
                 FROM products p
                 LEFT JOIN categories c ON p.category_id = c.id
                 ORDER BY p.name"
          :get  "SELECT * FROM products WHERE id = ?"}
```

---

## Actions Cheat Sheet

The `:actions` map controls which CRUD buttons appear.

```clojure
;; All actions enabled (default)
:actions {:new true :edit true :delete true}

;; Read-only (no create/edit/delete buttons)
:actions {:new false :edit false :delete false}

;; Can add and edit, but not delete
:actions {:new true :edit true :delete false}
```

| Key | What it does |
|-----|--------------|
| `:new` | Show "New" button to create records |
| `:edit` | Show "Edit" button on each row |
| `:delete` | Show "Delete" button on each row |

---

## Subgrids Cheat Sheet

Subgrids show child records as tabs on a parent entity's detail page. They use the Pick/D3 multivalue field pattern.

### All Subgrid Keys

| Key | Type | Required? | What it does |
|-----|------|-----------|--------------|
| `:entity` | keyword | **Yes** | Child entity keyword (e.g. `:cars`) |
| `:title` | string | No | Tab panel title |
| `:multivalue-field` | keyword | **Yes*** | Parent field storing child IDs (Pick/D3 mode) |
| `:foreign-key` | keyword | **Yes*** | Child column pointing to parent (legacy FK mode) |
| `:display-fields` | vector | No | Which child fields to show in the subgrid |
| `:icon` | string | No | Bootstrap icon class (e.g. `"bi bi-list-ul"`) |
| `:label` | string | No | Tab label text |

*Use `:multivalue-field` (Pick/D3 style) **or** `:foreign-key` (legacy), not both.

### Subgrid Examples

```clojure
;; Pick/D3 style — parent stores child IDs in a TEXT column
:subgrids [{:entity :siblings
            :title "Siblings"
            :multivalue-field :sibling_ids
            :display-fields [:name :age]
            :icon "bi bi-list-ul"
            :label "Siblings"}
           {:entity :cars
            :title "Cars"
            :multivalue-field :car_ids
            :display-fields [:company :model :year]
            :icon "bi bi-list-ul"
            :label "Cars"}]

;; Legacy FK style — child table has a parent_id column
:subgrids [{:entity :order_items
            :title "Order Items"
            :foreign-key :order_id
            :display-fields [:product_name :quantity :price]
            :icon "bi bi-cart"
            :label "Items"}]
```

### Important

- The child entity (e.g. `:cars`) must have its own `.edn` config in `resources/entities/cars.edn`
- Set `:menu-hidden? true` on child entities so they don't appear in the navigation menu
- The parent entity must have a `:hidden` field for the multivalue column (e.g. `{:id :car_ids :label "Car Ids" :type :hidden}`)

---

## Hooks Cheat Sheet

Hooks let you run code before or after database operations. They live in `src/<yourproject>/hooks/<entity>.clj`.

### All Hook Types

| Hook | When it runs | Arguments | Returns |
|------|-------------|-----------|---------|
| `:before-load` | Before querying records | `[params]` | Modified params map |
| `:after-load` | After querying records | `[rows params]` | Modified rows vector |
| `:before-save` | Before INSERT/UPDATE | `[params]` | Modified params **OR** `{:errors {...}}` to abort |
| `:after-save` | After successful save | `[entity-id params]` | `{:success true}` |
| `:before-delete` | Before DELETE | `[entity-id]` | `{:success true}` to allow, `{:errors {...}}` to block |
| `:after-delete` | After successful delete | `[entity-id]` | `{:success true}` |

### How to Register Hooks in Entity Config

```clojure
;; In your .edn file, add the :hooks key
:hooks {:before-load   :myapp.hooks.products/before-load
        :after-load    :myapp.hooks.products/after-load
        :before-save   :myapp.hooks.products/before-save
        :after-save    :myapp.hooks.products/after-save
        :before-delete :myapp.hooks.products/before-delete
        :after-delete  :myapp.hooks.products/after-delete}
```

You only need to include the hooks you actually use. For example, if you only need `after-load`:

```clojure
:hooks {:after-load :myapp.hooks.products/after-load}
```

### Common Hook Patterns

**Validate before saving** — return `{:errors {...}}` to abort the save:

```clojure
(defn before-save [params]
  (if (empty? (:name params))
    {:errors {:name "Name is required"}}
    params))
```

**Set defaults before saving:**

```clojure
(defn before-save [params]
  (-> params
      (assoc :status (or (:status params) "active"))
      (assoc :created_at (str (java.time.LocalDateTime/now)))))
```

**Transform data after loading** (e.g. image URLs):

```clojure
(defn after-load [rows params]
  (map #(assoc % :imagen (image-link (:imagen %))) rows))
```

**Handle file uploads before saving:**

```clojure
(defn before-save [params]
  (if-let [file (:imagen params)]
    (if (and (map? file) (:tempfile file))
      (-> params
          (assoc :file file)
          (dissoc :imagen))
      params)
    params))
```

**Block deletion with a reason:**

```clojure
(defn before-delete [entity-id]
  (if (has-active-orders? entity-id)
    {:errors {:delete "Cannot delete: has active orders"}}
    {:success true}))
```

**Log actions:**

```clojure
(defn after-save [entity-id params]
  (println "[INFO] Record saved. ID:" entity-id)
  {:success true})

(defn after-delete [entity-id]
  (println "[INFO] Record deleted. ID:" entity-id)
  {:success true})
```

---

## Scaffold Command

Scaffold auto-generates entity configs and hook files from your database tables.

### Usage

```bash
lein scaffold <table>                            # Scaffold a single table
lein scaffold <table> --force                    # Overwrite existing files
lein scaffold <table> --no-hooks                 # Skip hook file generation
lein scaffold <table> --title "My Title"         # Custom title
lein scaffold <table> --rights '["A" "S"]'       # Custom rights
lein scaffold --all                              # Scaffold all tables
lein scaffold --all --force                      # Overwrite all existing files
lein scaffold --all --exclude sessions,migrations  # Skip specific tables
lein scaffold --all --conn localdb               # Use a specific DB connection
```

### All Options

| Option | What it does |
|--------|--------------|
| `--all` | Scaffold all database tables |
| `--force` | Overwrite existing `.edn` and hook files |
| `--no-hooks` | Skip generating hook stub files |
| `--conn <key>` | Database connection key (default: `:default`) |
| `--rights '["A" "S"]'` | User rights (default: `["U" "A" "S"]`) |
| `--title "Title"` | Custom entity title |
| `--exclude a,b,c` | Comma-separated tables to skip (with `--all`) |

### What It Generates

1. **Entity config** → `resources/entities/<table>.edn`
2. **Hook stub** → `src/<project>/hooks/<table>.clj` (unless `--no-hooks`)

### Behavior Without `--force`

- **Entity config exists** → Skips with message: `Entity config already exists, skipping: ...`
- **Hook file exists** → Skips with message: `Hook file already exists, skipping: ...`
- No errors, no stack traces — safe to run multiple times

### Behavior With `--force`

- Overwrites both entity configs and hook files

### Auto-Detection

The scaffold command automatically detects:
- **Field types** from SQL column types (TEXT → `:textarea`, INTEGER → `:number`, etc.)
- **Email fields** from column names containing `email`
- **Password fields** from column names containing `password`
- **File fields** from column names containing `image`, `photo`, `picture`
- **Phone fields** from column names containing `phone`, `tel`, `mobile`
- **Child entities** (via T-dictionaries) → sets `:menu-hidden? true`
- **Subgrids** from T-dictionary definitions
- **Menu categories** from table name patterns

---

## How to Add a New Entity (Step by Step)

### Step 1: Create the Table in Migrations

Edit `src/<yourproject>/migrations.clj` — add inside `create-tables!`:

```clojure
(when-not (table-exists? db "products")
  (println "[migrate] Creating table: products")
  (pick/create-file! db "products"
                     {:id          "INTEGER PRIMARY KEY AUTOINCREMENT"
                      :name        "TEXT"
                      :description "TEXT"
                      :price       "REAL"
                      :category    "TEXT"
                      :is_active   "INTEGER DEFAULT 1"
                      :imagen      "TEXT"
                      :created_at  "TEXT DEFAULT (datetime('now'))"}))
```

### Step 2: Run the Migration

```bash
lein migrate
```

### Step 3: Scaffold the Entity

```bash
lein scaffold products
```

This creates:
- `resources/entities/products.edn` — entity config
- `src/<yourproject>/hooks/products.clj` — hook stubs

### Step 4: Customize the Entity Config

Edit `resources/entities/products.edn`:

```clojure
{:entity :products
 :title "Products"
 :table "products"
 :connection :default
 :rights ["U" "A" "S"]
 :mode :parameter-driven
 :menu-category :Products

 :fields [{:id :id :label "ID" :type :hidden}
          {:id :name :label "Product Name" :type :text :required? true :placeholder "Product name..."}
          {:id :description :label "Description" :type :textarea :placeholder "Describe the product..." :hidden-in-grid? true}
          {:id :price :label "Price" :type :decimal :required? true :placeholder "0.00"}
          {:id :category :label "Category" :type :select
           :options [{:value "electronics" :label "Electronics"}
                     {:value "clothing" :label "Clothing"}
                     {:value "food" :label "Food"}]}
          {:id :is_active :label "Active?" :type :checkbox}
          {:id :imagen :label "Photo" :type :file}
          {:id :created_at :label "Created" :type :datetime :grid-only? true}]

 :queries {:list :pickdict/list
           :get :pickdict/get}

 :actions {:new true :edit true :delete true}

 :hooks {:after-load :myapp.hooks.products/after-load
         :before-save :myapp.hooks.products/before-save}}
```

### Step 5: Restart the App

```bash
lein run
```

The new entity appears in the menu automatically at `/admin/products`.

### Adding a Parent-Child Relationship

To make `products` have child `reviews`:

**1. Create the reviews table** (in migrations):

```clojure
(when-not (table-exists? db "reviews")
  (pick/create-file! db "reviews"
                     {:id      "INTEGER PRIMARY KEY AUTOINCREMENT"
                      :rating  "INTEGER"
                      :comment "TEXT"
                      :author  "TEXT"}))
```

**2. Add a multivalue field to products** (in migrations):

```clojure
;; If the table already exists, add the column:
;; ALTER TABLE products ADD COLUMN review_ids TEXT
```

**3. Add T-dictionaries** (in migrations):

```clojure
(let [dict "products_DICT"]
  (when-not (dict-field-exists? db dict "REVIEW_AUTHORS")
    (pick/define-dictionary-field db dict "REVIEW_AUTHORS" "T" "9" "Treviews;AUTHOR" "Review Authors")))
```

**4. Update products.edn** — add `:review_ids` field and subgrid:

```clojure
;; In :fields, add:
{:id :review_ids :label "Review Ids" :type :hidden}

;; Add :subgrids key:
:subgrids [{:entity :reviews
            :title "Reviews"
            :multivalue-field :review_ids
            :display-fields [:author :rating :comment]
            :icon "bi bi-star"
            :label "Reviews"}]
```

**5. Create reviews.edn** with `:menu-hidden? true`:

```clojure
{:entity :reviews
 :title "Reviews"
 :table "reviews"
 :connection :default
 :rights ["U" "A" "S"]
 :mode :parameter-driven
 :menu-hidden? true

 :fields [{:id :id :label "ID" :type :hidden}
          {:id :author :label "Author" :type :text :required? true}
          {:id :rating :label "Rating" :type :number}
          {:id :comment :label "Comment" :type :textarea}]

 :queries {:list :pickdict/list
           :get :pickdict/get}

 :actions {:new true :edit true :delete true}}
```

---

## Complete Entity Examples

### Parent Entity (with subgrids)

```clojure
;; resources/entities/contactos.edn
{:entity :contactos
 :title "Contactos"
 :table "contactos"
 :connection :default
 :rights ["U" "A" "S"]
 :mode :parameter-driven
 :menu-category :Contactos

 :fields [{:id :id :label "ID" :type :hidden}
          {:id :name :label "Name" :type :textarea :placeholder "Name..."}
          {:id :email :label "Email" :type :email :placeholder "user@example.com"}
          {:id :phone :label "Phone" :type :text :placeholder "Phone..."}
          {:id :imagen :label "Imagen" :type :file}
          {:id :sibling_ids :label "Sibling Ids" :type :hidden}
          {:id :car_ids :label "Car Ids" :type :hidden}]

 :queries {:list :pickdict/list
           :get :pickdict/get}

 :actions {:new true :edit true :delete true}

 :hooks {:after-load :pickgen.hooks.contactos/after-load
         :before-save :pickgen.hooks.contactos/before-save}

 :subgrids [{:entity :siblings
             :title "Siblings"
             :multivalue-field :sibling_ids
             :display-fields [:name :age]
             :icon "bi bi-list-ul"
             :label "Siblings"}
            {:entity :cars
             :title "Cars"
             :multivalue-field :car_ids
             :display-fields [:company :model :year]
             :icon "bi bi-list-ul"
             :label "Cars"}]}
```

### Child Entity (hidden from menu)

```clojure
;; resources/entities/cars.edn
{:entity :cars
 :title "Cars"
 :table "cars"
 :connection :default
 :rights ["U" "A" "S"]
 :mode :parameter-driven
 :menu-hidden? true

 :fields [{:id :id :label "ID" :type :hidden}
          {:id :company :label "Company" :type :textarea :placeholder "Company..."}
          {:id :model :label "Model" :type :textarea :placeholder "Model..."}
          {:id :year :label "Year" :type :number}
          {:id :imagen :label "Imagen" :type :file}]

 :queries {:list :pickdict/list
           :get :pickdict/get}

 :actions {:new true :edit true :delete true}

 :hooks {:after-load :pickgen.hooks.cars/after-load
         :before-save :pickgen.hooks.cars/before-save}}
```

### Entity with Every Feature

```clojure
;; resources/entities/orders.edn — shows all possible options
{:entity :orders
 :title "Orders"
 :table "orders"
 :connection :default
 :rights ["A" "S"]
 :mode :parameter-driven
 :menu-category :Transactions
 :menu-order 1
 :audit? true

 :fields [{:id :id :label "ID" :type :hidden}
          {:id :order_number :label "Order #" :type :text :required? true :placeholder "ORD-001"}
          {:id :customer_name :label "Customer" :type :text :required? true}
          {:id :email :label "Email" :type :email :placeholder "customer@example.com"}
          {:id :order_date :label "Date" :type :date :required? true}
          {:id :delivery_date :label "Delivery" :type :datetime}
          {:id :amount :label "Amount" :type :decimal :required? true :placeholder "0.00"}
          {:id :quantity :label "Qty" :type :number}
          {:id :status :label "Status" :type :select :value "pending"
           :options [{:value "pending" :label "Pending"}
                     {:value "processing" :label "Processing"}
                     {:value "shipped" :label "Shipped"}
                     {:value "delivered" :label "Delivered"}]}
          {:id :priority :label "Priority" :type :radio
           :options [{:value "low" :label "Low"}
                     {:value "normal" :label "Normal"}
                     {:value "urgent" :label "Urgent"}]}
          {:id :is_paid :label "Paid?" :type :checkbox}
          {:id :notes :label "Notes" :type :textarea :hidden-in-grid? true :placeholder "Internal notes..."}
          {:id :receipt :label "Receipt" :type :file}
          {:id :total :label "Total" :type :computed}
          {:id :item_ids :label "Items" :type :hidden}
          {:id :created_at :label "Created" :type :datetime :grid-only? true}]

 :queries {:list :pickdict/list
           :get :pickdict/get}

 :actions {:new true :edit true :delete true}

 :hooks {:before-load   :myapp.hooks.orders/before-load
         :after-load    :myapp.hooks.orders/after-load
         :before-save   :myapp.hooks.orders/before-save
         :after-save    :myapp.hooks.orders/after-save
         :before-delete :myapp.hooks.orders/before-delete
         :after-delete  :myapp.hooks.orders/after-delete}

 :subgrids [{:entity :order_items
             :title "Items"
             :multivalue-field :item_ids
             :display-fields [:product_name :quantity :unit_price]
             :icon "bi bi-cart"
             :label "Order Items"}]}
```

---

## Complete Hook Examples

### Hook File with All Hook Types

```clojure
;; src/<yourproject>/hooks/products.clj
(ns myapp.hooks.products
  (:require [myapp.models.util :refer [image-link]]))

(defn before-load
  "Runs before querying records. Modify query params here."
  [params]
  (println "[INFO] Loading products with params:" params)
  ;; Example: force-filter to only active products for non-admin users
  params)

(defn after-load
  "Runs after querying records. Transform rows here."
  [rows params]
  (println "[INFO] Loaded" (count rows) "product(s)")
  ;; Transform image filenames to display links
  (map #(assoc % :imagen (image-link (:imagen %))) rows))

(defn before-save
  "Runs before INSERT/UPDATE. Validate and transform here.
   Return {:errors {...}} to abort the save."
  [params]
  (println "[INFO] Saving product...")
  (cond
    ;; Validation: name is required
    (empty? (:name params))
    {:errors {:name "Product name is required"}}

    ;; Validation: price must be positive
    (and (:price params) (neg? (Double/parseDouble (str (:price params)))))
    {:errors {:price "Price must be positive"}}

    ;; Handle file upload
    :else
    (if-let [file (:imagen params)]
      (if (and (map? file) (:tempfile file))
        (-> params
            (assoc :file file)
            (dissoc :imagen))
        params)
      params)))

(defn after-save
  "Runs after a successful save."
  [entity-id params]
  (println "[INFO] Product saved. ID:" entity-id)
  {:success true})

(defn before-delete
  "Runs before DELETE. Return {:errors {...}} to block deletion."
  [entity-id]
  (println "[INFO] Checking if product can be deleted. ID:" entity-id)
  ;; Example: prevent deletion if product has orders
  ;; (if (has-orders? entity-id)
  ;;   {:errors {:delete "Cannot delete: product has associated orders"}}
  ;;   {:success true})
  {:success true})

(defn after-delete
  "Runs after a successful delete."
  [entity-id]
  (println "[INFO] Product deleted. ID:" entity-id)
  {:success true})
```

---

## Menu System

The menu auto-discovers entities from `resources/entities/` and groups them.

### How It Works

1. Scans all `.edn` files in `resources/entities/`
2. Skips entities with `:menu-hidden? true`
3. Groups by `:menu-category`
4. Sorts by `:menu-order` (default 999)
5. Only shows entities the user's role can access (`:rights`)

### Auto-Categorization (when you don't set `:menu-category`)

| Table name contains | Auto-category |
|--------------------|---------------|
| `cliente`, `client`, `customer` | `:clients` |
| `propiedad`, `property` | `:properties` |
| `alquiler`, `rental` | `:transactions` |
| `venta`, `sale` | `:transactions` |
| `contrato`, `contract` | `:transactions` |
| `pago`, `payment` | `:financial` |
| `comision`, `commission` | `:financial` |
| `documento`, `document` | `:documents` |
| `user`, `usuario` | `:system` |
| `bitacora`, `log`, `audit` | `:system` |
| *(anything else)* | `:admin` |

---

## Configuration (app-config.edn)

All settings live in `resources/config/app-config.edn`:

| Setting | Description | Default |
|---------|-------------|---------|
| `:port` | Web server port | `3000` |
| `:app :default-locale` | Language | `:es` |
| `:app :session-timeout` | Session timeout (seconds) | `28800` (8h) |
| `:app :max-file-size-mb` | Max upload size | `5` |
| `:app :pagination-size` | Records per page | `25` |
| `:ui :default-theme` | Bootswatch theme name | `"sketchy"` |
| `:security :max-login-attempts` | Lockout after N fails | `5` |
| `:security :lockout-duration` | Lockout time (seconds) | `900` (15m) |
| `:security :allowed-file-types` | Allowed uploads | `["image/jpeg" "image/png" "image/gif" "application/pdf"]` |

### Available Themes

25 Bootswatch themes included: `cerulean`, `cosmo`, `cyborg`, `darkly`, `journal`, `litera`, `lumen`, `lux`, `materia`, `minty`, `morph`, `pulse`, `quartz`, `sandstone`, `simplex`, `sketchy`, `slate`, `solar`, `spacelab`, `united`, `vapor`, `zephyr`, and more.

### Roles Configuration

```clojure
:roles {:hierarchy ["S" "A" "U"]
        :labels {"S" "System" "A" "Administrator" "U" "User"}
        :permissions {:S [:all]
                      :A [:create :read :update :delete]
                      :U [:read]}}
```

---

## i18n (Translations)

Translation files live in `resources/i18n/en.edn` and `resources/i18n/es.edn`.

### Usage in Code

```clojure
(i18n/t :common/save)              ;; => "Guardar" (using default locale)
(i18n/t :common/save :en)          ;; => "Save"
(i18n/tr request :common/edit)     ;; Uses the session locale
```

### Translation Key Categories

| Namespace | Example Keys |
|-----------|-------------|
| `:common/*` | `save`, `cancel`, `edit`, `delete`, `new`, `search`, `export`, `close`, `back`, `next`, `loading`, `yes`, `no`, `confirm` |
| `:validation/*` | `required`, `invalid-email`, `invalid-date`, `min-length`, `max-length` |
| `:error/*` | `general`, `not-found`, `unauthorized`, `forbidden`, `server` |
| `:success/*` | `saved`, `deleted`, `created`, `updated`, `uploaded` |
| `:auth/*` | `login`, `logout`, `invalid-credentials`, `session-expired`, `welcome` |
| `:grid/*` | `no-data`, `showing`, `per-page`, `page` |

### Switching Language

Visit `/set-language/en` or `/set-language/es` — sets the locale in the session.

---

## URL Routing

All engine-driven entities are available at `/admin/<entity-name>`:

| URL | Method | What it does |
|-----|--------|-------------|
| `/admin/:entity` | GET | Grid view (with tabs if subgrids exist) |
| `/admin/:entity/add-form` | GET | New record form (AJAX/modal) |
| `/admin/:entity/edit-form/:id` | GET | Edit record form (AJAX/modal) |
| `/admin/:entity/save` | POST | Save (create or update) |
| `/admin/:entity/delete/:id` | GET | Delete record |

---

## Pick/D3 Multivalue Philosophy

### How It Differs From Traditional Databases

| Traditional (FK) | Pick/D3 (Multivalue) |
|-------------------|---------------------|
| Child table has `parent_id` column | Parent table has `child_ids` TEXT column |
| `JOIN` to get related data | T-dictionary translates IDs to data |
| Delete child = delete row | Delete child = delete row + remove ID from parent |
| N+1 query problem | Single dictionary-aware query |

### The `]` Delimiter

The `]` character separates multiple IDs in a multivalue field:

```
car_ids = "1]4]7"  →  Car IDs: 1, 4, 7
```

When you add a child via subgrid: `"1]4"` → `"1]4]7"`
When you delete a child via subgrid: `"1]4]7"` → `"1]4"`

### T-Dictionary Definition

In migrations, define T-dictionaries to translate multivalue IDs:

```clojure
;; Parameters: db, dict-table, key, type, position, conversion, description
(pick/define-dictionary-field db "contactos_DICT"
  "CAR_COMPANIES"  ;; Dictionary key
  "T"              ;; T = Translation type
  "7"              ;; Position (column index of car_ids)
  "Tcars;COMPANY"  ;; Translation: look up cars table, COMPANY field
  "Car Companies") ;; Description
```

This means: *"When you ask for CAR_COMPANIES on a contacto, take the value in position 7 (car_ids), split it by `]`, look up each ID in the cars table, and return the COMPANY field."*

---

## License

MIT License
