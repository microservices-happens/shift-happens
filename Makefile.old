# Load .env variables
include .env
export

# ──────────────────────────────────────────────────────────────
# Development
# ──────────────────────────────────────────────────────────────

## Start all 3 databases in the background, then run the Spring Boot app locally
run-all:
	docker compose up -d --wait db mongodb neo4j neo4j-init neo4j-init-indexes
	@echo "MySQL is ready."
	./mvnw spring-boot:run

## Start all 3 databases in the background only (no app)
run-dbs:
	docker compose up -d --wait db mongodb neo4j neo4j-init neo4j-init-indexes

## Run the Spring Boot app locally (databases must already be running)
run-app:
	./mvnw spring-boot:run

## Start only MySQL (watch logs for errors)
db:
	docker compose up db

## Nuke all volumes and restart databases fresh (re-runs all init scripts)
reset:
	docker compose down -v
	docker compose up -d --wait db mongodb neo4j neo4j-init neo4j-init-indexes

## Stop everything
down:
	docker compose down

## Stop everything and delete all data volumes
clean:
	docker compose down -v

## Show MySQL logs
db-logs:
	docker compose logs db

## Connect to MySQL CLI inside the container
db-shell:
	docker exec -it shift-happens-db mysql -u root -p$(MYSQL_ROOT_PASSWORD) $(MYSQL_DATABASE)

# ──────────────────────────────────────────────────────────────
# Testing  (isolated, throwaway Docker DBs — never touches dev data)
# ──────────────────────────────────────────────────────────────

# Run the test stack under its own compose project so it stays isolated from dev.
TEST_COMPOSE = docker compose -p shift-happens-test -f docker-compose.test.yml

# Connection settings that point the test run at the test stack's ports.
# These override the dev values exported from .env, just for ./mvnw test.
TEST_ENV = \
	DB_URL="jdbc:mysql://127.0.0.1:3308/$(MYSQL_DATABASE)?serverTimezone=UTC" \
	DB_USERNAME=root \
	DB_PASSWORD="$(MYSQL_ROOT_PASSWORD)" \
	MONGO_URI="mongodb://127.0.0.1:27018/$(MYSQL_DATABASE)" \
	NEO4J_URI="bolt://127.0.0.1:7688" \
	NEO4J_USERNAME="$(NEO4J_USERNAME)" \
	NEO4J_PASSWORD="$(NEO4J_PASSWORD)" \
	JWT_SECRET="$(JWT_SECRET)" \
	PASSWORD_PEPPER="$(PASSWORD_PEPPER)"

## Start the throwaway test databases (MySQL/Mongo/Neo4j) on test ports
test-up:
	$(TEST_COMPOSE) up -d --wait

## Stop the test databases and wipe their data
test-down:
	$(TEST_COMPOSE) down -v

## Spin up test DBs, run the full test suite against them, then tear them down
test:
	@set -e; \
	trap '$(TEST_COMPOSE) down -v' EXIT; \
	$(TEST_COMPOSE) up -d --wait; \
	$(TEST_ENV) ./mvnw test

# ──────────────────────────────────────────────────────────────
# Load Dumps  (restore committed seed data into live containers)
# ──────────────────────────────────────────────────────────────

## Load all 3 committed dumps — run after: make reset && make run-dbs
load-dbs:
	@bash src/main/resources/db/mysql/load.sh
	@bash src/main/resources/db/mongodb/load.sh
	@bash src/main/resources/db/neo4j/load.sh

## Load the committed MySQL dump only
load-mysql:
	@bash src/main/resources/db/mysql/load.sh

## Load the committed MongoDB dump only
load-mongo:
	@bash src/main/resources/db/mongodb/load.sh

## Load the committed Neo4j dump only
load-neo4j:
	@bash src/main/resources/db/neo4j/load.sh

## Load the full MySQL schema + seed data into the external DB in .env (e.g. Railway)
load-railway:
	@bash scripts/load-railway.sh

# ──────────────────────────────────────────────────────────────
# Backups
# ──────────────────────────────────────────────────────────────

## Dump all 3 databases to backups/<timestamp>/
backup:
	@bash scripts/backup.sh

## Restore from a backup. Usage: make restore BACKUP=backups/<timestamp>
restore:
	@bash scripts/restore.sh $(BACKUP)

## Print record counts across all 3 databases
verify:
	@echo "=== MySQL ==="
	@docker exec shift-happens-db mysql -u root -p$(MYSQL_ROOT_PASSWORD) -sN \
		-e "SELECT COUNT(*) FROM $(MYSQL_DATABASE).employee;" 2>/dev/null
	@echo "=== MongoDB ==="
	@docker exec shift-happens-mongo mongosh shift_happens --quiet \
		--eval "db.employees.countDocuments()" 2>/dev/null
	@echo "=== Neo4j ==="
	@docker exec shift-happens-neo4j cypher-shell \
		-u neo4j -p $(NEO4J_PASSWORD) \
		"MATCH (n) RETURN labels(n)[0] AS label, count(n) AS count ORDER BY label;" 2>/dev/null
