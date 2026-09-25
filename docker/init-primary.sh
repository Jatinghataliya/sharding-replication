#!/bin/bash
# init-primary.sh
# Runs inside the primary container on first boot.
# Creates the replication user and updates pg_hba.conf to allow replication connections.

set -e

echo ">>> Configuring primary for streaming replication..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create dedicated replication user
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator') THEN
            CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replication_secret';
        END IF;
    END
    \$\$;
EOSQL

# Allow replication connections from any host (safe for local Docker network)
echo "host replication replicator all md5" >> "$PGDATA/pg_hba.conf"

echo ">>> Primary replication setup complete."
