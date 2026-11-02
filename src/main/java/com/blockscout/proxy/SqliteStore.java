package com.blockscout.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SqliteStore {
    private static final int MAX_PAGE_SIZE = 1_000;
    private final String jdbcUrl;

    SqliteStore(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot initialize SQLite", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + absolute;
        migrate();
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private void migrate() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                      version INTEGER PRIMARY KEY,
                      applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS indexer_checkpoints (
                      chain_id TEXT PRIMARY KEY,
                      next_block INTEGER NOT NULL,
                      last_block_hash TEXT,
                      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS indexed_blocks (
                      chain_id TEXT NOT NULL,
                      block_number INTEGER NOT NULL,
                      block_hash TEXT NOT NULL,
                      parent_hash TEXT NOT NULL,
                      block_timestamp INTEGER NOT NULL,
                      PRIMARY KEY (chain_id, block_number)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event_logs (
                      chain_id TEXT NOT NULL,
                      block_number INTEGER NOT NULL,
                      block_hash TEXT NOT NULL,
                      transaction_hash TEXT NOT NULL,
                      transaction_index INTEGER NOT NULL,
                      log_index INTEGER NOT NULL,
                      address TEXT NOT NULL,
                      topic0 TEXT,
                      topic1 TEXT,
                      topic2 TEXT,
                      topic3 TEXT,
                      topics_json TEXT NOT NULL,
                      data TEXT NOT NULL,
                      block_timestamp INTEGER NOT NULL,
                      PRIMARY KEY (chain_id, transaction_hash, log_index)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contract_filters (
                      chain_id TEXT NOT NULL,
                      address TEXT NOT NULL,
                      topic0 TEXT NOT NULL DEFAULT '',
                      start_block INTEGER NOT NULL,
                      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (chain_id, address, topic0)
                    )
                    """);
            boolean transactionIndexMigrationRequired = !migrationExists(connection, 3);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contract_transactions (
                      chain_id TEXT NOT NULL,
                      indexed_contract_address TEXT NOT NULL,
                      transaction_hash TEXT NOT NULL,
                      block_number INTEGER NOT NULL,
                      block_hash TEXT NOT NULL,
                      block_timestamp INTEGER NOT NULL,
                      transaction_index INTEGER NOT NULL,
                      from_address TEXT NOT NULL,
                      to_address TEXT,
                      created_contract_address TEXT,
                      nonce TEXT NOT NULL,
                      value TEXT NOT NULL,
                      gas TEXT NOT NULL,
                      gas_price TEXT,
                      max_fee_per_gas TEXT,
                      max_priority_fee_per_gas TEXT,
                      input TEXT NOT NULL,
                      transaction_type TEXT NOT NULL,
                      receipt_status TEXT,
                      gas_used TEXT,
                      effective_gas_price TEXT,
                      PRIMARY KEY (chain_id, indexed_contract_address, transaction_hash)
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS event_logs_block_idx ON event_logs(chain_id, block_number, log_index)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS event_logs_address_idx ON event_logs(chain_id, address, block_number)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS event_logs_topic0_idx ON event_logs(chain_id, topic0, block_number)");
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS contract_transactions_address_block_idx
                    ON contract_transactions(chain_id, indexed_contract_address, block_number, transaction_index)
                    """);
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES (1)");
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES (2)");
            if (transactionIndexMigrationRequired) {
                statement.executeUpdate("""
                        UPDATE indexer_checkpoints
                        SET next_block = (
                              SELECT MIN(start_block) FROM contract_filters
                              WHERE contract_filters.chain_id = indexer_checkpoints.chain_id
                            ),
                            last_block_hash = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE EXISTS (
                          SELECT 1 FROM contract_filters
                          WHERE contract_filters.chain_id = indexer_checkpoints.chain_id
                        )
                        """);
            }
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES (3)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot migrate SQLite database", exception);
        }
    }

    Checkpoint checkpoint(String chainId) {
        String sql = "SELECT next_block, last_block_hash FROM indexer_checkpoints WHERE chain_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chainId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Checkpoint(result.getLong(1), result.getString(2)) : null;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    String blockHash(String chainId, long blockNumber) {
        String sql = "SELECT block_hash FROM indexed_blocks WHERE chain_id = ? AND block_number = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chainId);
            statement.setLong(2, blockNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    void initializeCheckpoint(String chainId, long nextBlock) {
        String sql = """
                INSERT INTO indexer_checkpoints(chain_id, next_block, last_block_hash)
                VALUES (?, ?, NULL)
                ON CONFLICT(chain_id) DO NOTHING
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chainId);
            statement.setLong(2, nextBlock);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    void registerFilters(String chainId, List<String> addresses, List<String> topic0Values, long startBlock) {
        List<String> topics = topic0Values.isEmpty() ? List.of("") : topic0Values;
        String sql = """
                INSERT INTO contract_filters(chain_id, address, topic0, start_block)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(chain_id, address, topic0) DO UPDATE SET start_block = MIN(start_block, excluded.start_block)
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String address : addresses) {
                for (String topic : topics) {
                    statement.setString(1, chainId);
                    statement.setString(2, address);
                    statement.setString(3, topic);
                    statement.setLong(4, startBlock);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    List<Map<String, Object>> indexedContracts(String chainId) {
        String sql = """
                SELECT address, topic0, MIN(start_block) AS start_block,
                       (SELECT COUNT(*) FROM contract_transactions transactions
                        WHERE transactions.chain_id = contract_filters.chain_id
                          AND transactions.indexed_contract_address = contract_filters.address) AS transaction_count
                FROM contract_filters
                WHERE chain_id = ?
                GROUP BY address, topic0
                ORDER BY address, topic0
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            Long latestIndexedBlock = latestIndexedBlock(connection, chainId);
            statement.setString(1, chainId);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> contracts = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> contract = new LinkedHashMap<>();
                    contract.put("address", result.getString("address"));
                    String topic0 = result.getString("topic0");
                    contract.put("topic0", topic0.isEmpty() ? null : topic0);
                    contract.put("startBlock", result.getLong("start_block"));
                    contract.put("latestIndexedBlock", latestIndexedBlock);
                    contract.put("transactionCount", result.getLong("transaction_count"));
                    contracts.add(contract);
                }
                return contracts;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    void persistBatch(
            String chainId,
            List<IndexedBlock> blocks,
            List<IndexedLog> logs,
            List<IndexedTransaction> transactions) {
        if (blocks.isEmpty()) {
            return;
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                insertBlocks(connection, chainId, blocks);
                insertLogs(connection, chainId, logs);
                insertTransactions(connection, chainId, transactions);
                IndexedBlock last = blocks.getLast();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE indexer_checkpoints
                        SET next_block = ?, last_block_hash = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE chain_id = ?
                        """)) {
                    statement.setLong(1, last.number() + 1);
                    statement.setString(2, last.hash());
                    statement.setString(3, chainId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    void rollbackAfter(String chainId, long blockNumber) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                deleteAfter(connection, "event_logs", chainId, blockNumber);
                deleteAfter(connection, "contract_transactions", chainId, blockNumber);
                deleteAfter(connection, "indexed_blocks", chainId, blockNumber);
                String hash = blockNumber < 0 ? null : blockHash(connection, chainId, blockNumber);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE indexer_checkpoints
                        SET next_block = ?, last_block_hash = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE chain_id = ?
                        """)) {
                    statement.setLong(1, blockNumber + 1);
                    statement.setString(2, hash);
                    statement.setString(3, chainId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    List<Map<String, Object>> findLogs(LogQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT block_number, block_hash, transaction_hash, transaction_index, log_index,
                       address, topics_json, data, block_timestamp
                FROM event_logs WHERE chain_id = ? AND block_number BETWEEN ? AND ?
                """);
        List<String> filters = new ArrayList<>();
        if (query.address() != null) {
            sql.append(" AND address = ?");
            filters.add(query.address());
        }
        for (int index = 0; index < query.topics().size(); index++) {
            String topic = query.topics().get(index);
            if (topic != null) {
                sql.append(" AND topic").append(index).append(" = ?");
                filters.add(topic);
            }
        }
        sql.append(" ORDER BY block_number ")
                .append(query.ascending() ? "ASC" : "DESC")
                .append(", log_index ")
                .append(query.ascending() ? "ASC" : "DESC")
                .append(" LIMIT ? OFFSET ?");
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, query.chainId());
            statement.setLong(parameter++, query.fromBlock());
            statement.setLong(parameter++, query.toBlock());
            for (String filter : filters) {
                statement.setString(parameter++, filter);
            }
            statement.setInt(parameter++, Math.min(query.offset(), MAX_PAGE_SIZE));
            statement.setLong(parameter, (long) (query.page() - 1) * query.offset());
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> logs = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> log = new LinkedHashMap<>();
                    log.put("address", result.getString("address"));
                    log.put("topics", Json.parse(result.getString("topics_json")));
                    log.put("data", result.getString("data"));
                    log.put("blockNumber", quantity(result.getLong("block_number")));
                    log.put("blockHash", result.getString("block_hash"));
                    log.put("timeStamp", quantity(result.getLong("block_timestamp")));
                    log.put("gasPrice", "0x0");
                    log.put("gasUsed", "0x0");
                    log.put("logIndex", quantity(result.getLong("log_index")));
                    log.put("transactionHash", result.getString("transaction_hash"));
                    log.put("transactionIndex", quantity(result.getLong("transaction_index")));
                    logs.add(log);
                }
                return logs;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    boolean indexedContract(String chainId, String address) {
        String sql = "SELECT 1 FROM contract_filters WHERE chain_id = ? AND address = ? LIMIT 1";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chainId);
            statement.setString(2, address);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    List<Map<String, Object>> findContractTransactions(ContractTransactionQuery query) {
        String sql = """
                SELECT transaction_hash, block_number, block_hash, block_timestamp, transaction_index,
                       from_address, to_address, created_contract_address, nonce, value, gas, gas_price,
                       max_fee_per_gas, max_priority_fee_per_gas, input, transaction_type,
                       receipt_status, gas_used, effective_gas_price
                FROM contract_transactions
                WHERE chain_id = ? AND indexed_contract_address = ? AND block_number BETWEEN ? AND ?
                ORDER BY block_number %s, transaction_index %s
                LIMIT ? OFFSET ?
                """.formatted(query.ascending() ? "ASC" : "DESC", query.ascending() ? "ASC" : "DESC");
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query.chainId());
            statement.setString(2, query.address());
            statement.setLong(3, query.fromBlock());
            statement.setLong(4, query.toBlock());
            statement.setInt(5, Math.min(query.offset(), MAX_PAGE_SIZE));
            statement.setLong(6, (long) (query.page() - 1) * query.offset());
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> transactions = new ArrayList<>();
                while (result.next()) {
                    long blockNumber = result.getLong("block_number");
                    String input = result.getString("input");
                    Map<String, Object> transaction = new LinkedHashMap<>();
                    transaction.put("blockNumber", Long.toString(blockNumber));
                    transaction.put("timeStamp", Long.toString(result.getLong("block_timestamp")));
                    transaction.put("hash", result.getString("transaction_hash"));
                    transaction.put("nonce", decimalQuantity(result.getString("nonce")));
                    transaction.put("blockHash", result.getString("block_hash"));
                    transaction.put("transactionIndex", Long.toString(result.getLong("transaction_index")));
                    transaction.put("from", result.getString("from_address"));
                    transaction.put("to", emptyIfNull(result.getString("to_address")));
                    transaction.put("value", decimalQuantity(result.getString("value")));
                    transaction.put("gas", decimalQuantity(result.getString("gas")));
                    transaction.put("gasPrice", decimalNullableQuantity(result.getString("gas_price")));
                    transaction.put("isError", receiptFailure(result.getString("receipt_status")) ? "1" : "0");
                    transaction.put("txreceipt_status", receiptSuccess(result.getString("receipt_status")) ? "1" : "0");
                    transaction.put("input", input);
                    transaction.put("contractAddress", emptyIfNull(result.getString("created_contract_address")));
                    transaction.put("gasUsed", decimalNullableQuantity(result.getString("gas_used")));
                    transaction.put("effectiveGasPrice", decimalNullableQuantity(result.getString("effective_gas_price")));
                    transaction.put("confirmations", Long.toString(Math.max(0, query.latestBlock() - blockNumber + 1)));
                    transaction.put("methodId", input != null && input.length() >= 10 ? input.substring(0, 10) : "0x");
                    transaction.put("functionName", "");
                    transaction.put("type", result.getString("transaction_type"));
                    transaction.put("maxFeePerGas", decimalNullableQuantity(result.getString("max_fee_per_gas")));
                    transaction.put(
                            "maxPriorityFeePerGas",
                            decimalNullableQuantity(result.getString("max_priority_fee_per_gas")));
                    transactions.add(transaction);
                }
                return transactions;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private static void insertBlocks(Connection connection, String chainId, List<IndexedBlock> blocks)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO indexed_blocks
                  (chain_id, block_number, block_hash, parent_hash, block_timestamp)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (IndexedBlock block : blocks) {
                statement.setString(1, chainId);
                statement.setLong(2, block.number());
                statement.setString(3, block.hash());
                statement.setString(4, block.parentHash());
                statement.setLong(5, block.timestamp());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertLogs(Connection connection, String chainId, List<IndexedLog> logs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO event_logs
                  (chain_id, block_number, block_hash, transaction_hash, transaction_index, log_index,
                   address, topic0, topic1, topic2, topic3, topics_json, data, block_timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (IndexedLog log : logs) {
                statement.setString(1, chainId);
                statement.setLong(2, log.blockNumber());
                statement.setString(3, log.blockHash());
                statement.setString(4, log.transactionHash());
                statement.setLong(5, log.transactionIndex());
                statement.setLong(6, log.logIndex());
                statement.setString(7, log.address());
                for (int topic = 0; topic < 4; topic++) {
                    statement.setString(8 + topic, topic < log.topics().size() ? log.topics().get(topic) : null);
                }
                statement.setString(12, Json.stringify(log.topics()));
                statement.setString(13, log.data());
                statement.setLong(14, log.blockTimestamp());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertTransactions(
            Connection connection, String chainId, List<IndexedTransaction> transactions) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO contract_transactions
                  (chain_id, indexed_contract_address, transaction_hash, block_number, block_hash,
                   block_timestamp, transaction_index, from_address, to_address, created_contract_address,
                   nonce, value, gas, gas_price, max_fee_per_gas, max_priority_fee_per_gas, input,
                   transaction_type, receipt_status, gas_used, effective_gas_price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (IndexedTransaction transaction : transactions) {
                statement.setString(1, chainId);
                statement.setString(2, transaction.indexedContractAddress());
                statement.setString(3, transaction.hash());
                statement.setLong(4, transaction.blockNumber());
                statement.setString(5, transaction.blockHash());
                statement.setLong(6, transaction.blockTimestamp());
                statement.setLong(7, transaction.transactionIndex());
                statement.setString(8, transaction.from());
                statement.setString(9, transaction.to());
                statement.setString(10, transaction.createdContractAddress());
                statement.setString(11, transaction.nonce());
                statement.setString(12, transaction.value());
                statement.setString(13, transaction.gas());
                statement.setString(14, transaction.gasPrice());
                statement.setString(15, transaction.maxFeePerGas());
                statement.setString(16, transaction.maxPriorityFeePerGas());
                statement.setString(17, transaction.input());
                statement.setString(18, transaction.type());
                statement.setString(19, transaction.receiptStatus());
                statement.setString(20, transaction.gasUsed());
                statement.setString(21, transaction.effectiveGasPrice());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void deleteAfter(Connection connection, String table, String chainId, long blockNumber)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM " + table + " WHERE chain_id = ? AND block_number > ?")) {
            statement.setString(1, chainId);
            statement.setLong(2, blockNumber);
            statement.executeUpdate();
        }
    }

    private static String blockHash(Connection connection, String chainId, long blockNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT block_hash FROM indexed_blocks WHERE chain_id = ? AND block_number = ?")) {
            statement.setString(1, chainId);
            statement.setLong(2, blockNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static Long latestIndexedBlock(Connection connection, String chainId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT MAX(block_number) FROM indexed_blocks WHERE chain_id = ?")) {
            statement.setString(1, chainId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long value = result.getLong(1);
                return result.wasNull() ? null : value;
            }
        }
    }

    private static String quantity(long value) {
        return "0x" + Long.toHexString(value);
    }

    private static String decimalNullableQuantity(String value) {
        return value == null ? "" : decimalQuantity(value);
    }

    private static String decimalQuantity(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]+")) {
            return "0";
        }
        return new java.math.BigInteger(value.substring(2), 16).toString();
    }

    private static boolean receiptFailure(String status) {
        return status != null && status.matches("0x0+");
    }

    private static boolean receiptSuccess(String status) {
        return status != null && !receiptFailure(status);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static IllegalStateException databaseError(SQLException exception) {
        return new IllegalStateException("SQLite operation failed", exception);
    }

    private static boolean migrationExists(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    record Checkpoint(long nextBlock, String lastBlockHash) {}

    record IndexedBlock(long number, String hash, String parentHash, long timestamp) {}

    record IndexedLog(
            long blockNumber,
            String blockHash,
            String transactionHash,
            long transactionIndex,
            long logIndex,
            String address,
            List<String> topics,
            String data,
            long blockTimestamp) {}

    record IndexedTransaction(
            String indexedContractAddress,
            String hash,
            long blockNumber,
            String blockHash,
            long blockTimestamp,
            long transactionIndex,
            String from,
            String to,
            String createdContractAddress,
            String nonce,
            String value,
            String gas,
            String gasPrice,
            String maxFeePerGas,
            String maxPriorityFeePerGas,
            String input,
            String type,
            String receiptStatus,
            String gasUsed,
            String effectiveGasPrice) {}

    record LogQuery(
            String chainId,
            long fromBlock,
            long toBlock,
            String address,
            List<String> topics,
            boolean ascending,
            int page,
            int offset) {}

    record ContractTransactionQuery(
            String chainId,
            String address,
            long fromBlock,
            long toBlock,
            boolean ascending,
            int page,
            int offset,
            long latestBlock) {}
}
