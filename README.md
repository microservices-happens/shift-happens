# Shift Happens

A shift scheduling and leave management system built as a database course project. The system evolves across three database phases — relational, document, and graph — all running simultaneously via a one-time migration.

## Microservice architecture (Development of Large Systems)

The current application remains the runnable monolith. The target microservice architecture (7 services, REST + GraphQL towards the frontend, RabbitMQ between services, CQRS, a leave-approval saga, and AI replacement suggestions) is described in [docs/architecture.md](docs/architecture.md).

- `docs/architecture.md` — requirements, service decomposition, patterns, deployment, CI/CD, testing, monitoring, versioning.
- `docs/shift-happens-high-level-architecture.drawio` — editable high-level diagram.
- `contracts/` — OpenAPI (REST), GraphQL SDL and AsyncAPI (events) contracts between the services.
- `services/*/README.md`, `functions/*/README.md` — scaffold for each service.
- `docker-compose.microservices.yml` — development environment for the target system.

---

## Final Project Artifacts Guide

This repository contains the final project artifacts in the following locations.

### Public code repository

The complete source code is in this public GitHub repository:

```text
https://github.com/Luke3520/shift_happens
```

### Relational database artifacts - MySQL

The relational database scripts are located in:

```text
docker/init/
src/main/resources/db/mysql/
```

Important files:

| Artifact | Location |
|----------|----------|
| Database creation, tables, keys, indexes, constraints, and referential integrity | `docker/init/01-schema.sql` and `src/main/resources/db/mysql/schema.sql` |
| Test data | `docker/init/02-seed-data.sql` and `src/main/resources/db/mysql/seed.sql` |
| Stored procedures/functions | `docker/init/08-routines.sql` and `src/main/resources/db/mysql/migrations/v4_stored_functions.sql` |
| Triggers | `docker/init/05-triggers.sql` and `src/main/resources/db/mysql/migrations/v3_leave_approval_trigger.sql`, `v4_triggers.sql`, `v5_auditlog_triggers.sql` |
| Views | `docker/init/07-views.sql` and `src/main/resources/db/mysql/views.sql` |
| Events | `docker/init/06-events.sql` |
| Users and privileges | `docker/init/09-create-app-user.sh`, `docker/init/09-seed-test-logins.sql`, and `src/main/resources/db/mysql/migrations/v6_users_privileges.sql` |
| Committed database dump | `src/main/resources/db/mysql/mysql.sql` |
| Script for loading dump | `src/main/resources/db/mysql/load.sh` |

The Docker setup imports the scripts in `docker/init/` automatically when the MySQL container is created.

### CRUD application source code

The CRUD application source code is located in:

```text
src/main/java/dk/ek/shift_happens/
frontend/
```

Backend controllers, services, repositories, entities, DTOs, security, and database integrations are in `src/main/java/dk/ek/shift_happens/`. The React frontend is in `frontend/`.

### Migration application source code

The migration source code is located in:

```text
src/main/java/dk/ek/shift_happens/migration/
```

The migration can be triggered after startup with:

```bash
curl -X POST http://localhost:8080/migrate
```

### MongoDB artifacts

MongoDB artifacts are located in:

```text
src/main/resources/db/mongodb/
```

Important files:

| Artifact | Location |
|----------|----------|
| Document database dump | `src/main/resources/db/mongodb/dump/` |
| Script for loading test data/dump | `src/main/resources/db/mongodb/load.sh` |
| JSON schemas | `src/main/resources/db/mongodb/schemas/` |
| MongoDB CRUD source code | `src/main/java/dk/ek/shift_happens/**/mongo/` |

To load the committed MongoDB dump into the running Docker container:

```bash
bash src/main/resources/db/mongodb/load.sh
```

### Neo4j artifacts

Neo4j artifacts are located in:

```text
src/main/resources/db/neo4j/
src/main/resources/neo4_graf/
```

Important files:

| Artifact | Location |
|----------|----------|
| Graph database dump | `src/main/resources/db/neo4j/neo4j.dump` |
| Script for loading test data/dump | `src/main/resources/db/neo4j/load.sh` |
| Schema and indexes | `src/main/resources/neo4_graf/neo4j-schema.cypher` and `src/main/resources/neo4_graf/neo4j-indexes.cypher` |
| Neo4j CRUD source code | `src/main/java/dk/ek/shift_happens/**/neo4j/` |

To load the committed Neo4j dump into the running Docker setup:

```bash
bash src/main/resources/db/neo4j/load.sh
```

### Installation in a test environment

The project is designed to run with Docker Compose. **Only Docker is required** — no Java, Maven, or database clients needed.

```bash
git clone https://github.com/Luke3520/shift_happens.git
cd shift_happens
cp .env.example .env
docker compose up app
```

This starts MySQL, MongoDB, Neo4j, and the Spring Boot application. MySQL is initialized automatically from `docker/init/`. The API is available at **http://localhost:8080** once startup completes.

**Populate MongoDB and Neo4j** — choose one option:

Option A — run the migration (reads from MySQL and writes to both):
```bash
curl -X POST http://localhost:8080/migrate
```

Option B — load the committed dump files directly:
```bash
bash src/main/resources/db/mysql/load.sh
bash src/main/resources/db/mongodb/load.sh
bash src/main/resources/db/neo4j/load.sh
```

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Framework | Spring Boot 3.5 (Java 21) |
| Build | Maven |
| Phase 1 | MySQL 8.0 + Spring Data JPA |
| Phase 2 | MongoDB + Spring Data MongoDB |
| Phase 3 | Neo4j + Spring Data Neo4j |
| Auth | Spring Security + JWT (HMAC-SHA256) |
| Other | Lombok, Docker, Docker Compose |

---

## Installation & Setup

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/)
- Java 21 (only needed if running the app outside Docker)

### 1. Clone the repository

```bash
git clone https://github.com/Luke3520/shift_happens.git
cd shift_happens
```

### 2. Configure environment

```bash
cp .env.example .env
```

The `.env.example` file contains working defaults for local development — no changes required to get started.

### 3. Start everything with Docker Compose

```bash
docker compose up app
```

This single command starts MySQL, MongoDB, Neo4j, and the Spring Boot application. All databases are initialized automatically from the SQL scripts in `docker/init/`.

The API will be available at **http://localhost:8080** once you see:
```
Started ShiftHappensApplication in X.XXX seconds
```

To stop:
```bash
docker compose down
```

To wipe all data and start fresh:
```bash
docker compose down -v
docker compose up app
```

### 4. Populate MongoDB and Neo4j

MySQL is seeded automatically. MongoDB and Neo4j need a one-time population — choose one option:

**Option A — migration** (reads from MySQL, writes to MongoDB + Neo4j):
```bash
curl -X POST http://localhost:8080/migrate
```

**Option B — load committed dumps** (faster, skips migration):
```bash
bash src/main/resources/db/mongodb/load.sh
bash src/main/resources/db/neo4j/load.sh
```

You only need to do this once (or again after a `docker compose down -v` reset).

---

## Make Commands (optional shortcut — requires `make`)

If you have `make` installed, these wrap the docker/maven commands above:

| Command | Equivalent |
|---------|------------|
| `make run-all` | Start all 3 DBs + run app locally (outside Docker) |
| `make run-dbs` | `docker compose up -d --wait db mongodb neo4j` |
| `make run-app` | `./mvnw spring-boot:run` |
| `make reset` | `docker compose down -v && docker compose up -d --wait db mongodb neo4j` |
| `make down` | `docker compose down` |
| `make clean` | `docker compose down -v` |
| `make db-shell` | Open MySQL CLI inside the container |
| `make load-dbs` | Restore all 3 committed dumps into running containers |
| `make load-mysql` | Restore committed MySQL dump only |
| `make load-mongo` | Restore committed MongoDB dump only |
| `make load-neo4j` | Restore committed Neo4j dump only |
| `make backup` | Dump all 3 DBs → `backups/<timestamp>/` |
| `make restore BACKUP=backups/<ts>` | Restore all 3 DBs from a timestamped backup |
| `make verify` | Print record counts for all 3 databases |
| `make test` | Spin up isolated test DBs, run `./mvnw test`, then tear them down |
| `make test-up` | Start the throwaway test DBs only (ports 3308 / 27018 / 7688) |
| `make test-down` | Stop the test DBs and wipe their data |

---

## Security

### SQL Injection Prevention
All database access goes through Spring Data JPA repositories (`JpaRepository`) and named JPQL parameters. Queries are never built by string concatenation — the framework compiles them into parameterized prepared statements, so user input cannot alter query structure.

### Database Users & Privileges (MySQL)
The application connects as `app_user`, a least-privilege account defined in [`src/main/resources/db/mysql/migrations/v6_users_privileges.sql`](src/main/resources/db/mysql/migrations/v6_users_privileges.sql). It holds:
- **`SELECT` only** on `audit_log` (no writes)
- **`SELECT, INSERT` only** on `leave_ledger` (double-entry ledger, no updates/deletes)
- **Full CRUD** on all other operational tables
- No `DROP`, `ALTER`, `CREATE`, or `GRANT` permissions

In Docker (and when loading an external DB via `make load-railway`), all four database users — `app_user`, `sh_admin`, `sh_readonly`, `sh_restricted` — are created at startup by [`docker/init/10-db-users.sql`](docker/init/10-db-users.sql).

### Database Backups
See the [Database Operations](#database-operations) section below for the full backup, restore, and verification guide.

---

## Authentication

All endpoints except `POST /auth/login` require a JWT bearer token.

**Login:**
```
POST /auth/login
Content-Type: application/json

{ "email": "user@example.com", "password": "yourpassword" }
```

**Response:**
```json
{ "token": "eyJ...", "employeeId": 1, "role": "ADMINISTRATOR", ... }
```

**Using the token:**
```
Authorization: Bearer <token>
```

**Role-based access:**
- `GET /**` — any authenticated user
- `POST / PUT / PATCH / DELETE /**` — ADMINISTRATOR or MANAGER only
- `GET /audit-log/**` — ADMINISTRATOR only

---

## API Endpoints

### MySQL

| Resource | Path |
|----------|------|
| Auth | `POST /auth/login` (public) |
| Employees | `/employees` |
| Departments | `/departments` |
| Work Locations | `/work-locations` |
| Shifts | `/shifts` |
| Shift Assignments | `/shift-assignments` |
| Shift Approvals | `/shift-approvals` |
| Shift Swaps | `/shift-swaps` |
| Shift Swap Approvals | `/shift-swap-approvals` |
| Leave Types | `/leave-types` |
| Leave Requests | `/leave-requests` |
| Leave Approvals | `/leave-approvals` |
| Leave Ledger | `/leave-ledger` |
| Job Roles | `/job-roles` |
| Employee Job Roles | `/employee-job-roles` |
| Employee Contracts | `/employee-contracts` |
| User Roles | `/user-roles` |
| Audit Log | `/audit-log` |

### MongoDB (requires migration)

| Resource | Path |
|----------|------|
| Employees | `/mongo/employees` |
| Shifts | `/mongo/shifts` |
| Departments | `/mongo/departments` |
| Job Roles | `/mongo/job_role` |
| Leave Types | `/mongo/leave_type` |
| Leave | `/mongo/leave` |
| Audit Log | `/mongo/audit_log` |
| User Roles | `/mongo/user_role` |
| Work Locations | `/mongo/work_location` |

All MongoDB endpoints support `GET`, `POST`, `PUT`, and `DELETE`.

### Neo4j (requires migration)

| Resource | Path |
|----------|------|
| Employees | `/neo4j/employees` |
| Departments | `/neo4j/departments` |
| Shifts | `/neo4j/shifts` |
| Job Roles | `/neo4j/job_role` |
| Work Locations | `/neo4j/work_location` |

### Migration

| Endpoint | Description |
|----------|-------------|
| `POST /migrate` | Full migration: MySQL → MongoDB + Neo4j |
| `POST /migrate/mongo` | MySQL → MongoDB only |
| `POST /migrate/neo4j` | MySQL → Neo4j only |

---

## Database Operations

This section covers three scenarios: loading the committed dump files shipped with the repo, taking new backups, and restoring from a backup.

---

### Scenario 1 — Fresh setup from the committed dumps

Use this when you clone the repo and want MongoDB and Neo4j populated without running the migration yourself. MySQL is always seeded automatically by Docker on first start.

```bash
# 1. Start all three databases
make run-dbs

# 2. Load MongoDB and Neo4j from the committed dump files
make load-dbs

# 3. Confirm the data is there
make verify
```

Expected output from `make verify`:
```
=== MySQL ===
101
=== MongoDB ===
101
=== Neo4j ===
label, count
"Department", 20
"Employee", 101
"JobRole", 12
"Shift", 100
"WorkLocation", 10
```

---

### Scenario 2 — Take a new backup

Use this to snapshot the current live state of all three databases.

```bash
# Databases must be running
make run-dbs

# Run the backup — creates backups/<timestamp>/ with mysql.sql, mongodb/, neo4j.dump, backup.log
make backup
```

The script prints a coloured status line per database and exits with an error if any step fails. A `backup.log` is written inside the timestamped folder.

To **update the committed dump files** after a backup (e.g. after seeding new data):
```bash
make backup

# Copy the fresh dumps over the committed ones
cp    backups/<timestamp>/mysql.sql   src/main/resources/db/mysql/mysql.sql
cp -r backups/<timestamp>/mongodb/.   src/main/resources/db/mongodb/dump/
cp    backups/<timestamp>/neo4j.dump  src/main/resources/db/neo4j/neo4j.dump

# Verify then commit
make verify
git add src/main/resources/db/mysql/mysql.sql src/main/resources/db/mongodb/dump/ src/main/resources/db/neo4j/neo4j.dump
git commit -m "chore: update committed db dump artifacts"
```

---

### Scenario 3 — Restore from a backup

Use this to roll back all three databases to a previous backup.

```bash
# Wipe everything and restart fresh containers
make reset

# Restore from a specific backup
make restore BACKUP=backups/<timestamp>
```

The restore script validates all three files exist before touching anything, then prints the row/document/node count per database as confirmation:
```
  ✓ MySQL restored — 101 employee rows
  ✓ MongoDB restored — 101 employee documents
  ✓ Neo4j restored — 243 nodes
```

---

### Quick reference

| Command | What it does |
|---|---|
| `make backup` | Dump all 3 DBs → `backups/<timestamp>/` |
| `make restore BACKUP=backups/<ts>` | Restore all 3 DBs from a backup |
| `make load-dbs` | Load committed dump files into running containers (all 3) |
| `make load-mysql` | Load committed MySQL dump only |
| `make load-mongo` | Load committed MongoDB dump only |
| `make load-neo4j` | Load committed Neo4j dump only |
| `make verify` | Print record counts for all 3 databases |
| `make reset` | Wipe all volumes, restart fresh (re-runs init scripts) |

---

## Database Schema (MySQL)

**18 tables** across four domains:

**Organisation:** `department`, `work_location`, `user_role`

**People:** `employee`, `employee_contract`, `job_role`, `employee_job_role`

**Scheduling:** `shift`, `shift_required_job_role`, `shift_assignment`, `shift_approval`, `shift_swap`, `shift_swap_approval`

**Leave:** `leave_type`, `leave_request`, `leave_approval`, `leave_ledger`

**Auditing:** `audit_log`

Notable constraints:
- `leave_approval` inserts are validated by a DB trigger that checks manager role and leave balance
- `leave_ledger` tracks balances double-entry style (ACCRUALs +, USAGEs −)
- Composite PKs on `employee_job_role` and `shift_required_job_role` use `@IdClass`

---

## MongoDB Design

Employee and Shift documents are **denormalized** — related data is embedded rather than referenced:

- **Employee** embeds: department, work location, user role, contracts, job roles, leave requests with approvals, leave ledger entries
- **Shift** embeds: required job roles, assignments with approvals and swap requests

Reference data (Department, JobRole, LeaveType, UserRole, WorkLocation, AuditLog) is kept flat.

See [`src/main/resources/db/mongodb/schemas/`](src/main/resources/db/mongodb/schemas/) for the full document shapes.

---

## Connecting to the Databases

**MySQL CLI:**
```bash
make db-shell
# or
docker exec -it shift-happens-db mysql -u root -prootpassword shift_happens
```

**MySQL GUI (Workbench, DBeaver, etc.):**

| Setting | Value |
|---------|-------|
| Host | `127.0.0.1` |
| Port | `3307` |
| User | `root` |
| Password | `MYSQL_ROOT_PASSWORD` from `.env` |
| Database | `shift_happens` |

**MongoDB:** `mongodb://localhost:27017/shift_happens`

**Neo4j Browser:** http://localhost:7474 (bolt port: `7687`)

---

## User Stories

| Role | Story |
|------|-------|
| Admin | Create and manage employees, departments, work locations |
| Admin | Build schedules and approve/decline shift assignments |
| Admin | Approve/decline shift swap requests |
| Manager | Approve/decline leave requests |
| Employee | View assigned schedule |
| Employee | Apply for a shift or leave |
| Employee | Request a shift swap with another employee |
