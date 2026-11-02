package com.blockscout.proxy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

record Configuration(
        String mode,
        String httpHost,
        int httpPort,
        URI besuUrl,
        Duration besuTimeout,
        Path databasePath,
        Path contractConfigPath,
        String contractAdminToken,
        Duration pollInterval,
        int batchSize,
        int confirmations,
        Long startBlock,
        int reorgLookback,
        List<String> addresses,
        List<String> topic0Values) {

    static Configuration fromEnvironment() {
        String mode = environment("SERVICE_MODE", "api").toLowerCase();
        if (!List.of("all", "api", "indexer").contains(mode)) {
            throw new IllegalArgumentException("SERVICE_MODE must be all, api, or indexer");
        }
        return new Configuration(
                mode,
                environment("HTTP_HOST", "127.0.0.1"),
                integer("HTTP_PORT", 8080, 1),
                URI.create(environment("BESU_RPC_URL", "http://127.0.0.1:8545")),
                Duration.ofMillis(integer("BESU_RPC_TIMEOUT_MS", 5_000, 1)),
                Path.of(environment("SQLITE_PATH", "./data/indexer.sqlite")),
                Path.of(environment("CONTRACT_CONFIG_PATH", "./data/tracked-contracts.json")),
                environment("CONTRACT_ADMIN_TOKEN", ""),
                Duration.ofMillis(integer("INDEXER_POLL_INTERVAL_MS", 2_000, 100)),
                integer("INDEXER_BATCH_SIZE", 500, 1),
                integer("INDEXER_CONFIRMATIONS", 0, 0),
                optionalLong("INDEXER_START_BLOCK"),
                integer("INDEXER_REORG_LOOKBACK", 128, 1),
                csv("INDEXER_CONTRACT_ADDRESSES", 40),
                csv("INDEXER_TOPIC0", 64));
    }

    boolean apiEnabled() {
        return !mode.equals("indexer");
    }

    boolean indexerEnabled() {
        return !mode.equals("api");
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int integer(String name, int defaultValue, int minimum) {
        String raw = environment(name, Integer.toString(defaultValue));
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum) {
                throw new IllegalArgumentException(name + " must be at least " + minimum);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static Long optionalLong(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static List<String> csv(String name, int hexLength) {
        String value = environment(name, "");
        if (value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String normalized = item.trim().toLowerCase();
            if (!normalized.matches("0x[0-9a-f]{" + hexLength + "}")) {
                throw new IllegalArgumentException(name + " contains invalid hex data: " + item);
            }
            values.add(normalized);
        }
        return List.copyOf(values);
    }
}
