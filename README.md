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

## Project Structure

```
sharding-replication/
├── pom.xml
├── docker-compose.yml
├── docker/
│   ├── init-primary.sh        ← Sets up replication user on each primary
│   └── init-replica.sh        ← pg_basebackup clone from primary
├── src/main/resources/
│   ├── application.yml
│   └── schema.sql             ← orders table + index
└── src/main/java/com/example/sharding/
    ├── ShardingReplicationApplication.java
    ├── context/
    │   ├── ShardContextHolder.java   ← ThreadLocal: shard index + role
    │   └── DataSourceKey.java        ← Composite key (shardIndex, Role)
    ├── routing/
    │   └── ShardReplicaRoutingDataSource.java  ← AbstractRoutingDataSource
    ├── config/
    │   ├── DataSourceConfig.java     ← 6 DataSources registered
    │   └── JpaConfig.java            ← Hibernate wired to routing DS
    ├── aspect/
    │   └── TransactionRoutingAspect.java  ← AOP: readOnly → REPLICA
    ├── entity/Order.java
    ├── repository/OrderRepository.java
    ├── dto/CreateOrderRequest.java
    ├── service/OrderService.java
    └── controller/OrderController.java
```

## How to Run

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### Steps

**1. Clone the repository**

```bash
git clone https://github.com/Jatinghataliya/sharding-replication.git
cd sharding-replication
```

**2. Start all 6 PostgreSQL nodes (3 primaries + 3 replicas)**

```bash
docker-compose up -d
```

Wait ~10 seconds for replicas to finish the base backup from their primaries.

**3. Build and run the Spring Boot application**

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080`.

## Full Routing Flow

Every request goes through three layers before a connection is picked:

```
HTTP Request
    │
    ▼
┌─────────────────────────────────────────────┐
│  AOP — TransactionRoutingAspect             │
│  Reads @Transactional(readOnly) flag        │
│  → readOnly=true  : setRole(REPLICA)        │
│  → readOnly=false : setRole(PRIMARY)        │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│  Service Layer — OrderService               │
│  Resolves shard: userId % 3                 │
│  → setShard(shardIndex)                     │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│  Hibernate → ShardReplicaRoutingDataSource  │
│  Calls determineCurrentLookupKey()          │
│  → Builds DataSourceKey(shardIndex, role)   │
│  → Spring resolves matching HikariCP pool   │
└─────────────────────────────────────────────┘
    │
    ├─── WRITE (@Transactional)          ──▶  🟢 Primary (shard N)
    └─── READ  (@Transactional readOnly) ──▶  🔵 Replica (shard N)
```

### Concrete Example

```
POST /api/orders  { userId: 101, amount: 250.00 }

  1. AOP          → readOnly=false → Role = PRIMARY
  2. Service      → 101 % 3 = 2   → Shard = 2
  3. Router       → DataSourceKey(2, PRIMARY)
  4. HikariCP     → shard2-primary (localhost:5434)
  5. Hibernate    → INSERT INTO orders ...

GET /api/orders/user/101

  1. AOP          → readOnly=true  → Role = REPLICA
  2. Service      → 101 % 3 = 2   → Shard = 2
  3. Router       → DataSourceKey(2, REPLICA)
  4. HikariCP     → shard2-replica (localhost:5438)
  5. Hibernate    → SELECT * FROM orders WHERE user_id = 101
```

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
