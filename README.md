# Besu Etherscan-compatible proxy

A small Java 21 service that exposes an allowlisted Blockscout/Etherscan
`/api?module=...&action=...` interface and obtains blockchain state on demand
from Besu JSON-RPC.

It includes an optional SQLite raw-event indexer and has no PostgreSQL, Redis,
ORM/framework, or signed transaction submission endpoint. SQLite JDBC is the
only database runtime dependency.

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
```

Both GET query parameters and POST `application/x-www-form-urlencoded` fields
are supported on `/api`.

## Configuration

| Environment variable | Default |
| --- | --- |
| `HTTP_HOST` | `127.0.0.1` |
| `HTTP_PORT` | `8080` |
| `BESU_RPC_URL` | `http://127.0.0.1:8545` |
| `BESU_RPC_TIMEOUT_MS` | `5000` |
| `NETWORK_NAME` | `Private Besu` |
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

## Run API and indexer with Docker

The same image runs as two services. The indexer is the only SQLite writer;
the API opens the WAL database for reads.

```sh
export INDEXER_CONTRACT_ADDRESSES=0xYourContractAddress
export INDEXER_START_BLOCK=0
docker compose up --build
```

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
module/action dispatcher, response envelopes, Swagger resources, and Postman
download. See `docs/api-support-matrix.md` for the complete support boundary.

## Architecture documentation

- [Architecture overview](docs/architecture-overview.md)
- [API support matrix](docs/api-support-matrix.md)
