# Migration Feature

## What It Does

A one-time migrator that reads all data from MySQL and writes it into MongoDB and Neo4j.
Confirmed correct approach by professor — three independent databases, no ongoing sync.

**Endpoints:**
- `POST /migrate` — migrates everything (MongoDB + Neo4j)
- `POST /migrate/mongo` — MongoDB only
- `POST /migrate/neo4j` — Neo4j only

**Response example:**
```json
{
  "employees": 100,      "shifts": 100,         "departments": 20,    "leaveDocuments": 100,
  "neo4jEmployees": 100, "neo4jDepartments": 20, "neo4jWorkLocations": 10,
  "neo4jShifts": 100,    "neo4jJobRoles": 12,    "errors": []
}
```

---

## How To Run

```bash
make clean      # wipe all volumes (required on first run)
make run-all    # starts MySQL + MongoDB + Neo4j in Docker, then runs Spring Boot
```

Once the app is started, hit `POST http://localhost:8080/migrate` in Postman.

To stop:
```bash
make down       # stop containers, keep data
make clean      # stop containers, wipe all data
```

---

## Architecture

```
MySQL (Docker, port 3307)
  └── JPA/Hibernate reads all tables

MigrationService.java
  └── loads all rows into memory (Maps keyed by ID to avoid N+1)
  └── maps rows → MongoDB documents / Neo4j nodes in Java
  └── writes to MongoDB and Neo4j

MongoDB (Docker, port 27017)         Neo4j (Docker, port 7687)
  └── employees collection              └── Employee nodes
  └── shifts collection                 └── Department nodes
  └── departments collection            └── WorkLocation nodes
  └── leave_requests collection         └── Shift nodes
                                        └── JobRole nodes
```

**MongoDB** embeds related data into documents (e.g. an Employee document includes
department, job roles, work location, and contracts as nested objects).

**Neo4j** stores flat nodes only. Relationships are not implemented yet — deferred
until the group agrees on graph design.

---

## Key Files

| File | Purpose |
|------|---------|
| `migration/MigrationService.java` | All migration logic and mappers |
| `migration/MigrationController.java` | Exposes the 3 POST endpoints |
| `config/Neo4jConfig.java` | Fixes dual transaction manager conflict (see below) |
| `*/mongo/*Document.java` | MongoDB document models |
| `*/neo4j/*Node.java` | Neo4j node models |
| `*/mongo/*MongoRepository.java` | MongoDB repositories |
| `*/neo4j/*Neo4jRepository.java` | Neo4j repositories |

---

## .env Setup (required — not committed to git)

Copy `.env.example` and fill in:

```
# MySQL (local Docker)
DB_URL=jdbc:mysql://localhost:3307/shift_happens?serverTimezone=UTC
DB_USERNAME=root
DB_PASSWORD=rootpassword

# Docker
MYSQL_ROOT_PASSWORD=rootpassword
MYSQL_DATABASE=shift_happens
LOCAL_DB_USERNAME=root
APP_DB_USER=app_user
APP_DB_PASSWORD=<ask team>

# MongoDB (local Docker)
MONGO_URI=mongodb://localhost:27017/shift_happens

# Neo4j (local Docker)
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=neo4jpassword
```

---

## Technical Note — Dual Transaction Manager Fix

Having both `spring-boot-starter-data-jpa` and `spring-boot-starter-data-neo4j` on the
classpath causes a conflict: both auto-configurations use `@ConditionalOnMissingBean(TransactionManager.class)`,
so whichever runs first suppresses the other. Result: one transaction manager is missing
and all writes to that database fail.

**Fix:** `Neo4jConfig.java` explicitly defines both transaction managers, bypassing the
conditionals. `JpaTransactionManager` is `@Primary` so JPA and `@Transactional` use it
by default. `Neo4jTransactionManager` is wired into `Neo4jTemplate` explicitly.
