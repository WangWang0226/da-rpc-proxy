package com.blockscout.proxy;

import java.math.BigInteger;
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
    private final Map<String, CacheEntry> explorerCache = new ConcurrentHashMap<>();

    InternalUiService(NodeRegistry nodes) {
        this.nodes = nodes;
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
