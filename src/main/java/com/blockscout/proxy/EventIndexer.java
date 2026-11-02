package com.blockscout.proxy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    private final TrackedContractStore trackedContracts;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile String chainId;
    private volatile long latestHead = -1;
    private volatile long latestSafeHead = -1;
    private volatile String lastError;

    EventIndexer(
            BesuClient client,
            SqliteStore store,
            Configuration configuration,
            TrackedContractStore trackedContracts) {
        this.client = client;
        this.store = store;
        this.configuration = configuration;
        this.trackedContracts = trackedContracts;
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
        lastError = null;
        if (chainId == null) {
            chainId = stringResult(client.call("eth_chainId", List.of()), "chain id").toLowerCase();
        }
        latestHead = quantity(client.call("eth_blockNumber", List.of()), "block number");
        latestSafeHead = Math.max(-1, latestHead - configuration.confirmations());
        for (TrackedContractStore.TrackedContract contract : trackedContracts.all()) {
            if (!contract.enabled()) {
                SqliteStore.ContractCheckpoint checkpoint = store.contractCheckpoint(chainId, contract.address());
                if (checkpoint != null) {
                    store.updateContractState(chainId, contract.address(), "paused", null);
                }
                continue;
            }
            try {
                pollContract(contract, latestSafeHead);
            } catch (RuntimeException exception) {
                store.initializeContractCheckpoint(chainId, contract.address(), contract.startBlock());
                store.updateContractState(chainId, contract.address(), "error", exception.getMessage());
                if (lastError == null) {
                    lastError = exception.getMessage();
                }
                System.err.printf("Contract index failed for %s: %s%n", contract.address(), exception.getMessage());
            }
        }
    }

    private void pollContract(TrackedContractStore.TrackedContract contract, long safeHead)
            throws InterruptedException {
        store.initializeContractCheckpoint(chainId, contract.address(), contract.startBlock());
        store.registerFilters(chainId, List.of(contract.address()), contract.topics(), contract.startBlock());
        SqliteStore.ContractCheckpoint checkpoint = store.contractCheckpoint(chainId, contract.address());
        checkpoint = repairReorg(contract.address(), checkpoint);
        if (checkpoint.nextBlock() > safeHead) {
            store.updateContractState(chainId, contract.address(), "caught_up", null);
            return;
        }
        long toBlock = Math.min(safeHead, checkpoint.nextBlock() + configuration.batchSize() - 1L);
        indexRange(contract, checkpoint.nextBlock(), toBlock, safeHead);
    }

    private SqliteStore.ContractCheckpoint repairReorg(
            String address, SqliteStore.ContractCheckpoint checkpoint) throws InterruptedException {
        if (checkpoint.nextBlock() == checkpoint.startBlock() || checkpoint.lastBlockHash() == null) {
            return checkpoint;
        }
        long lastNumber = checkpoint.nextBlock() - 1;
        if (checkpoint.lastBlockHash().equalsIgnoreCase(stringField(block(lastNumber, false), "hash"))) {
            return checkpoint;
        }
        long minimum = Math.max(checkpoint.startBlock() - 1, lastNumber - configuration.reorgLookback());
        long commonAncestor = lastNumber - 1;
        while (commonAncestor > minimum) {
            String storedHash = store.blockHash(chainId, commonAncestor);
            if (storedHash != null
                    && storedHash.equalsIgnoreCase(stringField(block(commonAncestor, false), "hash"))) {
                break;
            }
            commonAncestor--;
        }
        if (commonAncestor <= minimum && minimum >= checkpoint.startBlock()) {
            throw new IllegalStateException("Reorg exceeds INDEXER_REORG_LOOKBACK; reindex this contract");
        }
        store.rollbackContractAfter(chainId, address, commonAncestor);
        return store.contractCheckpoint(chainId, address);
    }

    private void indexRange(
            TrackedContractStore.TrackedContract contract, long fromBlock, long toBlock, long safeHead)
            throws InterruptedException {
        List<SqliteStore.IndexedBlock> blocks = new ArrayList<>();
        List<SqliteStore.IndexedTransaction> transactions = new ArrayList<>();
        Map<Long, Long> timestamps = new LinkedHashMap<>();
        for (long number = fromBlock; number <= toBlock; number++) {
            Map<String, Object> block = block(number, true);
            long timestamp = quantity(block.get("timestamp"), "block timestamp");
            blocks.add(new SqliteStore.IndexedBlock(
                    number, stringField(block, "hash"), stringField(block, "parentHash"), timestamp));
            timestamps.put(number, timestamp);
            transactions.addAll(indexedTransactions(block, number, timestamp, contract.address()));
        }

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("fromBlock", hex(fromBlock));
        filter.put("toBlock", hex(toBlock));
        filter.put("address", contract.address());
        if (!contract.topics().isEmpty()) {
            filter.put("topics", List.of(
                    contract.topics().size() == 1 ? contract.topics().getFirst() : contract.topics()));
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
        store.persistContractBatch(
                chainId, contract.address(), blocks, indexedLogs, transactions, toBlock >= safeHead);
        System.out.printf(
                "Indexed %s blocks %d-%d, %d logs, and %d contract transactions%n",
                contract.address(), fromBlock, toBlock, indexedLogs.size(), transactions.size());
    }

    private List<SqliteStore.IndexedTransaction> indexedTransactions(
            Map<String, Object> block, long blockNumber, long timestamp, String trackedAddress)
            throws InterruptedException {
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
            boolean directMatch = trackedAddress.equalsIgnoreCase(from)
                    || (to != null && trackedAddress.equalsIgnoreCase(to));
            Map<String, Object> receipt = null;
            String createdContract = null;
            if (directMatch || to == null) {
                receipt = receipt(stringField(transaction, "hash"));
                createdContract = optionalString(receipt.get("contractAddress"));
            }
            if (!directMatch && (createdContract == null || !trackedAddress.equalsIgnoreCase(createdContract))) {
                continue;
            }
            if (receipt == null) {
                receipt = receipt(stringField(transaction, "hash"));
                createdContract = optionalString(receipt.get("contractAddress"));
            }
            transactions.add(new SqliteStore.IndexedTransaction(
                    trackedAddress,
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

    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", overallState());
        result.put("chainId", chainId);
        result.put("chainHead", latestHead < 0 ? null : latestHead);
        result.put("safeHead", latestSafeHead < 0 ? null : latestSafeHead);
        result.put("polling", polling.get());
        result.put("lastError", lastError);
        List<Map<String, Object>> contractValues = new ArrayList<>();
        if (chainId != null) {
            for (TrackedContractStore.TrackedContract contract : trackedContracts.all()) {
                Map<String, Object> value = new LinkedHashMap<>(contract.toMap());
                SqliteStore.ContractCheckpoint checkpoint = store.contractCheckpoint(chainId, contract.address());
                value.put("nextBlock", checkpoint == null ? contract.startBlock() : checkpoint.nextBlock());
                value.put("state", checkpoint == null ? "initializing" : checkpoint.state());
                value.put("lastError", checkpoint == null ? null : checkpoint.lastError());
                value.put("indexedAt", checkpoint == null ? null : checkpoint.updatedAt());
                value.put("progress", progress(contract.startBlock(), checkpoint));
                value.put("eventCount", store.eventCount(chainId, contract.address()));
                value.put("transactionCount", store.transactionCount(chainId, contract.address()));
                contractValues.add(value);
            }
        }
        result.put("contracts", contractValues);
        result.put("storage", storageStatus());
        return result;
    }

    private String overallState() {
        if (lastError != null) {
            return "error";
        }
        if (chainId == null || latestSafeHead < 0) {
            return "initializing";
        }
        for (TrackedContractStore.TrackedContract contract : trackedContracts.enabled()) {
            SqliteStore.ContractCheckpoint checkpoint = store.contractCheckpoint(chainId, contract.address());
            if (checkpoint == null || checkpoint.nextBlock() <= latestSafeHead) {
                return "syncing";
            }
        }
        return "caught_up";
    }

    private double progress(long startBlock, SqliteStore.ContractCheckpoint checkpoint) {
        if (latestSafeHead < startBlock) {
            return 100.0;
        }
        long nextBlock = checkpoint == null ? startBlock : checkpoint.nextBlock();
        long total = latestSafeHead - startBlock + 1;
        long completed = Math.max(0, Math.min(total, nextBlock - startBlock));
        return Math.round((completed * 10_000.0) / total) / 100.0;
    }

    private Map<String, Object> storageStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path database = configuration.databasePath().toAbsolutePath();
        result.put("databaseBytes", fileSize(database));
        result.put("walBytes", fileSize(Path.of(database + "-wal")));
        result.put("shmBytes", fileSize(Path.of(database + "-shm")));
        try {
            result.put("usableBytes", Files.getFileStore(database.getParent()).getUsableSpace());
        } catch (IOException exception) {
            result.put("usableBytes", null);
        }
        return result;
    }

    private static long fileSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException exception) {
            return 0;
        }
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

    private static String quantityString(Map<String, Object> object, String field, String defaultValue) {
        String value = optionalString(object.get(field));
        return value == null ? defaultValue : value;
    }

    private static String optionalString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
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
