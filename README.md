# LocalMesh

> **Your laptop is a pod.**

LocalMesh is a developer tool that solves the #1 pain point of microservice development: you can't run 15 services locally, but you need them all to test your one change. LocalMesh lets a developer run **only their service** on their laptop — in their IDE, with hot reload, with their debugger attached — while all its upstream and downstream dependencies run in a shared Kubernetes cluster. From the cluster's perspective, the developer's laptop IS a deployed pod. Traffic flows in and out as if it were containerised and running in K8s.

The result: **sub-2-second feedback loops** on real cluster traffic, with zero mocking.

---

## Architecture

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
└────────────────────────────┼─────────────────────────────┘
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

---

## Prerequisites

- **Docker** + Docker Compose v2
- **Java 21** (Eclipse Temurin recommended)
- **Maven 3.9+** (or use the project's `./mvnw`)
- **kubectl** configured against your cluster
- **Node.js 20+** for the dashboard

---

## Quick Start

```bash
# 1. Clone and enter the repo
git clone https://github.com/your-org/localmesh && cd localmesh

# 2. Start local infrastructure
make dev-up

# 3. Build all Java modules
make build-all

# 4. Start the dashboard (in a new terminal)
make dashboard-install && make dashboard-dev

# 5. Deploy to your cluster and run the demo
bash scripts/demo.sh
```

---

## Developer Experience

```bash
# Connect your machine to the cluster
localmesh connect

# Intercept a service — route its traffic to your laptop
localmesh intercept payment-service --port 8080

# Run your service locally as normal
./mvnw spring-boot:run

# Every request hitting payment-service in the cluster
# now comes to YOUR JVM. Edit code, hot-reload, done.
localmesh status
localmesh disconnect
```

---

## How It Works

- `localmesh connect` establishes an authenticated gRPC tunnel to the in-cluster Controller
- `localmesh intercept <service>` creates a `LocalMeshIntercept` Custom Resource in Kubernetes
- The Controller watches for this CRD and injects a **sidecar proxy** into the target pod
- The sidecar intercepts inbound traffic via `iptables` and forwards it over the gRPC tunnel
- Your local CLI server receives the `TunnelFrame`, proxies it to `localhost:PORT`, and streams the response back
- Live metrics are published via Redis pub/sub → REST API → dashboard WebSocket
- `localmesh disconnect` removes the sidecar and restores the original pod

---

## Tech Stack

| Component        | Technology                                   |
|------------------|----------------------------------------------|
| Controller       | Java 21, Spring Boot 3, Kubernetes Java Client |
| CLI              | Java 21, Picocli, gRPC                       |
| Sidecar Proxy    | Java 21, plain HttpServer, gRPC              |
| REST API         | Java 21, SparkJava, jOOQ                     |
| Dashboard        | React 18, TypeScript, Vite, D3.js            |
| Database         | PostgreSQL 15 (via Flyway + jOOQ)            |
| Messaging        | Redis 7 (pub/sub + sorted sets)              |
| Transport        | gRPC / Protocol Buffers                      |
| Tests            | Spock, JUnit 5, Testcontainers               |

---

## Demo

> GIF placeholder — see `scripts/demo.sh` for a live walkthrough

```
Step 1: Open dashboard      →  http://localhost:3000
Step 2: Connect LocalMesh   →  localmesh connect
Step 3: Intercept service   →  localmesh intercept payment-service --port 8080
Step 4: Generate traffic    →  kubectl run curl --image=curlimages/curl -it --rm -- curl order-service/anything
Step 5: Watch it live       →  dashboard shows traffic flowing through your laptop
Step 6: Edit local code     →  JVM hot-reloads, change is live in <2 seconds
Step 7: Disconnect          →  localmesh disconnect
```
