package com.blockscout.proxy;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class EventIndexer implements AutoCloseable {
    private final BesuClient client;
    private final SqliteStore store;
    private final Configuration configuration;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile String chainId;
    private volatile String lastError;

    EventIndexer(BesuClient client, SqliteStore store, Configuration configuration) {
        this.client = client;
        this.store = store;
        this.configuration = configuration;
        if (configuration.addresses().isEmpty()) {
            throw new IllegalArgumentException(
                    "INDEXER_CONTRACT_ADDRESSES is required in indexer mode to prevent accidental full-chain indexing");
        }
    }

    void start() {
        executor.scheduleWithFixedDelay(
                this::pollSafely, 0, configuration.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            pollOnce();
            lastError = null;
        } catch (RuntimeException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            lastError = exception.getMessage();
            System.err.println("Indexer poll failed: " + exception.getMessage());
        } finally {
            polling.set(false);
        }
    }

    void pollOnce() throws InterruptedException {
        if (chainId == null) {
            chainId = stringResult(client.call("eth_chainId", List.of()), "chain id").toLowerCase();
        }
        long head = quantity(client.call("eth_blockNumber", List.of()), "block number");
        long safeHead = Math.max(-1, head - configuration.confirmations());
        SqliteStore.Checkpoint checkpoint = store.checkpoint(chainId);
        if (checkpoint == null) {
            long start = configuration.startBlock() == null ? safeHead + 1 : configuration.startBlock();
            store.initializeCheckpoint(chainId, start);
            checkpoint = store.checkpoint(chainId);
        }
        store.registerFilters(
                chainId, configuration.addresses(), configuration.topic0Values(), checkpoint.nextBlock());
        checkpoint = repairReorg(checkpoint);
        if (checkpoint.nextBlock() > safeHead) {
            return;
        }
        long toBlock = Math.min(safeHead, checkpoint.nextBlock() + configuration.batchSize() - 1L);
        indexRange(checkpoint.nextBlock(), toBlock);
    }

    private SqliteStore.Checkpoint repairReorg(SqliteStore.Checkpoint checkpoint) throws InterruptedException {
        if (checkpoint.nextBlock() == 0 || checkpoint.lastBlockHash() == null) {
            return checkpoint;
        }
        long lastNumber = checkpoint.nextBlock() - 1;
        Map<String, Object> canonical = block(lastNumber, false);
        if (checkpoint.lastBlockHash().equalsIgnoreCase(stringField(canonical, "hash"))) {
            return checkpoint;
        }
        long minimum = Math.max(-1, lastNumber - configuration.reorgLookback());
        long commonAncestor = lastNumber - 1;
        while (commonAncestor > minimum) {
            String storedHash = store.blockHash(chainId, commonAncestor);
            if (storedHash != null
                    && storedHash.equalsIgnoreCase(stringField(block(commonAncestor, false), "hash"))) {
                break;
            }
            commonAncestor--;
        }
        if (commonAncestor <= minimum && minimum >= 0) {
            throw new IllegalStateException("Reorg exceeds INDEXER_REORG_LOOKBACK; manual recovery is required");
        }
        System.err.printf("Reorg detected; rolling back to block %d%n", commonAncestor);
        store.rollbackAfter(chainId, commonAncestor);
        return store.checkpoint(chainId);
    }

    private void indexRange(long fromBlock, long toBlock) throws InterruptedException {
        List<SqliteStore.IndexedBlock> blocks = new ArrayList<>();
        List<SqliteStore.IndexedTransaction> transactions = new ArrayList<>();
        Map<Long, Long> timestamps = new HashMap<>();
        for (long number = fromBlock; number <= toBlock; number++) {
            Map<String, Object> block = block(number, true);
            long timestamp = quantity(block.get("timestamp"), "block timestamp");
            blocks.add(new SqliteStore.IndexedBlock(
                    number, stringField(block, "hash"), stringField(block, "parentHash"), timestamp));
            timestamps.put(number, timestamp);
            transactions.addAll(indexedTransactions(block, number, timestamp));
        }

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("fromBlock", hex(fromBlock));
        filter.put("toBlock", hex(toBlock));
        filter.put("address", configuration.addresses().size() == 1
                ? configuration.addresses().getFirst()
                : configuration.addresses());
        if (!configuration.topic0Values().isEmpty()) {
            filter.put("topics", List.of(configuration.topic0Values().size() == 1
                    ? configuration.topic0Values().getFirst()
                    : configuration.topic0Values()));
        }
        Object rawLogs = client.call("eth_getLogs", List.of(filter));
        if (!(rawLogs instanceof List<?> logs)) {
            throw new BesuClient.UpstreamException("Besu returned invalid logs", 502);
        }
        List<SqliteStore.IndexedLog> indexedLogs = new ArrayList<>();
        for (Object rawLog : logs) {
            if (!(rawLog instanceof Map<?, ?> rawMap)) {
                throw new BesuClient.UpstreamException("Besu returned an invalid log", 502);
            }
            Map<String, Object> log = BesuClient.castObject(rawMap);
            long blockNumber = quantity(log.get("blockNumber"), "log block number");
            indexedLogs.add(new SqliteStore.IndexedLog(
                    blockNumber,
                    stringField(log, "blockHash"),
                    stringField(log, "transactionHash"),
                    quantity(log.get("transactionIndex"), "transaction index"),
                    quantity(log.get("logIndex"), "log index"),
                    stringField(log, "address").toLowerCase(),
                    topics(log.get("topics")),
                    stringField(log, "data"),
                    timestamps.getOrDefault(blockNumber, 0L)));
        }
        store.persistBatch(chainId, blocks, indexedLogs, transactions);
        System.out.printf(
                "Indexed blocks %d-%d, %d logs, and %d contract transactions%n",
                fromBlock,
                toBlock,
                indexedLogs.size(),
                transactions.size());
    }

    private List<SqliteStore.IndexedTransaction> indexedTransactions(
            Map<String, Object> block, long blockNumber, long timestamp) throws InterruptedException {
        Object rawTransactions = block.get("transactions");
        if (!(rawTransactions instanceof List<?> values)) {
            throw new BesuClient.UpstreamException("Besu returned invalid block transactions", 502);
        }
        List<SqliteStore.IndexedTransaction> transactions = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> rawTransaction)) {
                throw new BesuClient.UpstreamException("Besu did not return full block transactions", 502);
            }
            Map<String, Object> transaction = BesuClient.castObject(rawTransaction);
            String to = optionalString(transaction.get("to"));
            String from = stringField(transaction, "from").toLowerCase();
            String matchedAddress = configuredAddress(to);
            if (matchedAddress == null) {
                matchedAddress = configuredAddress(from);
            }

            Map<String, Object> receipt = null;
            String createdContract = null;
            if (matchedAddress != null || to == null) {
                receipt = receipt(stringField(transaction, "hash"));
                createdContract = optionalString(receipt.get("contractAddress"));
                if (matchedAddress == null) {
                    matchedAddress = configuredAddress(createdContract);
                }
            }
            if (matchedAddress == null) {
                continue;
            }
            if (receipt == null) {
                receipt = receipt(stringField(transaction, "hash"));
                createdContract = optionalString(receipt.get("contractAddress"));
            }
            transactions.add(new SqliteStore.IndexedTransaction(
                    matchedAddress,
                    stringField(transaction, "hash").toLowerCase(),
                    blockNumber,
                    stringField(block, "hash").toLowerCase(),
                    timestamp,
                    quantity(transaction.get("transactionIndex"), "transaction index"),
                    from,
                    to == null ? null : to.toLowerCase(),
                    createdContract == null ? null : createdContract.toLowerCase(),
                    quantityString(transaction, "nonce", "0x0"),
                    quantityString(transaction, "value", "0x0"),
                    quantityString(transaction, "gas", "0x0"),
                    optionalString(transaction.get("gasPrice")),
                    optionalString(transaction.get("maxFeePerGas")),
                    optionalString(transaction.get("maxPriorityFeePerGas")),
                    stringField(transaction, "input"),
                    quantityString(transaction, "type", "0x0"),
                    optionalString(receipt.get("status")),
                    optionalString(receipt.get("gasUsed")),
                    optionalString(receipt.get("effectiveGasPrice"))));
        }
        return transactions;
    }

    private String configuredAddress(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase();
        return configuration.addresses().contains(normalized) ? normalized : null;
    }

    private Map<String, Object> receipt(String hash) throws InterruptedException {
        Object value = client.call("eth_getTransactionReceipt", List.of(hash));
        if (!(value instanceof Map<?, ?> raw)) {
            throw new BesuClient.UpstreamException("Besu returned no receipt for " + hash, 502);
        }
        return BesuClient.castObject(raw);
    }

    private Map<String, Object> block(long number, boolean fullTransactions) throws InterruptedException {
        Object value = client.call("eth_getBlockByNumber", List.of(hex(number), fullTransactions));
        if (!(value instanceof Map<?, ?> raw)) {
            throw new BesuClient.UpstreamException("Besu returned no block for " + number, 502);
        }
        return BesuClient.castObject(raw);
    }

    private static String quantityString(Map<String, Object> object, String field, String defaultValue) {
        String value = optionalString(object.get(field));
        return value == null ? defaultValue : value;
    }

    private static String optionalString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static List<String> topics(Object value) {
        if (!(value instanceof List<?> raw)) {
            throw new BesuClient.UpstreamException("Besu returned invalid log topics", 502);
        }
        List<String> topics = new ArrayList<>();
        for (Object topic : raw) {
            topics.add(stringResult(topic, "log topic").toLowerCase());
        }
        return List.copyOf(topics);
    }

    private static long quantity(Object value, String label) {
        String raw = stringResult(value, label);
        if (!raw.matches("0x[0-9a-fA-F]+")) {
            throw new BesuClient.UpstreamException("Besu returned invalid " + label, 502);
        }
        try {
            return new BigInteger(raw.substring(2), 16).longValueExact();
        } catch (ArithmeticException exception) {
            throw new BesuClient.UpstreamException(label + " is too large", 502);
        }
    }

    private static String stringField(Map<String, Object> object, String field) {
        return stringResult(object.get(field), field);
    }

    private static String stringResult(Object value, String label) {
        if (value instanceof String string) {
            return string;
        }
        throw new BesuClient.UpstreamException("Besu returned invalid " + label, 502);
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    String chainId() {
        return chainId;
    }

    String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println("Indexer did not stop within five seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
