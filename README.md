# Besu Etherscan-compatible proxy

A small Java 21 service that exposes an allowlisted Blockscout/Etherscan
`/api?module=...&action=...` interface and obtains blockchain state on demand
from Besu JSON-RPC.

It includes a bounded SQLite indexer for allowlisted contract events and
top-level transactions. It has no PostgreSQL, Redis, ORM/framework, or signed
transaction submission endpoint. SQLite JDBC is the only database runtime
dependency.

## Run

```sh
cd standalone/besu-rpc-proxy
./scripts/build.sh
./scripts/run.sh
```

Defaults:

- Explorer: <http://127.0.0.1:8080/>
- Swagger/OpenAPI browser: <http://127.0.0.1:8080/swagger>
- OpenAPI: <http://127.0.0.1:8080/openapi.json>
- Postman: <http://127.0.0.1:8080/postman/besu-etherscan-compatible.postman_collection.json>
- Besu: <http://127.0.0.1:8545>

Example:

```sh
curl 'http://127.0.0.1:8080/api?module=account&action=balance&address=0x...&tag=latest'
curl 'http://127.0.0.1:8080/api?module=proxy&action=eth_getTransactionCount&address=0x...&tag=pending'
curl 'http://127.0.0.1:8080/api?module=proxy&action=eth_gasPrice'
curl 'http://127.0.0.1:8080/api?module=logs&action=getLogs&fromBlock=0&toBlock=latest&address=0x...'
curl 'http://127.0.0.1:8080/api?module=logs&action=getIndexedContracts'
curl 'http://127.0.0.1:8080/api?module=account&action=txlist&address=0x...&startblock=0&endblock=latest&page=1&offset=25&sort=desc'
```

Both GET query parameters and POST `application/x-www-form-urlencoded` fields
are supported on `/api`.

## Configuration

| Environment variable | Default |
| --- | --- |
| `HTTP_HOST` | `127.0.0.1` |
| `HTTP_PORT` | `8080` |
| `BESU_RPC_URL` | `http://127.0.0.1:8545` |
| `BESU_NODES_CONFIG` | unset; optional path to a multi-node JSON file used by the internal UI |
| `BESU_RPC_TIMEOUT_MS` | `5000` |
| `SERVICE_MODE` | `api` (`api`, `indexer`, or `all`) |
| `SQLITE_PATH` | `./data/indexer.sqlite` |
| `INDEXER_CONTRACT_ADDRESSES` | required in indexer mode; comma-separated |
| `INDEXER_TOPIC0` | empty; optional comma-separated event signature hashes |
| `INDEXER_START_BLOCK` | unset; begin after current safe head on first run |
| `INDEXER_CONFIRMATIONS` | `0` |
| `INDEXER_BATCH_SIZE` | `500` |
| `INDEXER_POLL_INTERVAL_MS` | `2000` |
| `INDEXER_REORG_LOOKBACK` | `128` |

The upstream URL is fixed by service configuration. API callers cannot select
or override the Besu node.

## Internal operations UI

The browser UI has five tabs:

- **Explorer** reads the latest 5 blocks by default, collects up to 20 recent
  transactions, and refreshes every 15 seconds. Lookback can be changed to 10,
  20, or 30 blocks.
- **Nodes** reads node identity, peers, and transaction-pool state.
- **Validators** reads the current QBFT validators and pending votes. It never
  submits validator votes.
- **Contracts** lists configured SQLite indexes, searches events, and lists
  indexed top-level transactions with receipt status and gas usage.
- **API Console** provides an editable form for every supported public action,
  displays the exact request URL, and renders the live response.

Explorer aggregation uses a five-second, disposable in-memory cache. Nodes and
Validators call Besu live. Their `/internal/ui/*` routes are deliberately not
part of the public OpenAPI or Postman collection.

For a single node, no extra configuration is needed. For multiple UI-selectable
nodes, copy `config/nodes.example.json`, edit the endpoints, and set:

```sh
BESU_NODES_CONFIG=./config/nodes.json ./scripts/run.sh
```

The first node is selected by default. Browser callers can select only a node
defined in this server-side file; they cannot supply an arbitrary RPC URL.

Besu must expose `ETH` for Explorer, `NET`, `ADMIN`, and `TXPOOL` for the Nodes
page, and `QBFT` for Validators. Optional node fields display as unavailable if
their API is disabled. For example:

```text
--rpc-http-api=ETH,NET,ADMIN,TXPOOL,QBFT
```

When Docker is used, mount the configuration file into the API container and
set `BESU_NODES_CONFIG` to its in-container path. The indexer still follows the
single canonical `BESU_RPC_URL`.

## Run API and indexer with Docker

The same image runs as two services. The indexer is the only SQLite writer;
the API opens the WAL database for reads.

Both Compose services use the `besu-rpc-proxy:local` image. The Dockerfile
uses a BuildKit cache for the Gradle wrapper and dependencies, so the first
build downloads the Java toolchain dependencies while later source-only builds
reuse them. Docker Compose automatically reads a `.env` file from this project
directory for variable interpolation.

```sh
cp .env.example .env
# Edit .env if the Besu URL, contract allowlist, or start block differs.
docker compose up --build -d
docker compose ps
docker compose logs -f indexer
```

You do not need to run `export` before each Docker Compose command when `.env`
exists in this directory. This applies to Docker Compose only: the standalone
`./scripts/run.sh` process does not parse dotenv files, so host-based execution
still requires exported variables or inline assignments.

The default example monitors
`0xaf75636C88b9c70FbCE5a5d2772F7F6a8d56C132` from block 0. The first indexer
run stores a durable checkpoint. Changing `INDEXER_START_BLOCK` later does not
rewind an existing database.

To delete only this project's SQLite database and start a clean backfill while
leaving a separately running `besu-test-network` project untouched:

```sh
docker compose down
docker volume rm besu-rpc-proxy_indexer-data
docker compose up --build -d
```

`docker compose down` temporarily stops only this project's API and indexer.
The named volume removal permanently deletes their SQLite index and checkpoint;
it does not delete Besu chain data or volumes owned by another Compose project.

For host-based development, start both roles in one JVM:

```sh
SERVICE_MODE=all \
INDEXER_CONTRACT_ADDRESSES=0xYourContractAddress \
INDEXER_START_BLOCK=0 \
./scripts/run.sh
```

## Verification

```sh
./scripts/test.sh
```

The integration suite uses an in-process fake Besu server and covers the
module/action dispatcher, event and allowlisted contract-transaction indexing,
response envelopes, internal UI aggregations, Swagger resources, and Postman
download. See `docs/api-support-matrix.md` for the complete support boundary.

## Architecture documentation

- [Architecture overview](docs/architecture-overview.md)
- [API support matrix](docs/api-support-matrix.md)
