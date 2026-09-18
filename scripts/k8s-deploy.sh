#!/usr/bin/env bash
# =============================================================================
# LocalMesh — Full Kubernetes Deployment Script
# Run this after: kubectl is configured against your OKE cluster
# Usage: bash scripts/k8s-deploy.sh
# =============================================================================
set -euo pipefail

CONTROLLER_IMAGE="ghcr.io/mrugajaj/localmesh-controller:latest"
API_IMAGE="ghcr.io/mrugajaj/localmesh-api:latest"

echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║       LocalMesh — Kubernetes Deployment      ║"
echo "╚══════════════════════════════════════════════╝"
echo ""

# ── Verify kubectl is connected ──────────────────────────────────────────────
echo "▶ Checking cluster connection..."
kubectl cluster-info || { echo "ERROR: kubectl not connected to a cluster"; exit 1; }

# ── Check secrets are filled in ──────────────────────────────────────────────
echo "▶ Checking secrets..."
if grep -q "REPLACE_WITH" k8s/controller-deployment.yaml; then
  echo ""
  echo "❌  STOP: You have not filled in your secrets yet!"
  echo "   Edit k8s/controller-deployment.yaml and replace:"
  echo "     REPLACE_WITH_NEON_JDBC_URL      → your Neon PostgreSQL JDBC URL"
  echo "     REPLACE_WITH_NEON_USER          → your Neon DB username"
  echo "     REPLACE_WITH_NEON_PASSWORD      → your Neon DB password"
  echo "     REPLACE_WITH_UPSTASH_REDIS_URL  → your Upstash Redis URL"
  echo ""
  exit 1
fi

# ── Install CRD ──────────────────────────────────────────────────────────────
echo "▶ Installing LocalMeshIntercept CRD..."
kubectl apply -f k8s/crd-localmeshintercept.yaml

# ── RBAC ─────────────────────────────────────────────────────────────────────
echo "▶ Applying RBAC..."
kubectl apply -f k8s/rbac.yaml

# ── Deploy Controller ─────────────────────────────────────────────────────────
echo "▶ Deploying Controller..."
kubectl apply -f k8s/controller-deployment.yaml

# ── Expose Controller via LoadBalancer ───────────────────────────────────────
echo "▶ Creating LoadBalancer for Controller gRPC..."
kubectl apply -f k8s/controller-lb.yaml

# ── Deploy API (optional in-cluster API) ─────────────────────────────────────
echo "▶ Deploying REST API..."
kubectl apply -f k8s/api-deployment.yaml

# ── Deploy Demo Services ──────────────────────────────────────────────────────
echo "▶ Deploying demo microservices..."
kubectl apply -f k8s/demo-services.yaml

# ── Wait for rollout ──────────────────────────────────────────────────────────
echo ""
echo "▶ Waiting for Controller to be ready..."
kubectl rollout status deployment/localmesh-controller --timeout=120s

echo "▶ Waiting for API to be ready..."
kubectl rollout status deployment/localmesh-api --timeout=120s

# ── Print status ──────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║              Deployment Complete!            ║"
echo "╚══════════════════════════════════════════════╝"
echo ""
kubectl get pods
echo ""
echo "▶ Getting Controller external IP (may take 2-3 min for LB provisioning)..."
echo "  Run this to get it: kubectl get service localmesh-controller-lb"
echo ""
echo "✅ Done! Use the EXTERNAL-IP in your localmesh CLI config."
