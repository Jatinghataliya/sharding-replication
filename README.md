# Sharding + Replication with Hibernate & PostgreSQL

A Spring Boot application demonstrating **database sharding with streaming replication** using Hibernate, Spring Data JPA, and PostgreSQL.

## Architecture

```
3 Shards × (1 Primary + 1 Replica) = 6 PostgreSQL instances

Shard 0 → Primary :5432 | Replica :5435
Shard 1 → Primary :5433 | Replica :5437
Shard 2 → Primary :5434 | Replica :5438
```

- **Writes** (`@Transactional`)               → routed to the **Primary**
- **Reads** (`@Transactional(readOnly=true)`) → routed to the **Replica**
- **Shard key**: `userId` — hash-based: `shard = userId % 3`

## Key Components

| Class | Role |
|---|---|
| `ShardContextHolder` | ThreadLocal — holds shard index + role per request |
| `DataSourceKey` | Composite key `(shardIndex, Role)` for DataSource lookup |
| `ShardReplicaRoutingDataSource` | Extends `AbstractRoutingDataSource`, resolves key |
| `DataSourceConfig` | Registers all 6 DataSources |
| `JpaConfig` | Wires Hibernate to the routing DataSource |
| `TransactionRoutingAspect` | AOP — auto-sets PRIMARY/REPLICA from `@Transactional` |
| `OrderService` | Sets shard index; delegates reads/writes to repository |

## Getting Started

### 1. Start PostgreSQL (Docker)

```bash
docker-compose up -d
```

### 2. Run the Application

```bash
mvn spring-boot:run
```

### 3. Try the API

```bash
# Create an order (writes to shard primary)
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": 101, "amount": 250.00}'

# Get orders for a user (reads from shard replica)
curl http://localhost:8080/api/orders/user/101

# Check which shard a userId maps to
curl "http://localhost:8080/api/orders/shard-info?userId=101"

# Update order status
curl -X PATCH "http://localhost:8080/api/orders/1/status?userId=101&status=SHIPPED"
```

## API Endpoints

| Method | URL | Description |
|---|---|---|
| `POST` | `/api/orders` | Create order → PRIMARY |
| `GET` | `/api/orders/user/{userId}` | Get user orders → REPLICA |
| `GET` | `/api/orders/{orderId}?userId=` | Get single order → REPLICA |
| `PATCH`| `/api/orders/{orderId}/status` | Update status → PRIMARY |
| `GET` | `/api/orders/shard-info?userId=` | Show shard mapping |

## How Routing Works

```
Request → AOP detects @Transactional(readOnly)
        → ShardContextHolder.setRole(REPLICA or PRIMARY)
        → Service sets ShardContextHolder.setShard(userId % 3)
        → Hibernate calls ShardReplicaRoutingDataSource.determineCurrentLookupKey()
        → Returns DataSourceKey(shardIndex, role)
        → Spring resolves the matching HikariCP DataSource
        → Query executes on the correct PostgreSQL node
```
