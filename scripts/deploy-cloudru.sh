#!/bin/bash
set -e

echo "=== MMVB Trading Bot — Deploy to Cloud.ru ==="

# Configuration
REGISTRY="cr.cloud.ru"
IMAGE="mmvb-trading-bot"
TAG="${1:-latest}"

echo "Building image..."
docker build -t $REGISTRY/$IMAGE:$TAG .

echo "Pushing to Cloud.ru Container Registry..."
docker push $REGISTRY/$IMAGE:$TAG

echo "Updating Kubernetes deployment..."
kubectl set image deployment/mmvb-bot bot=$REGISTRY/$IMAGE:$TAG -n mmvb-trading
kubectl rollout status deployment/mmvb-bot -n mmvb-trading

echo "Deployment complete!"
kubectl get pods -n mmvb-trading
