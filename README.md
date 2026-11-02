# Besu Etherscan-compatible proxy

A lightweight Java 21 service for a private Besu QBFT network. It exposes an
allowlisted Blockscout/Etherscan `GET` or form-encoded `POST /api` interface,
an internal operations explorer, and a bounded contract indexer. It does not
require PostgreSQL, Redis, Spring, Node.js, or Elixir.

Current chain state comes directly from Besu JSON-RPC. Allowlisted contract
events and top-level transactions are indexed in SQLite. In Docker, that
SQLite database lives on a memory-backed tmpfs and is deliberately rebuilt
after every container restart.

## Quick start with Docker

Prerequisites: a running Besu node with HTTP JSON-RPC enabled and Docker
Compose.

```sh
cd standalone/besu-rpc-proxy
cp .env.example .env
# Edit .env, especially BESU_RPC_URL and the initial contract address.
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

If upgrading from the previous two-container `api` + `indexer` layout, remove
those two project containers once before starting the new `app` service:

```sh
docker compose down --remove-orphans
docker compose up --build -d
```

This command is scoped to the `besu-rpc-proxy` Compose project and does not
stop or delete the separate Besu network. The former `indexer-data` volume is
no longer mounted; it may be removed separately after confirming the migration.

Open:

- Explorer: <http://127.0.0.1:8080/>
- Swagger: <http://127.0.0.1:8080/swagger>
- OpenAPI: <http://127.0.0.1:8080/openapi.json>
- Postman: <http://127.0.0.1:8080/postman/besu-etherscan-compatible.postman_collection.json>

Docker uses `http://host.docker.internal:8545` because `127.0.0.1` inside the
app container points back to the app container, not to Besu on the Mac host.
For a host-run JVM, the default remains `http://127.0.0.1:8545`.

Besu should expose:

```text
--rpc-http-api=ETH,NET,ADMIN,TXPOOL,QBFT
```

Only `ETH` is required for public proxy calls and indexing. The other groups
provide the internal Nodes and Validators pages.

## Storage model

Compose runs one `app` container with `SERVICE_MODE=all`:

- `/data/indexer.sqlite`, `-wal`, and `-shm` are inside a size-limited tmpfs
  (`256m` by default). They consume RAM, vanish when the container stops, and
  are rebuilt from Besu using each contract's start block.
- `/config/tracked-contracts.json` is on the small persistent
  `contract-config` Docker volume. Contract names, addresses, enabled state,
  topics, and start blocks survive restarts.
- Besu chain data is not managed or deleted by this Compose project.

To discard only the tracked-contract configuration and bootstrap it again from
`.env`:

```sh
docker compose down
docker volume rm besu-rpc-proxy_contract-config
docker compose up --build -d
```

A normal `docker compose restart` already clears and rebuilds the tmpfs SQLite
index. It does not affect a separately running `besu-test-network` project.

## Contract tracking

`INDEXER_CONTRACT_ADDRESSES` is a comma-separated bootstrap list used only when
`tracked-contracts.json` does not yet exist:

```dotenv
INDEXER_CONTRACT_ADDRESSES=0xaf75636C88b9c70FbCE5a5d2772F7F6a8d56C132,0xAnotherAddress
INDEXER_START_BLOCK=0
```

After the first start, use the Contracts page to add, pause/resume, reindex, or
remove contracts without restarting the service. Each contract has an
independent checkpoint, progress, and error state. New addresses are verified
with `eth_getCode`. Reindexing deletes that contract's current RAM index and
backfills it from the selected start block.

Set `CONTRACT_ADMIN_TOKEN` to protect these internal mutations. The UI asks for
the token and sends it as `X-Contract-Admin-Token`. Read-only internal pages and
the Fireblocks API remain unaffected. In a real environment, also restrict the
internal UI at the network or reverse-proxy layer.

## Public API examples

```sh
curl 'http://127.0.0.1:8080/api?module=account&action=balance&address=0x...&tag=latest'
curl 'http://127.0.0.1:8080/api?module=proxy&action=eth_getTransactionCount&address=0x...&tag=pending'
curl 'http://127.0.0.1:8080/api?module=proxy&action=eth_gasPrice'
curl 'http://127.0.0.1:8080/api?module=logs&action=getLogs&fromBlock=0&toBlock=latest&address=0x...'
curl 'http://127.0.0.1:8080/api?module=account&action=txlist&address=0x...&startblock=0&endblock=latest&page=1&offset=25&sort=desc'
```

The upstream Besu URL is server configuration. API callers cannot choose an
arbitrary node or arbitrary JSON-RPC method.

## Internal UI

- **Explorer**: latest 5 blocks by default, up to 20 transactions, 15-second
  refresh, exact decimal transaction quantities, local and UTC timestamps, and
  raw JSON.
- **Nodes**: read-only node, peer, and transaction-pool status.
- **Validators**: read-only QBFT validators and pending votes.
- **Contracts**: persistent tracking configuration, per-contract sync status,
  event search, and indexed contract transactions.
- **API Console**: editable parameters, exact request URL, and live response
  for every supported public endpoint.

The Explorer and Contracts pages display a sync banner because results can be
incomplete while tmpfs SQLite is rebuilding.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVICE_MODE` | `api` in Java; `all` in Compose | `api`, `indexer`, or `all` |
| `HTTP_HOST` / `HTTP_PORT` | `127.0.0.1` / `8080` | HTTP bind address |
| `BESU_RPC_URL` | `http://127.0.0.1:8545` | Canonical Besu RPC endpoint |
| `BESU_RPC_TIMEOUT_MS` | `5000` | Upstream timeout |
| `BESU_NODES_CONFIG` | unset | Optional internal-UI node registry |
| `SQLITE_PATH` | `./data/indexer.sqlite` | SQLite path; Compose fixes it to `/data/indexer.sqlite` |
| `CONTRACT_CONFIG_PATH` | `./data/tracked-contracts.json` | Persistent tracking configuration |
| `CONTRACT_ADMIN_TOKEN` | empty | Optional mutation token |
| `INDEXER_CONTRACT_ADDRESSES` | empty | First-run comma-separated bootstrap addresses |
| `INDEXER_TOPIC0` | empty | First-run event topic allowlist |
| `INDEXER_START_BLOCK` | `0` in Compose | First-run decimal start block |
| `INDEXER_CONFIRMATIONS` | `0` | Lag behind the chain head |
| `INDEXER_BATCH_SIZE` | `500` | Blocks per contract per poll |
| `INDEXER_POLL_INTERVAL_MS` | `2000` | Poll delay |
| `INDEXER_REORG_LOOKBACK` | `128` | Automatic reorg search depth |
| `SQLITE_TMPFS_SIZE` | `256m` | Maximum tmpfs filesystem size |
| `CONTAINER_MEMORY_LIMIT` | `1g` | Container memory limit |
| `JAVA_TOOL_OPTIONS` | `-Xms128m -Xmx384m` | JVM heap sizing |

Compose automatically reads `.env`; direct `./scripts/run.sh` execution does
not, so host-run variables must be exported or supplied inline.

## Host development and verification

```sh
./scripts/build.sh
SERVICE_MODE=all \
INDEXER_CONTRACT_ADDRESSES=0xYourContractAddress \
INDEXER_START_BLOCK=0 \
./scripts/run.sh

./scripts/test.sh
```

The integration suite uses an in-process fake Besu and covers public API
dispatch, SQLite migration/indexing, internal UI aggregation, contract
management authorization, and static documentation resources.

## Documentation

- [Architecture overview](docs/architecture-overview.md)
- [API support matrix](docs/api-support-matrix.md)
