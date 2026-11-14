#!/usr/bin/env bash
# Starts the local demo again from an empty database: backs up the current one to .data/backups,
# drops every table, rebuilds the backend and starts it, which builds the demo year again.
# Local development only. The Docker services must be running (scripts/dev-up.sh starts them).
set -euo pipefail
cd "$(dirname "$0")/.."

stamp=$(date +%Y%m%d-%H%M%S)
mkdir -p .data/backups
echo "Backing up the current database to .data/backups/uaevat-$stamp.dump"
docker compose exec -T postgres pg_dump -U vat_super -d uaevat -Fc > ".data/backups/uaevat-$stamp.dump"

# Only a Java process listening on the backend port is stopped.
pid=$(lsof -tiTCP:18090 -sTCP:LISTEN 2>/dev/null || true)
if [ -n "$pid" ] && ps -p "$pid" -o comm= | grep -q java; then
  echo "Stopping the backend (pid $pid)"
  kill "$pid"
  while kill -0 "$pid" 2>/dev/null; do sleep 1; done
fi

echo "Emptying the database"
docker compose exec -T postgres psql -q -U vat_super -d uaevat -v ON_ERROR_STOP=1 <<'SQL'
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION vat_owner;
GRANT USAGE ON SCHEMA public TO vat_app;
REVOKE CREATE ON SCHEMA public FROM vat_app;
SQL
rm -rf .data/demo

echo "Building the backend"
./mvnw -q -B -DskipTests package -pl backend -am

echo "Starting the backend (log: .data/backend.log)"
# Emptied first, so the wait below cannot find the last run's "ready" line.
: > .data/backend.log
(cd backend && nohup java -jar target/backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev >> ../.data/backend.log 2>&1 &)

echo "Building the demo year. This takes a few minutes."
until grep -qE "Demo year ready|APPLICATION FAILED TO START" .data/backend.log 2>/dev/null; do sleep 5; done
grep -E "Demo client .* (ready|stopped)|Demo year ready|APPLICATION FAILED" .data/backend.log | sed 's/^.*\] //'
