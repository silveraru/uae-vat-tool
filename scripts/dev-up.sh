#!/usr/bin/env bash
# Starts everything for local development. Stop the services with: docker compose down
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p .data/sftp-inbox
docker compose up -d
until docker compose exec -T postgres pg_isready -U vat_super -d uaevat >/dev/null 2>&1; do sleep 1; done

[ -f backend/target/backend-0.1.0-SNAPSHOT.jar ] || ./mvnw -q -B -DskipTests package
[ -d frontend/node_modules ] || (cd frontend && npm install --no-audit --no-fund)

echo "Starting the backend on http://localhost:18090 (log: .data/backend.log)"
(cd backend && nohup java -jar target/backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev > ../.data/backend.log 2>&1 &)
echo "Starting the front end on http://localhost:5183 (log: .data/frontend.log)"
(cd frontend && nohup npx vite --port 5183 --strictPort > ../.data/frontend.log 2>&1 &)

echo "Waiting for the backend..."
until curl -sf http://localhost:18090/actuator/health >/dev/null; do sleep 2; done
echo "Ready: open http://localhost:5183 and sign in as admin, preparer, reviewer, partner, approver or auditor (password demo-pass-1)."
