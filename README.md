# Ledger

A small double-entry ledger service. You can open accounts, move money between them, see an account's balance and statement, and check that every balance is correct.

Built with Java 21, Spring Boot, jOOQ, Flyway and PostgreSQL. Tested with Testcontainers.

## Running it

You need Docker and Java 21.

```bash
docker compose up -d     # start PostgreSQL
./gradlew bootRun        # start the app on http://localhost:8080
./gradlew test           # run the tests (starts its own PostgreSQL in Docker)
```

The database schema lives in `src/main/resources/db/migration` (Flyway). The jOOQ classes generated from it are committed in `src/generated/jooq`, so the project builds without a database. After changing the schema: start PostgreSQL, run the app once so Flyway applies the change, then run `./gradlew generateJooq` and commit the updated files.

## Key ideas

- **Money is stored in minor units** (pence) as whole numbers. `1050` means £10.50. No floating point anywhere.
- **Every transfer moves money from one account to another**, so money is never created or destroyed. Add up every balance and you always get `0`.
- **The SYSTEM account** (`00000000-0000-0000-0000-000000000001`) is where money enters the ledger. It is the only account allowed to go negative. To give an account money, transfer from SYSTEM to it.
- **Transfers are safe to retry.** Each request carries a `requestId`. Sending the same request again returns the original result and does not move money twice.

## APIs

### Open an account

`POST /accounts`

```json
{ "name": "Alice" }
```

Response `200`:

```json
{ "id": "7aca5939-4156-4093-905b-c452b9547f9d", "name": "Alice" }
```

### List accounts

`GET /accounts`

```json
[
  { "id": "00000000-0000-0000-0000-000000000001", "name": "System" },
  { "id": "7aca5939-4156-4093-905b-c452b9547f9d", "name": "Alice" }
]
```

### Get one account

`GET /accounts/{id}`

```json
{ "id": "7aca5939-4156-4093-905b-c452b9547f9d", "name": "Alice" }
```

### Get an account's balance

`GET /accounts/{id}/balance`

Returns the balance in pence as a plain number:

```json
7000
```

### Make a transfer

`POST /transfers`

```json
{
  "fromId": "7aca5939-4156-4093-905b-c452b9547f9d",
  "toId": "d51ec0bb-fc4d-47b2-9591-d793e89fe9d0",
  "requestId": "pay-bob-001",
  "amountMinor": 3000
}
```

`requestId` is chosen by the caller and must be unique per transfer. Reuse it only when retrying the same transfer.

Response `200` when the money moved:

```json
{
  "transferId": "0be1f91f-1333-4584-aaaf-d8da07a6a962",
  "requestId": "pay-bob-001",
  "fromAccountId": "7aca5939-4156-4093-905b-c452b9547f9d",
  "toAccountId": "d51ec0bb-fc4d-47b2-9591-d793e89fe9d0",
  "amountMinor": 3000,
  "status": "POSTED",
  "rejectionReason": null
}
```

Response `422` when the sender does not have enough money. The attempt is still saved:

```json
{
  "transferId": "1b147a95-8146-4685-9246-a4ab22b7ab5e",
  "requestId": "pay-bob-002",
  "fromAccountId": "7aca5939-4156-4093-905b-c452b9547f9d",
  "toAccountId": "d51ec0bb-fc4d-47b2-9591-d793e89fe9d0",
  "amountMinor": 999999,
  "status": "REJECTED",
  "rejectionReason": "INSUFFICIENT_FUNDS"
}
```

What happens when you send a `requestId` again:

| Situation | Result |
|---|---|
| Same `requestId`, same accounts and amount | The original response, nothing moves twice |
| Same `requestId`, different accounts or amount | `422`, the key is already used |
| A rejected `requestId`, retried after funds arrive | Still rejected. Use a new `requestId` |

### Get an account statement

`GET /accounts/{id}/statement`

Optional query parameters:

| Parameter | Meaning | Default |
|---|---|---|
| `from` | Start time, included. ISO format, e.g. `2026-10-01T00:00:00Z` | 30 days before `to` |
| `to` | End time, not included | now |
| `limit` | Entries per page, 1 to 500 | 100 |
| `cursor` | `nextCursor` from the previous page | first page |

```json
{
  "accountId": "7aca5939-4156-4093-905b-c452b9547f9d",
  "from": "2026-09-05T00:00:00Z",
  "to": "2026-10-05T00:00:00Z",
  "openingBalanceMinor": 0,
  "closingBalanceMinor": 7000,
  "entries": [
    {
      "transferId": "50f73e58-4efc-4a82-b5f7-fdbf0924145f",
      "transactionTime": "2026-10-05T00:11:44.146881Z",
      "transactionDescription": "Transfer from System",
      "counterpartyAccountId": "00000000-0000-0000-0000-000000000001",
      "transactionAmountMinor": 10000,
      "isCredit": true,
      "runningBalanceMinor": 10000
    },
    {
      "transferId": "0be1f91f-1333-4584-aaaf-d8da07a6a962",
      "transactionTime": "2026-10-05T00:11:44.200182Z",
      "transactionDescription": "Transfer to Bob",
      "counterpartyAccountId": "d51ec0bb-fc4d-47b2-9591-d793e89fe9d0",
      "transactionAmountMinor": 3000,
      "isCredit": false,
      "runningBalanceMinor": 7000
    }
  ],
  "nextCursor": null
}
```

- `openingBalanceMinor` is the balance at `from`, and `closingBalanceMinor` is the balance at `to`.
- Rejected transfers do not appear, because no money moved.
- When `nextCursor` is not `null`, there are more entries. Call again with `cursor=<nextCursor>`, and pass the same `from` and `to` as the first page so the window does not shift.

### Check every balance

`GET /accounts/balances/verify`

Recalculates every balance from the transfer history and compares it with the stored balance. Read-only and safe to run any time.

```json
{
  "consistent": true,
  "accountsChecked": 3,
  "mismatchCount": 0,
  "ledgerNetMinor": 0,
  "accountMismatches": []
}
```

If something is wrong, `consistent` is `false` and each bad account is listed (at most 100):

```json
{
  "accountId": "7aca5939-4156-4093-905b-c452b9547f9d",
  "cachedBalanceMinor": 4000,
  "derivedBalanceMinor": 5000,
  "differenceMinor": -1000
}
```

`ledgerNetMinor` is the sum of all balances. It must always be `0`.

### Rebuild every balance

`POST /accounts/balances/rebuild`

Throws away the stored balances and recalculates them all from the transfer history. Transfers wait while it runs. Reads keep working.

```json
{
  "accountsRebuilt": 3,
  "accountsCorrected": 1,
  "corrections": [
    {
      "accountId": "7aca5939-4156-4093-905b-c452b9547f9d",
      "cachedBalanceMinor": 4000,
      "derivedBalanceMinor": 5000,
      "differenceMinor": -1000
    }
  ],
  "consistentAfterRebuild": true
}
```

`corrections` shows each balance before (`cachedBalanceMinor`) and after (`derivedBalanceMinor`) the rebuild.

### Errors

Errors share one shape:

```json
{
  "timestamp": "2026-10-05T00:19:00.228772Z",
  "status": 404,
  "error": "Not Found",
  "message": "Account not found: 9051082d-ce50-45a1-83d5-30805943afe1",
  "path": "/transfers"
}
```

| Status | When |
|---|---|
| `400` | Bad input: a malformed id or date, sending money to the same account, `from` after `to` |
| `404` | The account does not exist |
| `422` | Not enough money (with the transfer as the body), or a `requestId` reused for a different transfer |

## How it works

### Data

- `accounts`: id, name, and type (`SYSTEM` or `USER`).
- `transfers`: one row per transfer attempt, with sender, receiver, amount, status (`POSTED` or `REJECTED`) and the `requestId`. Rows are never changed after they are written. This is the source of truth.
- `balances`: the current balance of each account. It is a fast-read copy of what the transfers add up to, and can be rebuilt from `transfers` at any time.

### Making a transfer

Everything below happens in one database transaction:

1. Lock both accounts' balance rows (`SELECT ... FOR UPDATE`). Any other transfer touching these accounts now waits.
2. Check the sender has enough money.
3. Insert the transfer row with `ON CONFLICT (idempotency_key) DO NOTHING` (the `requestId` is stored as `idempotency_key`). If it already exists, return the original transfer instead.
4. If the transfer is `POSTED`, subtract from the sender's balance and add to the receiver's.

### Why concurrent requests are safe

- **No overdrafts.** Without the lock, two transfers could both read £100, both pass the check, and leave the account negative. With the lock, the second one waits and sees the updated balance.
- **No deadlocks.** The two balance rows are always locked in account id order. If Alice pays Bob while Bob pays Alice, both transfers lock the same row first, so neither can block the other forever.
- **No duplicate transfers.** The unique `requestId` is enforced by the database. Two identical requests arriving together still produce one transfer.

The tests prove each of these by running 10 to 20 transfers at the same time.

### Why `verify` needs no locks

PostgreSQL gives each query a consistent snapshot of the data. A transfer writes its row and both balances in one transaction, so a snapshot sees all of it or none of it. `verify` runs on one snapshot, so it never reports a false mismatch while transfers are happening.

## Load test

The `loadtest` folder has a [Locust](https://locust.io) script. Before the run it creates and funds 200 accounts. After the run it calls `verify` to check that no money was lost or duplicated.

```bash
cd loadtest
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
SCENARIO=spread .venv/bin/locust -f locustfile.py --headless --processes 6 -u 200 -r 200 -t 45s --host http://localhost:8080
SCENARIO=hot    .venv/bin/locust -f locustfile.py --headless -u 50 -r 50 -t 60s --host http://localhost:8080
```

Two scenarios:

- **spread**: each transfer goes between two random accounts, so transfers rarely wait for each other.
- **hot**: every transfer comes out of the same account, so they all queue for that one account's lock.

All results are from an Apple M3 Pro laptop (11 cores). The app, PostgreSQL (in Docker) and Locust all shared the same machine, so treat the numbers as relative, not absolute. Each figure is a single run.

**Connection pool size.** The database connection pool caps how many transfers can be in the database at once. The spread scenario (6 load processes, 200 users, 45 seconds) was run at five pool sizes:

| Pool size | Transfers per second | Median | p95 | p99 |
|---|---|---|---|---|
| 10 (Spring Boot default) | 1,640 | 99 ms | 200 ms | 360 ms |
| 20 | 2,557 | 69 ms | 120 ms | 150 ms |
| **30 (chosen)** | **2,903** | **61 ms** | **99 ms** | **130 ms** |
| 50 | 2,580 | 59 ms | 150 ms | 260 ms |
| 90 | 2,536 | 48 ms | 230 ms | 290 ms |

- Going from 10 to 30 connections gave 77% more throughput, and p99 fell from 360 ms to 130 ms. With 10 connections, transfers were queueing for a free connection.
- Past 30 it gets worse. Throughput drops slightly and the slowest requests get much slower (p99 doubles). PostgreSQL runs one process per connection, and once there are more active connections than CPU cores they mostly compete with each other.
- The app now uses 30 (`spring.datasource.hikari.maximum-pool-size`), about 3x the core count. A pool of 100 is not possible anyway: PostgreSQL allows 100 connections by default.

**Busy account.** Both scenarios with the same setup (1 load process, 50 users, 60 seconds, pool size 10):

| Scenario | Transfers per second | Median | p99 |
|---|---|---|---|
| spread | ~1,860 | 19 ms | 40 ms |
| hot | ~514 | 90 ms | 150 ms |

Every hot transfer waits for the same account lock, held about 1.9 ms each, which caps one account at about 500 transfers per second. A bigger pool does not help here, because the wait is for the lock, not for a connection. This is the "busy accounts" limit below.

**Correct under load.** Across all runs, over 800,000 transfers ran concurrently with 0 errors. After every run `verify` found no mismatches and the ledger still summed to 0.

## Known limits

- **Busy accounts are a bottleneck.** Every transfer touching an account locks its row, so transfers on the same account run one at a time. The SYSTEM account is the busiest. At large scale you would split a busy account into several balance rows.
- **One database.** Everything runs on a single PostgreSQL instance, measured at about 2,900 transfers per second on a laptop (see Load test). Far beyond that you would split accounts across databases or use a purpose-built ledger database.
- **The admin endpoints have no auth.** `verify` and `rebuild` should be internal-only in a real deployment.
- **Statement default window moves.** Without `to`, "now" is recalculated on each request. Pass explicit `from`/`to` when paging.
