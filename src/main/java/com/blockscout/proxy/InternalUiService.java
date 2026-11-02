package com.blockscout.proxy;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InternalUiService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private final NodeRegistry nodes;
    private final EventIndexer indexer;
    private final TrackedContractStore trackedContracts;
    private final SqliteStore store;
    private final Map<String, CacheEntry> explorerCache = new ConcurrentHashMap<>();

    InternalUiService(NodeRegistry nodes) {
        this(nodes, null, null, null);
    }

    InternalUiService(
            NodeRegistry nodes,
            EventIndexer indexer,
            TrackedContractStore trackedContracts,
            SqliteStore store) {
        this.nodes = nodes;
        this.indexer = indexer;
        this.trackedContracts = trackedContracts;
        this.store = store;
    }

    Map<String, Object> configuration() {
        return Map.of("defaultNode", nodes.defaultNode(), "nodes", nodes.descriptions());
    }

    Map<String, Object> explorer(String nodeName, int lookback, int transactionLimit) throws InterruptedException {
        if (!List.of(5, 10, 20, 30).contains(lookback)) {
            throw new IllegalArgumentException("lookback must be 5, 10, 20, or 30");
        }
        if (transactionLimit < 1 || transactionLimit > 20) {
            throw new IllegalArgumentException("transactionLimit must be between 1 and 20");
        }
        NodeRegistry.Node node = nodes.get(nodeName);
        String key = node.name() + ":" + lookback + ":" + transactionLimit;
        CacheEntry cached = explorerCache.get(key);
        if (cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.value();
        }
        Map<String, Object> value = loadExplorer(node, lookback, transactionLimit);
        explorerCache.put(key, new CacheEntry(Instant.now(), value));
        return value;
    }

    Map<String, Object> nodeStatus(String nodeName) throws InterruptedException {
        NodeRegistry.Node node = nodes.get(nodeName);
        BesuClient client = node.client();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("node", node.name());
        response.put("rpcUrl", node.endpoint().toString());
        Object blockNumber = client.call("eth_blockNumber", List.of());
        response.put("status", "connected");
        response.put("blockNumber", quantity(blockNumber, "block number"));
        response.put("nodeInfo", optionalCall(client, "admin_nodeInfo", List.of()));
        response.put("peerCount", optionalQuantity(client, "net_peerCount"));
        response.put("txpool", optionalCall(client, "txpool_besuTransactions", List.of()));
        response.put("peers", optionalCall(client, "admin_peers", List.of()));
        response.put("checkedAt", Instant.now().toString());
        return response;
    }

    Map<String, Object> validators(String nodeName) throws InterruptedException {
        NodeRegistry.Node node = nodes.get(nodeName);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("node", node.name());
        response.put("validators", node.client().call("qbft_getValidatorsByBlockNumber", List.of("latest")));
        response.put("pendingVotes", optionalCall(node.client(), "qbft_getPendingVotes", List.of()));
        response.put("checkedAt", Instant.now().toString());
        return response;
    }

    Map<String, Object> indexerStatus() {
        requireIndexerManagement();
        return indexer.status();
    }

    Map<String, Object> trackedContracts() {
        requireIndexerManagement();
        return indexer.status();
    }

    Map<String, Object> addContract(Map<String, Object> request) throws InterruptedException {
        requireIndexerManagement();
        String name = requestString(request, "name");
        String address = requestString(request, "address").toLowerCase();
        long startBlock = requestLong(request, "startBlock");
        long head = quantity(nodes.get(null).client().call("eth_blockNumber", List.of()), "block number");
        if (startBlock > head) {
            throw new IllegalArgumentException("startBlock must not be greater than the current chain head");
        }
        Object code = nodes.get(null).client().call("eth_getCode", List.of(address, "latest"));
        if (!(code instanceof String string) || string.equals("0x") || string.equals("0x0")) {
            throw new IllegalArgumentException("No contract bytecode exists at this address on the selected chain");
        }
        List<String> topics = requestTopics(request.get("topics"));
        return trackedContracts.add(name, address, startBlock, topics).toMap();
    }

    Map<String, Object> updateContract(String address, Map<String, Object> request) {
        requireIndexerManagement();
        Object enabled = request.get("enabled");
        if (!(enabled instanceof Boolean flag)) {
            throw new IllegalArgumentException("enabled must be true or false");
        }
        return trackedContracts.updateEnabled(address, flag).toMap();
    }

    Map<String, Object> reindexContract(String address, Map<String, Object> request) throws InterruptedException {
        requireIndexerManagement();
        long startBlock = requestLong(request, "startBlock");
        BesuClient client = nodes.get(null).client();
        long head = quantity(client.call("eth_blockNumber", List.of()), "block number");
        if (startBlock > head) {
            throw new IllegalArgumentException("startBlock must not be greater than the current chain head");
        }
        String chainId = string(client.call("eth_chainId", List.of()), "chain ID").toLowerCase();
        TrackedContractStore.TrackedContract updated = trackedContracts.updateStartBlock(address, startBlock);
        store.resetContract(chainId, updated.address(), startBlock);
        return updated.toMap();
    }

    Map<String, Object> removeContract(String address, boolean purge) throws InterruptedException {
        requireIndexerManagement();
        String chainId = string(nodes.get(null).client().call("eth_chainId", List.of()), "chain ID").toLowerCase();
        TrackedContractStore.TrackedContract contract = trackedContracts.get(address);
        trackedContracts.remove(address);
        store.removeContract(chainId, contract.address(), purge);
        return Map.of("removed", contract.address(), "purged", purge);
    }

    Map<String, Object> transactionDetails(String nodeName, String hash) throws InterruptedException {
        if (!hash.matches("0x[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid transaction hash");
        }
        BesuClient client = nodes.get(nodeName).client();
        Object rawTransaction = client.call("eth_getTransactionByHash", List.of(hash));
        if (!(rawTransaction instanceof Map<?, ?> transactionMap)) {
            throw new IllegalArgumentException("Transaction not found");
        }
        Map<String, Object> transaction = new LinkedHashMap<>(BesuClient.castObject(transactionMap));
        Object receipt = optionalCall(client, "eth_getTransactionReceipt", List.of(hash));
        Object blockNumber = transaction.get("blockNumber");
        Object block = blockNumber instanceof String blockTag
                ? optionalCall(client, "eth_getBlockByNumber", List.of(blockTag, false))
                : null;
        Object latest = client.call("eth_blockNumber", List.of());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transaction", transaction);
        response.put("receipt", receipt);
        response.put("block", block);
        response.put("latestBlock", latest);
        return response;
    }

    private void requireIndexerManagement() {
        if (indexer == null || trackedContracts == null || store == null) {
            throw new IllegalStateException("Indexer management is not available in this service mode");
        }
    }

    private static String requestString(Map<String, Object> request, String name) {
        Object value = request.get(name);
        if (value instanceof String string && !string.isBlank()) {
            return string.trim();
        }
        throw new IllegalArgumentException(name + " is required");
    }

    private static long requestLong(Map<String, Object> request, String name) {
        Object value = request.get(name);
        if (value instanceof Number number) {
            try {
                long result = number instanceof BigDecimal decimal
                        ? decimal.toBigIntegerExact().longValueExact()
                        : BigInteger.valueOf(number.longValue()).longValueExact();
                if (result >= 0) {
                    return result;
                }
            } catch (ArithmeticException ignored) {
                // Report the same validation error for fractional and out-of-range values.
            }
        }
        throw new IllegalArgumentException(name + " must be a non-negative integer");
    }

    private static List<String> requestTopics(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException("topics must be an array");
        }
        List<String> topics = new ArrayList<>();
        for (Object topic : raw) {
            if (!(topic instanceof String string)) {
                throw new IllegalArgumentException("topics must contain strings");
            }
            topics.add(string);
        }
        return topics;
    }

    private static Map<String, Object> loadExplorer(
            NodeRegistry.Node node, int lookback, int transactionLimit) throws InterruptedException {
        BesuClient client = node.client();
        String chainId = string(client.call("eth_chainId", List.of()), "chain ID");
        long latest = quantity(client.call("eth_blockNumber", List.of()), "block number");
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (long number = latest; number >= 0 && blocks.size() < lookback; number--) {
            Object value = client.call("eth_getBlockByNumber", List.of(hex(number), true));
            if (value instanceof Map<?, ?> rawBlock) {
                blocks.add(new LinkedHashMap<>(BesuClient.castObject(rawBlock)));
            }
        }

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            Object rawTransactions = block.get("transactions");
            if (!(rawTransactions instanceof List<?> blockTransactions)) {
                continue;
            }
            for (Object rawTransaction : blockTransactions) {
                if (rawTransaction instanceof Map<?, ?> rawMap) {
                    transactions.add(new LinkedHashMap<>(BesuClient.castObject(rawMap)));
                }
            }
        }
        transactions.sort(Comparator
                .comparingLong((Map<String, Object> transaction) -> quantity(transaction.get("blockNumber"), "block"))
                .thenComparingLong(transaction -> quantity(transaction.get("transactionIndex"), "transaction index"))
                .reversed());
        if (transactions.size() > transactionLimit) {
            transactions = new ArrayList<>(transactions.subList(0, transactionLimit));
        }
        List<Map<String, Object>> enrichedTransactions = new ArrayList<>(transactions.size());
        for (Map<String, Object> transaction : transactions) {
            enrichedTransactions.add(enrichTransaction(client, transaction));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("node", node.name());
        response.put("chainId", chainId);
        response.put("latestBlock", latest);
        response.put("lookback", lookback);
        response.put("blocks", blocks);
        response.put("transactions", enrichedTransactions);
        response.put("fetchedAt", Instant.now().toString());
        return response;
    }

    private static Map<String, Object> enrichTransaction(BesuClient client, Map<String, Object> transaction)
            throws InterruptedException {
        Map<String, Object> result = new LinkedHashMap<>(transaction);
        Object hash = transaction.get("hash");
        if (!(hash instanceof String transactionHash)) {
            return result;
        }
        Object rawReceipt = optionalCall(client, "eth_getTransactionReceipt", List.of(transactionHash));
        if (rawReceipt instanceof Map<?, ?> receipt) {
            Map<String, Object> object = BesuClient.castObject(receipt);
            result.put("receiptStatus", object.get("status"));
            result.put("gasUsed", object.get("gasUsed"));
            result.put("effectiveGasPrice", object.get("effectiveGasPrice"));
            result.put("contractAddress", object.get("contractAddress"));
        }
        return result;
    }

    private static Object optionalCall(BesuClient client, String method, List<?> params) throws InterruptedException {
        try {
            return client.call(method, params);
        } catch (BesuClient.UpstreamException exception) {
            return null;
        }
    }

    private static Long optionalQuantity(BesuClient client, String method) throws InterruptedException {
        Object value = optionalCall(client, method, List.of());
        return value == null ? null : quantity(value, method);
    }

    private static long quantity(Object value, String label) {
        String raw = string(value, label);
        if (!raw.matches("0x[0-9a-fA-F]+")) {
            throw new BesuClient.UpstreamException("Besu returned invalid " + label, 502);
        }
        try {
            return new BigInteger(raw.substring(2), 16).longValueExact();
        } catch (ArithmeticException exception) {
            throw new BesuClient.UpstreamException(label + " is too large", 502);
        }
    }

    private static String string(Object value, String label) {
        if (value instanceof String string) {
            return string;
        }
        throw new BesuClient.UpstreamException("Besu returned invalid " + label, 502);
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    private record CacheEntry(Instant createdAt, Map<String, Object> value) {}
}
