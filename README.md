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
├── src/main/java/com/example/sharding/
│   ├── ShardingReplicationApplication.java
│   ├── context/
│   │   ├── ShardContextHolder.java        ← ThreadLocal: shard index + role
│   │   └── DataSourceKey.java             ← Composite key (shardIndex, Role)
│   ├── routing/
│   │   └── ShardReplicaRoutingDataSource.java  ← AbstractRoutingDataSource
│   ├── config/
│   │   ├── DataSourceConfig.java          ← 6 DataSources registered
│   │   ├── JpaConfig.java                 ← Hibernate wired to routing DS
│   │   └── SwaggerConfig.java             ← OpenAPI / Swagger UI config
│   ├── aspect/
│   │   └── TransactionRoutingAspect.java  ← AOP: readOnly → REPLICA
│   ├── entity/Order.java
│   ├── repository/OrderRepository.java
│   ├── dto/CreateOrderRequest.java
│   ├── service/OrderService.java
│   └── controller/
│       ├── OrderController.java
│       └── GlobalExceptionHandler.java    ← Maps RuntimeException → 500 JSON
└── src/test/java/com/example/sharding/
    ├── suite/
    │   ├── ShardingTestSuite.java         ← Master suite (all 67 tests)
    │   ├── UnitTestSuite.java             ← Unit tests only (49 tests)
    │   ├── ApiTestSuite.java              ← MockMvc tests only (12 tests)
    │   └── LoadTestSuite.java             ← Load tests only (6 tests)
    ├── context/
    │   ├── ShardContextHolderTest.java    ← 8 tests
    │   └── DataSourceKeyTest.java         ← 8 tests
    ├── config/
    │   └── DataSourceConfigTest.java      ← 15 tests
    ├── aspect/
    │   └── TransactionRoutingAspectTest.java  ← 6 tests
    ├── service/
    │   └── OrderServiceTest.java          ← 12 tests
    ├── controller/
    │   └── OrderControllerTest.java       ← 12 tests
    ├── load/
    │   └── LoadTest.java                  ← 6 tests
    └── performance/
        └── PerformanceTest.java           ← JMH benchmarks (run separately)
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

---

## Test Suite

### Suite Structure

```
ShardingTestSuite  (67 tests — master suite)
│
├── UnitTestSuite  (49 tests)
│   ├── ShardContextHolderTest      8  ThreadLocal isolation, defaults, cross-thread
│   ├── DataSourceKeyTest           8  equals, hashCode, toString, all combos unique
│   ├── DataSourceConfigTest       15  shard resolution, distribution, edge cases
│   ├── TransactionRoutingAspectTest 6  role logic, defaults, overwrite, clear
│   └── OrderServiceTest           12  CRUD, shard routing, not-found exceptions
│
├── ApiTestSuite   (12 tests)
│   └── OrderControllerTest        12  MockMvc: 5 endpoints × happy + error paths
│
└── LoadTestSuite  (6 tests)
    └── LoadTest                    6  throughput, 500 threads, mixed R/W, distribution
```

### Test Results (last run)

| Suite | Tests | Pass | Time |
|---|---|---|---|
| `ShardContextHolderTest` | 8 | ✅ | ~0.09s |
| `DataSourceKeyTest` | 8 | ✅ | ~0.02s |
| `DataSourceConfigTest` | 15 | ✅ | ~0.08s |
| `TransactionRoutingAspectTest` | 6 | ✅ | ~0.01s |
| `OrderServiceTest` | 12 | ✅ | ~1.4s |
| `OrderControllerTest` | 12 | ✅ | ~3.0s |
| `LoadTest` | 6 | ✅ | ~0.2s |
| **Total** | **67** | **✅ 0 failures** | **~5s** |

### Load Test Benchmarks

```
Sequential:  1,000 creates in ~35ms  → ~28,000 req/s
Concurrent:  500/500 threads succeeded without errors
Mixed R/W:   200 interleaved reads + writes, 0 failures
Shard dist:  1000 / 1000 / 1000 across 3 shards (perfect even split)
```

### Running the Tests

```bash
# Full master suite (all 67 tests)
mvn test -Dtest=ShardingTestSuite

# Unit tests only (49 tests, ~2s)
mvn test -Dtest=UnitTestSuite

# API / MockMvc tests only (12 tests)
mvn test -Dtest=ApiTestSuite

# Load / concurrency tests only (6 tests)
mvn test -Dtest=LoadTestSuite

# JMH performance benchmarks (run separately — ~60s)
mvn test -Dtest=PerformanceTest -DfailIfNoTests=false

# Full Maven lifecycle — all tests except JMH
mvn test
```

### Swagger / OpenAPI

Once the application is running:

| URL | Description |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Interactive Swagger UI |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI JSON spec |
