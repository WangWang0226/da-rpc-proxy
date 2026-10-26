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
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS event_logs_block_idx ON event_logs(chain_id, block_number, log_index)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS event_logs_address_idx ON event_logs(chain_id, address, block_number)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS event_logs_topic0_idx ON event_logs(chain_id, topic0, block_number)");
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES (1)");
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES (2)");
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
                SELECT address, topic0, MIN(start_block) AS start_block
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
                    contracts.add(contract);
                }
                return contracts;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    void persistBatch(String chainId, List<IndexedBlock> blocks, List<IndexedLog> logs) {
        if (blocks.isEmpty()) {
            return;
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                insertBlocks(connection, chainId, blocks);
                insertLogs(connection, chainId, logs);
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

    private static IllegalStateException databaseError(SQLException exception) {
        return new IllegalStateException("SQLite operation failed", exception);
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

    record LogQuery(
            String chainId,
            long fromBlock,
            long toBlock,
            String address,
            List<String> topics,
            boolean ascending,
            int page,
            int offset) {}
}
