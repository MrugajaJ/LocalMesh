# LocalMesh
### *Run your service locally. Live in the cluster.*

---

## The Name

**LocalMesh** — "Local" because your code runs on your machine, "Mesh" because it transparently joins the service mesh of a real Kubernetes cluster. The name is short, technical, memorable, and says exactly what the tool does.

Tagline: *"Your laptop is a pod."*

---

## What We're Building

LocalMesh is a developer tool that solves the #1 pain point of microservice development: you can't run 15 services locally, but you need them all to test your one change.

LocalMesh lets a developer run **only their service** on their laptop — in their IDE, with hot reload, with their debugger attached — while all its upstream and downstream dependencies run in a **shared Kubernetes cluster**. From the cluster's perspective, the developer's laptop IS a deployed pod. Traffic flows in and out of it as if it were containerised and running in K8s.

The result: **sub-2-second feedback loops** on real cluster traffic, with zero mocking.

### The developer experience

```bash
# Step 1: Connect your local machine to the cluster
localmesh connect

# Step 2: Intercept a service — route its traffic to your laptop
localmesh intercept payment-service --port 8080

# Step 3: Run your service locally as normal
./mvnw spring-boot:run

# Now: every request hitting payment-service in the cluster
# comes to YOUR JVM. You edit code, JVM hot-reloads,
# the change is live in <2 seconds.
# Other devs are untouched — they see the real pod.
```

That's the entire UX. One command to connect, one command to intercept, run your service normally.

---

## System Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                     │
│                                                           │
│  ┌─────────────┐    ┌─────────────┐   ┌──────────────┐  │
│  │ order-svc   │───▶│ payment-svc │──▶│ notify-svc   │  │
│  │  (real pod) │    │  (INTERCEPTED)  │  (real pod)  │  │
│  └─────────────┘    └──────┬──────┘   └──────────────┘  │
│                            │ traffic routed               │
│                     ┌──────▼──────┐                      │
│                     │  LocalMesh  │                       │
│                     │ Controller  │                       │
│                     │   (Java)    │                       │
│                     └──────┬──────┘                       │
└────────────────────────────┼────────────────────────────-┘
                             │ encrypted tunnel (gRPC)
                    ┌────────▼────────┐
                    │  Developer      │
                    │  Laptop         │
                    │                 │
                    │  localmesh CLI  │
                    │  ↕              │
                    │  JVM (port 8080)│
                    │  (hot reload ✓) │
                    │  (debugger ✓)   │
                    └─────────────────┘
```

### Components

#### 1. LocalMesh Controller (Java 21, Spring Boot)
- A Kubernetes controller deployed into the cluster
- Watches for `LocalMeshIntercept` Custom Resources (CRDs)
- When an intercept is created: injects a sidecar into the target pod that proxies traffic to the developer's tunnel
- Reconciliation loop written in Java using the Kubernetes Java client
- Stores intercept sessions in PostgreSQL
- Publishes live traffic metrics to Redis pub/sub

#### 2. LocalMesh CLI (Java 21, Picocli)
- Developer installs this on their laptop
- `localmesh connect` — establishes a gRPC tunnel to the in-cluster controller
- `localmesh intercept <service> --port <port>` — creates the CRD, starts the tunnel
- `localmesh status` — shows active intercepts, traffic flowing through
- `localmesh disconnect` — tears down the intercept, restores the original pod
- Tunnels TCP traffic bidirectionally over a persistent gRPC stream

#### 3. Traffic Proxy Sidecar (Java 21, lightweight)
- Injected as a sidecar container into the intercepted pod by the controller
- Intercepts inbound traffic to the target container
- Forwards it over the gRPC tunnel to the developer's local port
- Forwards responses back into the cluster
- Emits span data (latency, status codes) to the controller

#### 4. LocalMesh API (Java 21, SparkJava)
- REST API serving the dashboard
- Endpoints: active intercepts, traffic history, service topology, developer sessions
- Auth: simple API key per developer (MVP)
- Backed by PostgreSQL (sessions, traffic logs) and Redis (live metrics cache)

#### 5. LocalMesh Dashboard (React + Vite)
- Real-time web UI showing:
  - Which developer has intercepted which service
  - Live traffic flowing through each local machine (req/s, latency, error rate)
  - A visual service map showing the cluster topology with intercepts highlighted
  - Traffic comparison: local dev vs real pod (latency side-by-side)
- WebSocket connection for live updates via Redis pub/sub → API → browser

### Data Flow

```
Incoming request to payment-service
        │
        ▼
  Cluster DNS resolves to payment-service pod
        │
        ▼
  Sidecar proxy intercepts (iptables rules)
        │
        ▼
  Forwards over gRPC tunnel to developer laptop
        │
        ▼
  Developer's JVM handles request (localhost:8080)
        │
        ▼
  Response travels back over same tunnel
        │
        ▼
  Sidecar returns response to original caller
        │
        ▼
  Metrics emitted to Redis → Dashboard updates live
```

### Database Schema (PostgreSQL)

```sql
-- Who is connected
CREATE TABLE developer_sessions (
  id UUID PRIMARY KEY,
  developer_name VARCHAR(100),
  tunnel_endpoint VARCHAR(255),
  connected_at TIMESTAMP,
  last_heartbeat TIMESTAMP
);

-- What is being intercepted
CREATE TABLE intercepts (
  id UUID PRIMARY KEY,
  session_id UUID REFERENCES developer_sessions(id),
  service_name VARCHAR(100),
  namespace VARCHAR(100),
  local_port INT,
  status VARCHAR(20), -- ACTIVE, TEARDOWN, FAILED
  created_at TIMESTAMP
);

-- Traffic log per intercept
CREATE TABLE traffic_events (
  id UUID PRIMARY KEY,
  intercept_id UUID REFERENCES intercepts(id),
  method VARCHAR(10),
  path VARCHAR(500),
  status_code INT,
  latency_ms INT,
  timestamp TIMESTAMP
);
```

### Redis Usage

```
localmesh:metrics:{intercept_id}     → live req/s, p99 latency (sorted set)
localmesh:session:{session_id}       → heartbeat TTL key (expires in 30s)
localmesh:events                     → pub/sub channel for dashboard WebSocket
localmesh:topology                   → cached service dependency graph
```

---

## Project Structure

```
localmesh/
├── controller/          # K8s controller (Java 21, Spring Boot)
│   ├── src/
│   └── Dockerfile
├── cli/                 # Developer CLI (Java 21, Picocli)
│   └── src/
├── sidecar/             # Traffic proxy sidecar (Java 21)
│   ├── src/
│   └── Dockerfile
├── api/                 # REST API (Java 21, SparkJava)
│   ├── src/
│   └── Dockerfile
├── dashboard/           # React frontend (Vite)
│   └── src/
├── k8s/                 # Kubernetes manifests
│   ├── controller-deployment.yaml
│   ├── crd-localmeshintercept.yaml
│   ├── rbac.yaml
│   └── api-deployment.yaml
├── docker-compose.yml   # Local dev setup (postgres + redis)
└── README.md
```

---

## Build Tasks & Vibe-Coding Prompts

---

### TASK 1 — Project Scaffold & Shared Infrastructure

**What this task does:** Sets up the mono-repo structure, Docker Compose for local dependencies, shared proto definitions for gRPC, and the database migrations.

---

**Prompt:**

```
Create a Java 21 mono-repo project called "localmesh" with the following structure:

localmesh/
├── controller/     (Spring Boot 3 Maven project)
├── cli/            (Maven project, Picocli)
├── sidecar/        (Maven project, plain Java)
├── api/            (Maven project, SparkJava)
├── dashboard/      (React + Vite, TypeScript)
├── proto/          (shared protobuf definitions)
├── k8s/            (Kubernetes YAML manifests)
└── docker-compose.yml

Requirements:

1. docker-compose.yml should start:
   - PostgreSQL 15 on port 5432 (db: localmesh, user: localmesh, pass: localmesh)
   - Redis 7 on port 6379
   - pgAdmin on port 5050 (optional, nice to have)

2. In proto/localmesh.proto, define a gRPC service called TunnelService with:
   - ConnectTunnel(stream TunnelFrame) returns (stream TunnelFrame) — bidirectional stream
   - TunnelFrame message with: string intercept_id, bytes payload, string direction (REQUEST/RESPONSE), int64 timestamp_ms
   - HeartbeatService with: Ping(PingRequest) returns (PongResponse)

3. In controller/, create a Spring Boot 3 app with:
   - Flyway for database migrations
   - Migration V1 that creates tables: developer_sessions, intercepts, traffic_events (schema as specified below)
   - jOOQ configured against those tables
   - Redis connection via Lettuce
   - Spring Data JPA disabled (use jOOQ only)

4. developer_sessions table:
   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
   developer_name VARCHAR(100) NOT NULL,
   tunnel_endpoint VARCHAR(255),
   connected_at TIMESTAMP DEFAULT NOW(),
   last_heartbeat TIMESTAMP DEFAULT NOW()

5. intercepts table:
   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
   session_id UUID REFERENCES developer_sessions(id),
   service_name VARCHAR(100) NOT NULL,
   namespace VARCHAR(100) NOT NULL DEFAULT 'default',
   local_port INT NOT NULL,
   status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
   created_at TIMESTAMP DEFAULT NOW()

6. traffic_events table:
   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
   intercept_id UUID REFERENCES intercepts(id),
   method VARCHAR(10),
   path VARCHAR(500),
   status_code INT,
   latency_ms INT,
   timestamp TIMESTAMP DEFAULT NOW()

7. In the root, create a Makefile with targets:
   - make dev-up → docker-compose up -d
   - make dev-down → docker-compose down
   - make build-all → builds all Java modules
   - make proto-gen → generates Java and TypeScript from proto files

Use Java 21, Maven, Spring Boot 3.2. Include .gitignore. Include a README.md with a one-paragraph description of LocalMesh and instructions to run make dev-up then make build-all.
```

---

### TASK 2 — Kubernetes Controller

**What this task does:** The brain of LocalMesh. Runs inside the cluster, watches for intercept requests, and injects the sidecar into target pods.

---

**Prompt:**

```
Build a Kubernetes controller in Java 21 + Spring Boot 3 inside the localmesh/controller/ module.

The controller should:

1. Define a Custom Resource Definition (CRD) called LocalMeshIntercept with:
   - spec.serviceName (string)
   - spec.namespace (string)
   - spec.localPort (int)
   - spec.developerSessionId (string)
   - status.state (PENDING, ACTIVE, FAILED, TORN_DOWN)
   - status.tunnelEndpoint (string)

2. Use the official Kubernetes Java client (io.kubernetes:client-java:18.0.0) to:
   - Watch for LocalMeshIntercept custom resources via a SharedInformer
   - On CREATE: find the target service's pod, inject a sidecar container called "localmesh-proxy" into the pod spec, record the intercept in PostgreSQL via jOOQ
   - On DELETE: remove the sidecar, restore the original pod, update status in DB
   - Reconciliation loop using a work queue pattern

3. The sidecar injection should:
   - Add a container localmesh-proxy with image localmesh/sidecar:latest
   - Set env vars: TARGET_PORT (the service's original port), TUNNEL_ENDPOINT (the controller's gRPC address), INTERCEPT_ID
   - Add an initContainer that sets iptables rules to redirect inbound traffic on TARGET_PORT to the sidecar

4. RBAC: generate a ClusterRole + ClusterRoleBinding YAML in k8s/rbac.yaml that grants the controller permission to get/list/watch/patch pods, deployments, services, and the localmeshintercept CRD

5. Publish a Redis pub/sub event to channel "localmesh:events" whenever:
   - A new intercept becomes ACTIVE
   - An intercept is TORN_DOWN
   - A heartbeat is missed (session expired)

6. Health check endpoint at GET /health returning {"status":"ok","interceptCount":N}

7. Write Spock tests (Groovy) for:
   - The reconciliation logic (mock the K8s client)
   - The PostgreSQL persistence (use Testcontainers)
   - The Redis pub/sub emission

Use jOOQ for all DB access, Lettuce for Redis, the Kubernetes Java client for cluster operations. Package as a Docker image in the Dockerfile.
```

---

### TASK 3 — gRPC Tunnel + Traffic Proxy Sidecar

**What this task does:** The sidecar that gets injected into the intercepted pod. It receives traffic meant for the real service and forwards it over an encrypted tunnel to the developer's laptop.

---

**Prompt:**

```
Build a lightweight Java 21 TCP/HTTP traffic proxy in localmesh/sidecar/ that acts as a Kubernetes sidecar container.

The sidecar does two things:
1. Accepts inbound HTTP traffic on a configurable port (env: PROXY_PORT, default 8081)
2. Forwards that traffic over a gRPC bidirectional stream to a remote endpoint (env: TUNNEL_ENDPOINT) and streams the response back

Implementation:

1. On startup, read env vars:
   - TUNNEL_ENDPOINT (e.g. localmesh-controller.default.svc:50051)
   - INTERCEPT_ID (UUID string)
   - TARGET_PORT (original service port, for fallback)
   - PROXY_PORT (port this sidecar listens on, default 8081)

2. Start an HTTP server using the built-in Java 21 com.sun.net.httpserver.HttpServer on PROXY_PORT

3. For every incoming HTTP request:
   a. Serialise the full request (method, path, headers, body) into a TunnelFrame protobuf message
   b. Send it over the gRPC bidirectional stream to TUNNEL_ENDPOINT
   c. Wait for a TunnelFrame response
   d. Deserialise and return the HTTP response to the caller
   e. Emit a traffic metric: method, path, status code, latency_ms to the controller via a separate gRPC unary call

4. gRPC client setup:
   - Connect to TUNNEL_ENDPOINT using ManagedChannelBuilder
   - Use the TunnelService from proto/localmesh.proto
   - Implement reconnect logic with exponential backoff (1s, 2s, 4s, max 30s)
   - Send a Ping every 10 seconds to maintain the connection

5. Graceful shutdown:
   - On SIGTERM, stop accepting new requests
   - Drain in-flight requests (wait max 10s)
   - Close the gRPC channel
   - Exit

6. Logging: structured JSON logs (slf4j + logback) with fields: intercept_id, request_id, method, path, status_code, latency_ms, timestamp

7. Dockerfile: FROM eclipse-temurin:21-jre-alpine, non-root user, health check via GET /health

8. Write unit tests (JUnit 5) for:
   - Request serialisation/deserialisation roundtrip
   - Reconnect backoff logic
   - Graceful drain behaviour

Keep the sidecar lean — no Spring, no heavy frameworks. Plain Java 21 + gRPC + protobuf only.
```

---

### TASK 4 — Developer CLI

**What this task does:** What the developer actually runs on their laptop. Connects to the cluster, intercepts a service, and forwards traffic to their local JVM.

---

**Prompt:**

```
Build a Java 21 CLI tool using Picocli in localmesh/cli/ called "localmesh".

Commands to implement:

1. localmesh connect [--kubeconfig PATH] [--namespace NS]
   - Reads kubeconfig (default: ~/.kube/config)
   - Authenticates to the cluster
   - Creates a developer session in the controller via REST POST /api/sessions
   - Prints: "Connected to cluster. Session ID: {uuid}"
   - Saves session ID to ~/.localmesh/session.json

2. localmesh intercept SERVICE_NAME --port LOCAL_PORT [--namespace NS]
   - Requires active session (reads ~/.localmesh/session.json)
   - POST /api/intercepts with {serviceName, localPort, sessionId}
   - The controller creates the CRD and injects the sidecar
   - Once the intercept is ACTIVE, starts a local gRPC server on a random high port
   - This gRPC server implements TunnelService: receives TunnelFrame messages, forwards them as HTTP requests to localhost:LOCAL_PORT, streams responses back
   - Prints live traffic log to terminal: "[GET] /payments/123 → 200 OK (34ms)"

3. localmesh status
   - GET /api/intercepts?sessionId={id}
   - Prints a table: SERVICE | STATUS | REQUESTS | AVG LATENCY | ERRORS

4. localmesh disconnect
   - DELETE /api/intercepts/{id} for all active intercepts
   - DELETE /api/sessions/{id}
   - Removes ~/.localmesh/session.json
   - Prints: "Disconnected. All intercepts torn down."

5. The local gRPC server (the tunnel receiver):
   - Listens on a random available port (printed on intercept start)
   - For each TunnelFrame received:
     a. Deserialise the HTTP request from the payload
     b. Make the HTTP request to localhost:LOCAL_PORT using Java 21 HttpClient
     c. Serialise the response into a TunnelFrame
     d. Stream it back
   - Print each request/response pair to stdout in colour: green for 2xx, yellow for 4xx, red for 5xx

6. Picocli configuration:
   - Main class: LocalMeshCli
   - Help text with examples for each command
   - --api-url flag (default: read from ~/.localmesh/session.json, fallback http://localhost:8080)
   - --verbose flag for debug logging

7. Package as a fat JAR. Include a bash wrapper script localmesh that runs java -jar localmesh.jar "$@"

8. Write Spock tests for:
   - Session persistence (read/write ~/.localmesh/session.json)
   - The TunnelFrame HTTP deserialisation
   - CLI argument parsing edge cases
```

---

### TASK 5 — REST API

**What this task does:** The thin API layer between the CLI/dashboard and the database/Redis. Manages sessions, intercepts, and streams live metrics.

---

**Prompt:**

```
Build a REST API in Java 21 using SparkJava in localmesh/api/.

Endpoints:

POST /api/sessions
  Body: { "developerName": "alice" }
  Creates a developer_sessions row, returns { "sessionId": "uuid", "createdAt": "..." }

DELETE /api/sessions/:sessionId
  Marks session as disconnected, triggers intercept teardown for all active intercepts on this session

GET /api/sessions
  Returns all currently active sessions (last_heartbeat within 30s)

POST /api/sessions/:sessionId/heartbeat
  Updates last_heartbeat to NOW() for the session

POST /api/intercepts
  Body: { "sessionId": "uuid", "serviceName": "payment-service", "namespace": "default", "localPort": 8080 }
  Creates an intercepts row with status PENDING
  Publishes a Redis message to "localmesh:commands" channel: { "action": "CREATE_INTERCEPT", "interceptId": "uuid" }
  Polls for status change to ACTIVE (max 30s, 1s intervals)
  Returns { "interceptId": "uuid", "status": "ACTIVE", "tunnelEndpoint": "..." }

GET /api/intercepts
  Query param: ?sessionId=uuid
  Returns all intercepts for that session with current status

DELETE /api/intercepts/:interceptId
  Sets status to TEARDOWN
  Publishes Redis message: { "action": "TEARDOWN_INTERCEPT", "interceptId": "uuid" }

GET /api/intercepts/:interceptId/traffic
  Query param: ?since=ISO8601_TIMESTAMP&limit=100
  Returns recent traffic_events for this intercept

GET /api/topology
  Returns a service dependency graph inferred from traffic_events:
  { nodes: [{id, name, namespace}], edges: [{source, target, requestCount, avgLatency}] }

GET /api/metrics/live
  Server-Sent Events endpoint
  Subscribes to Redis pub/sub "localmesh:events"
  Streams events as SSE to the browser dashboard

Implementation requirements:
- SparkJava for routing
- jOOQ for all DB queries (no JPA)
- Lettuce for Redis pub/sub
- Gson for JSON serialisation
- CORS enabled for localhost:5173 (Vite dev server)
- Request logging middleware: log method, path, status, latency for every request
- Error handling middleware: return { "error": "message" } JSON for all exceptions

Write Spock tests (Testcontainers for Postgres + Redis) for:
- Session lifecycle (create → heartbeat → expire)
- Intercept creation and status polling
- Traffic query filters
- SSE stream receives Redis pub/sub events
```

---

### TASK 6 — React Dashboard

**What this task does:** The visual control centre. Shows who is intercepting what, live traffic flowing through laptops, and a service map of the cluster.

---

**Prompt:**

```
Build a React + TypeScript + Vite dashboard in localmesh/dashboard/ for the LocalMesh developer tool.

Design direction: Dark terminal-inspired UI. Think: VS Code meets Datadog. Dark background (#0d1117), monospace font for traffic logs, green/amber/red status indicators. Clean, dense, information-rich. No gradients, no rounded decorations. This is a tool for engineers.

Pages / sections:

1. Header bar (always visible)
   - LocalMesh logo (text-based, monospace: [ LocalMesh ])
   - Connection status pill: green "● Connected" or red "○ Disconnected"
   - Active intercept count badge
   - "New Intercept" button

2. Active Intercepts Panel (left side, ~35% width)
   For each active intercept, a card showing:
   - Service name (large, monospace)
   - Developer name + session age ("alice · 14m")
   - Live metrics: req/s, p99 latency, error %
   - Status indicator (ACTIVE = green pulse, TEARDOWN = amber, FAILED = red)
   - "Tear Down" button
   - Click to select and show traffic in the right panel

3. Live Traffic Log (right side, ~65% width)
   When an intercept is selected:
   - A scrolling log of requests, newest at top
   - Each row: METHOD PATH → STATUS CODE (latency ms)
   - Colour coded: green 2xx, yellow 3xx/4xx, red 5xx
   - Filter bar: by method, status code, path contains
   - Auto-scroll toggle
   - Max 200 rows displayed (virtualised list)

4. Service Map (bottom panel, collapsible)
   - A force-directed graph using D3.js showing cluster services as nodes
   - Edges = traffic between services (inferred from traffic log)
   - Intercepted services highlighted with a pulsing ring
   - Node size = traffic volume
   - Click a node to filter the traffic log to that service

5. New Intercept Modal
   - Form: Service Name (text input with autocomplete from /api/topology), Local Port (number), Namespace (default: "default")
   - Submit calls POST /api/intercepts
   - Shows a loading state ("Injecting sidecar...") while polling for ACTIVE status
   - On success: closes modal, new card appears in Active Intercepts

Data layer:
- Use React Query for REST API calls (/api/sessions, /api/intercepts, /api/topology)
- Use native EventSource for the SSE stream (/api/metrics/live) to update metrics in real time
- All live metrics update every second from the SSE stream, no polling

API base URL from env: VITE_API_URL (default http://localhost:8080)

Tech: React 18, TypeScript, Vite, React Query (tanstack), D3.js for the service map, no CSS framework (write raw CSS with CSS variables). 

Include a mock data mode (VITE_MOCK=true) that simulates 3 services with live traffic so the dashboard can be demoed without a real cluster running.
```

---

### TASK 7 — Kubernetes Manifests & Demo Script

**What this task does:** Everything needed to deploy LocalMesh into a real cluster and run a live demo. This is what you show in the interview.

---

**Prompt:**

```
Create the Kubernetes deployment manifests and a demo setup script for LocalMesh.

1. k8s/crd-localmeshintercept.yaml
   Define the LocalMeshIntercept CustomResourceDefinition with:
   - group: localmesh.dev
   - version: v1alpha1
   - scope: Namespaced
   - spec fields: serviceName, namespace, localPort, developerSessionId
   - status fields: state, tunnelEndpoint, message

2. k8s/rbac.yaml
   ServiceAccount: localmesh-controller
   ClusterRole: localmesh-controller with rules to:
   - get/list/watch/patch/update: pods, services, deployments, replicasets
   - get/list/watch/create/update/patch/delete: localmeshintercepts (custom resource)
   - get/list/watch: namespaces
   ClusterRoleBinding: binds the above

3. k8s/controller-deployment.yaml
   - Deployment of localmesh/controller:latest
   - Uses ServiceAccount: localmesh-controller
   - Env: POSTGRES_URL, REDIS_URL from secrets
   - Resources: requests 256Mi/250m, limits 512Mi/500m
   - LivenessProbe: GET /health every 15s

4. k8s/api-deployment.yaml + Service
   - Deployment of localmesh/api:latest
   - Service type: ClusterIP, port 8080
   - Same env pattern

5. k8s/demo-services.yaml
   Deploy 3 simple demo microservices to demonstrate LocalMesh:
   - order-service: a simple HTTP service (use kennethreitz/httpbin as the image) on port 80, with label app=order-service
   - payment-service: same pattern
   - notification-service: same pattern
   Wire them up so order-service has env vars pointing to payment-service's ClusterIP, and payment-service points to notification-service. This simulates a real dependency chain.

6. scripts/demo.sh
   A bash script that:
   a. Applies all k8s manifests (kubectl apply -f k8s/)
   b. Waits for all deployments to be ready
   c. Prints a step-by-step demo guide:
      "Step 1: Connect LocalMesh → run: localmesh connect"
      "Step 2: Intercept payment-service → run: localmesh intercept payment-service --port 8080"
      "Step 3: Open dashboard → http://localhost:3000"
      "Step 4: Generate traffic → kubectl run curl --image=curlimages/curl -it --rm -- curl order-service/anything"
      "Step 5: Edit your local payment-service code and watch the change go live"
   d. Port-forwards the LocalMesh dashboard to localhost:3000

7. README.md (root)
   A proper project README with:
   - What LocalMesh is (2 paragraphs)
   - Architecture diagram (ASCII)
   - Prerequisites (Docker, kubectl, Java 21, Maven)
   - Quick start (5 commands from clone to running demo)
   - How it works (bullet points)
   - Tech stack table
   - Link to "Demo" section with GIF placeholder

Make the demo script idempotent (safe to run multiple times).
```

---

## Summary: What You'll Have

| Component | Tech | Lines of code (est.) |
|---|---|---|
| Controller | Java 21, Spring Boot 3, K8s Java Client | ~800 |
| CLI | Java 21, Picocli, gRPC | ~600 |
| Sidecar | Java 21, gRPC, plain HttpServer | ~400 |
| API | Java 21, SparkJava, jOOQ | ~500 |
| Dashboard | React 18, TypeScript, D3 | ~700 |
| K8s Manifests | YAML | ~300 |
| Tests | Spock, JUnit 5, Testcontainers | ~400 |
| **Total** | | **~3,700** |

## The Demo (What you show in the interview)

1. Open the dashboard — 3 demo services visible in the service map
2. Run `localmesh intercept payment-service --port 8080`
3. Show the intercepted service light up in the dashboard
4. Run your local service — a trivially modified version that adds an `X-LocalMesh: true` header
5. Generate traffic to order-service in the cluster
6. Watch the traffic flow through your laptop in real time on the dashboard
7. Modify your local service code, save — JVM hot-reloads
8. Generate traffic again — the response now reflects your local change
9. Run `localmesh disconnect` — traffic returns to the real pod, dashboard updates

That is an unforgettable 3-minute demo.
