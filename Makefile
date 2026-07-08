.PHONY: dev-up dev-down build-all proto-gen clean dashboard-install dashboard-dev

# ──────────────────────────────────────────────
# Local dev infrastructure
# ──────────────────────────────────────────────
dev-up:
	docker compose up -d
	@echo "✓ PostgreSQL   → localhost:5432"
	@echo "✓ Redis        → localhost:6379"
	@echo "✓ pgAdmin      → http://localhost:5050"

dev-down:
	docker compose down

dev-logs:
	docker compose logs -f

# ──────────────────────────────────────────────
# Proto code generation
# ──────────────────────────────────────────────
proto-gen:
	@echo "Generating Java sources from proto..."
	mvn -f controller/pom.xml generate-sources -q
	mvn -f sidecar/pom.xml generate-sources -q
	mvn -f cli/pom.xml generate-sources -q
	@echo "✓ Java proto generated"

# ──────────────────────────────────────────────
# Build all Java modules
# ──────────────────────────────────────────────
build-all:
	mvn -f controller/pom.xml package -DskipTests -q
	mvn -f sidecar/pom.xml package -DskipTests -q
	mvn -f cli/pom.xml package -DskipTests -q
	mvn -f api/pom.xml package -DskipTests -q
	@echo "✓ All Java modules built"

# ──────────────────────────────────────────────
# Tests
# ──────────────────────────────────────────────
test-all:
	mvn -f controller/pom.xml test
	mvn -f sidecar/pom.xml test
	mvn -f cli/pom.xml test
	mvn -f api/pom.xml test

# ──────────────────────────────────────────────
# Dashboard
# ──────────────────────────────────────────────
dashboard-install:
	cd dashboard && npm install

dashboard-dev:
	cd dashboard && npm run dev

dashboard-build:
	cd dashboard && npm run build

# ──────────────────────────────────────────────
# Docker images
# ──────────────────────────────────────────────
docker-build-all:
	docker build -t localmesh/controller:latest controller/
	docker build -t localmesh/sidecar:latest sidecar/
	docker build -t localmesh/api:latest api/

# ──────────────────────────────────────────────
# Clean
# ──────────────────────────────────────────────
clean:
	mvn -f controller/pom.xml clean -q
	mvn -f sidecar/pom.xml clean -q
	mvn -f cli/pom.xml clean -q
	mvn -f api/pom.xml clean -q
	rm -rf dashboard/dist dashboard/node_modules
