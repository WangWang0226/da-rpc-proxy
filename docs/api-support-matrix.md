# API support matrix

The blockchain API is exposed through one Blockscout/Etherscan-compatible
endpoint:

```text
GET or POST /api?module=<module>&action=<action>&...
```

Every action is resolved through an explicit allowlist and a bounded set of
Besu JSON-RPC calls. Allowlisted contract logs and top-level transactions are
handled by the small SQLite indexer; there is no arbitrary method pass-through,
PostgreSQL, or Redis.

## Documentation and test clients

| Resource | URL or file |
| --- | --- |
| Swagger/OpenAPI browser | `/swagger` |
| OpenAPI 3.0 JSON | `/openapi.json` |
| Downloadable Postman Collection | `/postman/besu-etherscan-compatible.postman_collection.json` |
| Repository Postman Collection | `src/main/resources/postman/besu-blockscout-v2.postman_collection.json` |

## Official Blockscout-compatible actions

These names and response envelopes match Blockscout's documented per-instance
RPC API.

This service now implements 8 of Blockscout's 42 documented per-instance
module/action operations (19.0%). The percentage is intentionally small
because account history, tokens, stats, contract verification, and other
full-explorer features remain outside this lightweight service.

| Module | Action | Besu source | Response |
| --- | --- | --- | --- |
| `account` | `balance` | `eth_getBalance` | `{status,message,result}` with decimal wei |
| `account` | `balancemulti` | Up to 20 `eth_getBalance` calls | `{status,message,result[]}` |
| `account` | `eth_get_balance` | `eth_getBalance` | `{jsonrpc,id,result}` with hex wei |
| `account` | `txlist` | SQLite allowlisted contract transaction index | `{status,message,result[]}`; arbitrary accounts rejected |
| `block` | `eth_block_number` | `eth_blockNumber` | `{jsonrpc,id,result}` |
| `transaction` | `gettxreceiptstatus` | `eth_getTransactionReceipt` | `{status,message,result:{status}}` |
| `transaction` | `getstatus` | `eth_getTransactionReceipt` | `{status,message,result:{isError,errDescription}}` |
| `logs` | `getLogs` | SQLite event index | `{status,message,result[]}` with raw EVM logs |

## Etherscan-style proxy compatibility extensions

Blockscout's documented per-instance module/action list does not define the
deployment-preparation methods below. They are exposed under `module=proxy`
using the familiar Etherscan naming convention because Fireblocks requires the
module/action transport. Successful responses use the Ethereum JSON-RPC-shaped
`{jsonrpc:"2.0",id:1,result:...}` envelope.

| Action | Besu source | Primary use |
| --- | --- | --- |
| `eth_chainId` | `eth_chainId` | Replay-protected signing |
| `eth_blockNumber` | `eth_blockNumber` | Current chain head |
| `eth_getBalance` | `eth_getBalance` | Confirm funds for value and gas |
| `eth_getTransactionCount` | `eth_getTransactionCount` | Pending wallet nonce |
| `eth_gasPrice` | `eth_gasPrice` | Legacy fee suggestion |
| `eth_maxPriorityFeePerGas` | `eth_maxPriorityFeePerGas` | EIP-1559 priority fee |
| `eth_feeHistory` | `eth_feeHistory` | Base-fee and reward history |
| `eth_estimateGas` | `eth_estimateGas` | Deployment gas estimation |
| `eth_call` | `eth_call` | Read-only execution and revert detection |
| `eth_getCode` | `eth_getCode` | Detect deployed contract code |
| `eth_getTransactionByHash` | `eth_getTransactionByHash` | Transaction lookup |
| `eth_getTransactionReceipt` | `eth_getTransactionReceipt` | Receipt, status, contract address, and logs |
| `eth_getBlockByNumber` | `eth_getBlockByNumber` | Block lookup by number or tag |
| `eth_getBlockByHash` | `eth_getBlockByHash` | Block lookup by hash |

## Infrastructure

| Path | Purpose |
| --- | --- |
| `/api/health` | Combined service and Besu readiness |
| `/api/health/liveness` | Process liveness without a Besu call |
| `/api/health/readiness` | Besu `eth_chainId` and `eth_blockNumber` check |
| `/openapi.json` | Machine-readable API contract |

The OpenAPI document and Postman Collection each contain all 27 documented
operations: 8 official compatible actions, 14 proxy extensions, 1 local
index-discovery extension, 3 health checks, and the OpenAPI endpoint.

## Lightweight indexer boundary

`logs.getLogs` searches only configured contract addresses and topics that
have already been persisted to SQLite. It supports block range, address,
`topic0` through `topic3` (AND), pagination, and ascending/descending order.
It does not scan arbitrary historical logs from Besu on demand.

The local discovery extension below lets the explorer UI show which indexes
are actually available. It is not claimed as an official Blockscout action.

| Module | Action | Result |
| --- | --- | --- |
| `logs` | `getIndexedContracts` | Contract address, optional `topic0`, start block, and latest indexed block |

`account.txlist` uses the same configured contract allowlist. It stores only
top-level transactions whose `to` address is configured, plus matching contract
creation transactions. Receipts provide status, created address, gas used, and
effective gas price. It does not include internal contract calls or unrelated
wallet history.

## Deliberately unsupported

| Module/action family | Reason |
| --- | --- |
| Arbitrary account `txlist`, token transfers, internal transactions | Requires broader address indexes or execution traces |
| Token lists, holders, and historical balances | Requires event ingestion and token-state indexing |
| Arbitrary unconfigured logs and OR topic operators | Outside the bounded index configuration |
| Contract ABI/source and verification | Source metadata is not EVM state |
| Stats, charts, counters, and supply calculations | Requires historical aggregation |
| Search, watchlists, and account APIs | Requires durable indexed or user storage |

Unsupported module/action combinations return HTTP 200 with the Etherscan
error envelope `{status:"0",message:"NOTOK",result:"Unsupported module/action"}`.
Old `/api/v2/...` blockchain routes return HTTP 404.

## Internal UI routes

`/internal/ui/config`, `/internal/ui/explorer`, `/internal/ui/nodes/status`, and
`/internal/ui/validators` support the private browser UI. They aggregate live
Besu state, are not Blockscout/Etherscan-compatible customer endpoints, and are
therefore deliberately excluded from the support percentage, OpenAPI document,
and Postman collection. They must not be presented to Fireblocks as public API
contract surface.
