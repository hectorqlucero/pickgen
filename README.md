# Pickgen

A data-driven Clojure CRUD application template using the **Pick/D3 multivalue database philosophy** — no foreign keys, parent records store child IDs as delimited lists.

Uses [pickdict](https://clojars.org/org.clojars.hector/pickdict) for database operations and SQLite as the database.

Includes a working demo app (contacts, siblings, cars) to show how everything fits together. Run `init.sh` to rename it to your own project.

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

## Setup (Existing Clone)

If you've already run `init.sh`, use these commands:

```bash
lein migrate       # Create database tables
lein database      # Seed default user accounts
lein run           # Start at http://localhost:3000
```

Open **http://localhost:3000** in your browser.

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

## Running the App

| Command            | What it does                                          |
|--------------------|-------------------------------------------------------|
| `lein migrate`     | Creates all tables, dictionaries, and views (safe to run multiple times) |
| `lein database`    | Seeds the default user accounts                       |
| `lein run`         | Starts the web server on port 3000                    |
| `lein test`        | Runs the test suite                                   |
| `lein check`       | Checks for compilation errors                         |
| `lein scaffold`    | Generates files for a new entity                      |

For development with auto-reload, the project uses a `dev` profile — `lein run` from the dev profile reloads code changes automatically.

---

## How the App Works

### Everything is Driven by EDN Config Files

Each entity (users, pickgen, siblings, cars) has a `.edn` file under `resources/entities/`. These files control:

- What table to query
- What fields to show in forms
- What queries to run for listing and fetching
- What actions are allowed (new, edit, delete)
- What subgrids (child records) to display

**Example:** `resources/entities/pickgen.edn` defines the pickgen entity — its fields, queries, and two subgrids (cars and siblings).

The engine reads these files at runtime. You don't write route handlers or HTML templates for each entity — the engine generates everything from the EDN config.

### Pick/D3 Multivalue Philosophy (No Foreign Keys)

This app does **not** use foreign keys. Instead, it follows the Pick/D3 database model:

1. **Parent records store child IDs.** The `pickgen` table has `sibling_ids` and `car_ids` columns (TEXT type). These store multiple IDs separated by the `]` character.

   Example: A contact with 3 cars has `car_ids = "1]4]7"`.

2. **Child tables are standalone.** The `siblings` and `cars` tables have no `contacto_id` column. They don't know which contact they belong to.

3. **T-dictionaries translate IDs.** When the app needs to show a contact's car companies, it looks up `pickgen_DICT.CAR_COMPANIES`, which is a T-type (translation) dictionary that maps `car_ids` → `cars.COMPANY`.

When you add a car through a contact's subgrid:
- The car is inserted into the `cars` table
- The new car's ID is appended to the contact's `car_ids` field

When you delete a car from a contact's subgrid:
- The car is deleted from the `cars` table
- That ID is removed from the contact's `car_ids` field

### Subgrids (Tabs for Child Records)

On the pickgen detail page, you see tabs for "Cars" and "Siblings". These are **subgrids** defined in the entity's EDN file:

```clojure
:subgrids [{:entity :cars
            :title "Cars"
            :multivalue-field :car_ids   ;; Which field on the parent stores child IDs
            :icon "bi bi-list-ul"
            :label "Cars"}]
```

The `:multivalue-field` key tells the engine which column on the parent record holds the child IDs.

---

## Supported Field Types

| Type | Description | Example |
|------|-------------|---------|
| `:text` | Single-line text input | Name, code |
| `:textarea` | Multi-line text input | Notes, description |
| `:number` | Integer input | Age, quantity |
| `:decimal` | Decimal input | Price, weight |
| `:email` | Email input with validation | Email address |
| `:password` | Password input (masked) | Password field |
| `:date` | Date picker | Birth date |
| `:select` | Dropdown menu | Category, status |
| `:radio` | Radio button group | Active/Inactive |
| `:file` | File upload | Image, PDF |
| `:hidden` | Hidden field | ID, multivalue fields |
| `:computed` | Read-only calculated field | Total, full name |

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
│   │   ├── pickgen.edn
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
│   │   ├── pickgen.clj
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

## How to Add a New Entity

1. **Create the table** — Add a `create-file!` call in `src/pickgen/migrations.clj`:

   ```clojure
   (when-not (table-exists? db "products")
     (pick/create-file! db "products"
                        {:id    "INTEGER PRIMARY KEY AUTOINCREMENT"
                         :name  "TEXT"
                         :price "REAL"}))
   ```

2. **Create the entity config** — Add `resources/entities/products.edn`:

   ```clojure
   {:entity :products
    :title "Products"
    :table "products"
    :connection :default
    :rights ["U" "A" "S"]
    :mode :parameter-driven
    :menu-category :Products
    :fields [{:id :id :label "ID" :type :hidden}
             {:id :name :label "Name" :type :text :placeholder "Name..."}
             {:id :price :label "Price" :type :number}]
    :queries {:list "SELECT * FROM products ORDER BY id DESC"
              :get "SELECT * FROM products WHERE id = ?"}
    :actions {:new true :edit true :delete true}}
   ```

3. **Run the migration** — `lein migrate`

4. **Restart the app** — The new entity appears in the menu automatically.

To make it a **child entity** (accessed only via subgrid, hidden from the menu), add `:menu-hidden? true` to the EDN config and add a `:subgrids` entry on the parent entity with a `:multivalue-field` pointing to a TEXT column on the parent that stores the child IDs.

---

## Configuration

All settings live in `resources/config/app-config.edn`:

- **`:port`** — Web server port (default: 3000)
- **`:default-locale`** — Language (`:es` or `:en`)
- **`:connections`** — Database connection (SQLite path)
- **`:ui :default-theme`** — Bootstrap theme (25 Bootswatch themes included)
- **`:roles`** — Role hierarchy and permissions
- **`:security`** — CSRF, session timeout, login attempt limits

---

## Database

- **Engine:** SQLite (file at `db/pickgen.sqlite`)
- **Library:** pickdict 0.3.0
- **Tables:** `users`, `pickgen`, `siblings`, `cars`
- **Dict tables:** Auto-created by pickdict (`users_DICT`, `pickgen_DICT`, etc.) — store A-type and T-type dictionary definitions
- **Views:** `users_view` (formatted user listing)
- **Multivalue delimiter:** `]` — a field value of `"3]7]12"` means IDs 3, 7, and 12

---

## Hooks

Hooks let you run code before or after database operations. Define them in `src/pickgen/hooks/<entity>.clj` and reference them in the entity EDN:

```clojure
:hooks {:before-save  :pickgen.hooks.pickgen/before-save
        :after-load   :pickgen.hooks.pickgen/after-load}
```

Available hooks: `before-load`, `after-load`, `before-save`, `after-save`, `before-delete`, `after-delete`.

---

## License

MIT License
