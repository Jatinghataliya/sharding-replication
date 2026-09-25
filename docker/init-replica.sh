#!/bin/bash
# init-replica.sh
# Runs as the replica container entrypoint.
# Waits for the primary to be ready, then performs pg_basebackup to clone it.

set -e

PRIMARY_HOST="$1"
APP_USER="$2"
APP_PASSWORD="$3"

echo ">>> Waiting for primary at $PRIMARY_HOST to be ready..."
until pg_isready -h "$PRIMARY_HOST" -U replicator; do
    echo "    Primary not ready, retrying in 2s..."
    sleep 2
done

echo ">>> Primary is ready. Running pg_basebackup..."

# Clone the primary with -R flag to auto-generate standby.signal + postgresql.auto.conf
PGPASSWORD=replication_secret pg_basebackup \
    -h "$PRIMARY_HOST" \
    -U replicator \
    -D "$PGDATA" \
    -Fp \
    -Xs \
    -R \
    -P

echo ">>> Base backup complete. Starting replica..."

# Start PostgreSQL as a hot standby
exec docker-entrypoint.sh postgres \
    -c hot_standby=on \
    -c hot_standby_feedback=on
