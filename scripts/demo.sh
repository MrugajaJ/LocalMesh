#!/usr/bin/env bash
# LocalMesh Demo Script
# Idempotent — safe to run multiple times.
#
# Usage: bash scripts/demo.sh
#
set -euo pipefail

NAMESPACE="${NAMESPACE:-default}"
DASHBOARD_LOCAL_PORT="${DASHBOARD_LOCAL_PORT:-3000}"
API_LOCAL_PORT="${API_LOCAL_PORT:-8080}"

YELLOW='\033[0;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

header() { echo -e "\n${BOLD}${CYAN}► $1${RESET}"; }
ok()     { echo -e "  ${GREEN}✓ $1${RESET}"; }
info()   { echo -e "  ${YELLOW}→ $1${RESET}"; }

# ── 1. Apply manifests ────────────────────────────────────────────────────────
header "Applying Kubernetes manifests..."
kubectl apply -f k8s/crd-localmeshintercept.yaml
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/controller-deployment.yaml
kubectl apply -f k8s/api-deployment.yaml
kubectl apply -f k8s/demo-services.yaml
ok "Manifests applied"

# ── 2. Wait for deployments ───────────────────────────────────────────────────
header "Waiting for deployments to become ready..."
kubectl rollout status deployment/localmesh-controller -n "$NAMESPACE" --timeout=120s
kubectl rollout status deployment/localmesh-api        -n "$NAMESPACE" --timeout=120s
kubectl rollout status deployment/order-service        -n "$NAMESPACE" --timeout=60s
kubectl rollout status deployment/payment-service      -n "$NAMESPACE" --timeout=60s
kubectl rollout status deployment/notification-service -n "$NAMESPACE" --timeout=60s
ok "All deployments ready"

# ── 3. Port-forward dashboard ─────────────────────────────────────────────────
header "Port-forwarding LocalMesh API to localhost:${API_LOCAL_PORT}..."
kubectl port-forward svc/localmesh-api "${API_LOCAL_PORT}":8080 -n "$NAMESPACE" &
PF_PID=$!
trap "kill $PF_PID 2>/dev/null || true" EXIT
sleep 2
ok "API available at http://localhost:${API_LOCAL_PORT}"

# ── 4. Demo guide ─────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}          LocalMesh Live Demo Guide                           ${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
echo -e "  ${GREEN}Step 1:${RESET} Open the dashboard"
echo -e "          ${CYAN}cd dashboard && npm run dev${RESET}"
echo -e "          Then open: ${BOLD}http://localhost:5173${RESET}"
echo ""
echo -e "  ${GREEN}Step 2:${RESET} Connect LocalMesh to the cluster"
echo -e "          ${CYAN}localmesh connect${RESET}"
echo ""
echo -e "  ${GREEN}Step 3:${RESET} Intercept payment-service → route traffic to your laptop"
echo -e "          ${CYAN}localmesh intercept payment-service --port 8080${RESET}"
echo ""
echo -e "  ${GREEN}Step 4:${RESET} Run your local service (in another terminal)"
echo -e "          ${CYAN}./mvnw spring-boot:run   # or your service's start command${RESET}"
echo ""
echo -e "  ${GREEN}Step 5:${RESET} Generate traffic to order-service"
echo -e "          ${CYAN}kubectl run curl --image=curlimages/curl -it --rm -- \\"
echo -e "            curl order-service/anything${RESET}"
echo ""
echo -e "  ${GREEN}Step 6:${RESET} Watch traffic flow through your laptop on the dashboard"
echo ""
echo -e "  ${GREEN}Step 7:${RESET} Edit your local service code — JVM hot-reloads in <2s"
echo ""
echo -e "  ${GREEN}Step 8:${RESET} Disconnect and restore the cluster pod"
echo -e "          ${CYAN}localmesh disconnect${RESET}"
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
info "API is running. Press Ctrl+C to stop port-forwarding."
wait $PF_PID
