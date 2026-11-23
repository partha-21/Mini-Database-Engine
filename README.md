# MiniDB Engine (Java)

A production-grade relational database engine written from scratch in pure Java with **zero third-party libraries or frameworks**. Features a hand-written SQL compiler and recursive descent parser, dual pluggable storage engines (**Clustered B+Tree** and **Log-Structured Merge Tree** with Bloom filters and compaction), **ARIES-style Write-Ahead Logging (WAL)** with crash recovery, **strict two-phase locking (2PL) serializable transactions**, and a modern **Interactive Web Studio & REST API**.

---

## Highlights & Features

- **Dual Pluggable Storage Engines**:
  - **B+Tree Engine**: Clustered 64-bit key index with 4 KB fixed pages, count- and byte-aware node splitting, borrow/merge rebalancing, root shrinking, and persistent free-page recycling via an LRU no-steal buffer pool with journaled crash-atomic checkpoints.
  - **LSM-Tree Engine**: In-memory MemTable, append-only immutable SSTables, bit-vector **Bloom Filters**, and tiered compaction with tombstone garbage collection and crash-safe file numbering.
- **ACID Transactions**:
  - **Atomicity**: Complete transaction rollback support with compensation log records (CLRs) and atomic multi-row statement execution.
  - **Consistency**: Primary key uniqueness, column schema enforcement, row byte bounds validation, and machine-verified B+Tree structural invariants.
  - **Isolation**: Serializable isolation powered by strict two-phase locking (2PL). Readers and writers are protected against dirty reads, non-repeatable reads, and phantoms.
  - **Durability**: Append-only Write-Ahead Log (WAL) with 32-bit CRC framing. Committed transactions are fsynced to disk before acknowledgement.
- **Crash Recovery (ARIES)**:
  - Startup recovery analyzes log history, re-applies committed operations (Redo / repeat history), and rolls back in-flight uncommitted transactions (Undo) using before-images.
  - Detects and handles torn-tail writes at EOF and halts on mid-log corruption.
- **SQL Studio UI & HTTP REST API**:
  - Embedded HTTP server with a professional developer UI (Dark Zinc aesthetic inspired by Linear / Vercel / Supabase).
  - Multi-statement SQL batch execution, intelligent error analysis with one-click suggested fixes, execution plan inspector, and persistent query history.
- **100% Dependency-Free**:
  - No external database libraries, parsers, HTTP frameworks, or CSS/JS CDNs. Builds with standard `javac` on JDK 17+.

---

## Quick Start

### 1. Build & Run Locally

```bash
# Compile engine sources (requires JDK 17+)
./build.sh

# Start the interactive Web Studio on localhost:8088
java -cp out minidb.Main server 8088

# Open interactive shell (REPL)
java -cp out minidb.Main repl minidb-data

# Run B+Tree vs LSM-Tree micro-benchmarks
java -cp out minidb.Main bench 30000

# Execute full regression test suite (48 tests)
./test.sh
```

### 2. Run with Docker

```bash
# Build Docker image
docker build -t minidb .

# Run container on port 8080
docker run -p 8080:8080 minidb
```

Access the studio at `http://localhost:8080`.

---

## Deploying to Railway

MiniDB is pre-configured for **1-click deployment on Railway**:

1. Log in to [Railway](https://railway.app).
2. Click **New Project** -> **Deploy from GitHub repo**.
3. Select this repository: `partha-21/MYSQL-Database-Engine`.
4. Railway will automatically detect the `Dockerfile` and `railway.json`.
5. Once deployed, click on the service -> **Settings** -> **Networking** -> **Generate Domain** to get a public URL for your live MiniDB Studio!

---

## SQL Dialect & Syntax

```sql
-- Create tables with explicit engine selection
CREATE TABLE users (id INT, name TEXT, age INT) USING BTREE;
CREATE TABLE events (id INT, kind TEXT) USING LSM;

-- Atomic multi-row inserts
INSERT INTO users VALUES (1, 'Alice', 30), (2, 'Bob', 25), (3, 'Charlie', 35);

-- Point lookups and index range scans
SELECT * FROM users WHERE id = 2;
SELECT name, age FROM users WHERE id >= 1 AND id <= 3 ORDER BY age DESC LIMIT 2;

-- Updates and Deletes
UPDATE users SET age = 31 WHERE name = 'Alice';
DELETE FROM users WHERE id = 3;

-- Aggregations
SELECT COUNT(*) FROM users WHERE age >= 25;

-- Strict serializable transactions
BEGIN;
INSERT INTO users VALUES (4, 'Diana', 28);
UPDATE users SET age = 29 WHERE id = 4;
COMMIT; -- or ROLLBACK;

-- Planner inspections & engine diagnostics
EXPLAIN SELECT * FROM users WHERE id >= 2 AND id <= 4;
SHOW TABLES;
SHOW STATS;
CHECKPOINT;
```

---

## Architecture Overview

```
                      ┌────────────────────────┐
                      │    SQL Query Text      │
                      └───────────┬────────────┘
                                  │
                                  ▼
                      ┌────────────────────────┐
                      │     Lexer & Parser     │
                      └───────────┬────────────┘
                                  │ AST
                                  ▼
                      ┌────────────────────────┐
                      │     QueryExecutor      │
                      │ (Planner, 2PL Lock Mgr)│
                      └───────────┬────────────┘
                                  │
                  ┌───────────────┴───────────────┐
                  ▼                               ▼
       ┌─────────────────────┐         ┌─────────────────────┐
       │   B+Tree Engine     │         │   LSM-Tree Engine   │
       │ (Clustered Index)   │         │ (MemTable + SSTable)│
       └──────────┬──────────┘         └──────────┬──────────┘
                  │                               │
                  ▼                               ▼
       ┌─────────────────────┐         ┌─────────────────────┐
       │ BufferPool (4KB)    │         │ Bloom Filters &     │
       │ LRU + Journal Check │         │ Tiered Compaction   │
       └──────────┬──────────┘         └──────────┬──────────┘
                  │                               │
                  └───────────────┬───────────────┘
                                  │
                                  ▼
                      ┌────────────────────────┐
                      │ WAL & Recovery Manager │
                      │  (ARIES Redo/Undo log) │
                      └────────────────────────┘
```

---

## Benchmark Comparison (30,000 Operations)

| Workload | B+Tree Engine | LSM-Tree Engine | Primary Tradeoff |
| :--- | :--- | :--- | :--- |
| **Insert (Random Keys)** | ~80,000 ops/s | **~430,000 ops/s** | LSM appends in-memory; B+Tree splits pages on disk |
| **Point Read (Key Exists)** | ~110,000 ops/s | **~280,000 ops/s** | Cache locality vs MemTable/SSTable bloom check |
| **Point Read (Key Absent)** | ~320,000 ops/s | **~5,800,000 ops/s** | Bloom filter eliminates disk I/O completely |
| **Range Scan (100 Keys)** | **~38,000 scans/s** | ~11,000 scans/s | B+Tree follows leaf pointers; LSM merges multiple runs |

---

## Automated Test Coverage (48 Tests)

MiniDB includes a comprehensive unit and regression test harness covering:
- **Lexer & Parser**: Keywords, token boundaries, SQL comments (`--` and `/* */`), operator precedence, malformed input handling.
- **B+Tree Invariants**: 20,000 randomized operations vs oracle model, variable byte splits, delete borrow/merge rebalancing, free page recycling.
- **Buffer Pool**: Journal crash recovery, torn-page repair, LRU clean page eviction, dirty page pinning.
- **LSM-Tree**: Bloom filter false positive rates, 15,000 operations vs oracle, tiered compaction, tombstone garbage collection across restarts.
- **Write-Ahead Log**: CRC validation, EOF torn-tail ignoring, mid-log corruption detection.
- **Concurrency & Recovery**: Strict 2-phase locking, concurrent fund transfers, ARIES redo/undo verification, automatic checkpointing.

```bash
./test.sh
# 48 passed, 0 failed
```

---

## License

MIT License. Built for educational and systems architecture study.
