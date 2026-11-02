package com.blockscout.proxy;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TrackedContractStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CONTRACTS = 100;
    private final Path path;
    private final Map<String, TrackedContract> contracts = new LinkedHashMap<>();

    TrackedContractStore(Path path, List<String> bootstrapAddresses, List<String> bootstrapTopics, Long startBlock) {
        this.path = path.toAbsolutePath().normalize();
        if (Files.exists(this.path)) {
            load();
        } else {
            long initialBlock = startBlock == null ? 0 : startBlock;
            for (String address : bootstrapAddresses) {
                String now = Instant.now().toString();
                contracts.put(
                        address.toLowerCase(),
                        new TrackedContract(
                                defaultName(address),
                                address.toLowerCase(),
                                initialBlock,
                                List.copyOf(bootstrapTopics),
                                true,
                                now,
                                now));
            }
            persist();
        }
    }

    synchronized List<TrackedContract> all() {
        return List.copyOf(contracts.values());
    }

    synchronized List<TrackedContract> enabled() {
        return contracts.values().stream().filter(TrackedContract::enabled).toList();
    }

    synchronized TrackedContract get(String address) {
        TrackedContract contract = contracts.get(normalizeAddress(address));
        if (contract == null) {
            throw new IllegalArgumentException("Tracked contract not found: " + address);
        }
        return contract;
    }

    synchronized TrackedContract add(String name, String address, long startBlock, List<String> topics) {
        String normalized = normalizeAddress(address);
        if (contracts.containsKey(normalized)) {
            throw new IllegalArgumentException("Contract is already tracked");
        }
        if (contracts.size() >= MAX_CONTRACTS) {
            throw new IllegalArgumentException("A maximum of " + MAX_CONTRACTS + " tracked contracts is supported");
        }
        validateName(name);
        validateStartBlock(startBlock);
        List<String> normalizedTopics = normalizeTopics(topics);
        String now = Instant.now().toString();
        TrackedContract contract =
                new TrackedContract(name.trim(), normalized, startBlock, normalizedTopics, true, now, now);
        contracts.put(normalized, contract);
        persist();
        return contract;
    }

    synchronized TrackedContract updateEnabled(String address, boolean enabled) {
        TrackedContract current = get(address);
        TrackedContract updated = new TrackedContract(
                current.name(),
                current.address(),
                current.startBlock(),
                current.topics(),
                enabled,
                current.createdAt(),
                Instant.now().toString());
        contracts.put(updated.address(), updated);
        persist();
        return updated;
    }

    synchronized TrackedContract updateStartBlock(String address, long startBlock) {
        validateStartBlock(startBlock);
        TrackedContract current = get(address);
        TrackedContract updated = new TrackedContract(
                current.name(),
                current.address(),
                startBlock,
                current.topics(),
                current.enabled(),
                current.createdAt(),
                Instant.now().toString());
        contracts.put(updated.address(), updated);
        persist();
        return updated;
    }

    synchronized void remove(String address) {
        String normalized = normalizeAddress(address);
        if (contracts.remove(normalized) == null) {
            throw new IllegalArgumentException("Tracked contract not found: " + address);
        }
        persist();
    }

    private void load() {
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(path));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot read tracked contract configuration", exception);
        }
        if (!(parsed instanceof Map<?, ?> rawRoot)) {
            throw new IllegalStateException("Tracked contract configuration must be a JSON object");
        }
        Map<String, Object> root = BesuClient.castObject(rawRoot);
        if (!(root.get("schemaVersion") instanceof Number version) || version.intValue() != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported tracked contract configuration schema version");
        }
        if (!(root.get("contracts") instanceof List<?> entries)) {
            throw new IllegalStateException("Tracked contract configuration must contain a contracts array");
        }
        for (Object entry : entries) {
            TrackedContract contract = parseContract(entry);
            if (contracts.putIfAbsent(contract.address(), contract) != null) {
                throw new IllegalStateException("Duplicate tracked contract: " + contract.address());
            }
        }
        if (contracts.size() > MAX_CONTRACTS) {
            throw new IllegalStateException("Tracked contract configuration exceeds " + MAX_CONTRACTS + " contracts");
        }
    }

    private TrackedContract parseContract(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Tracked contract entry must be an object");
        }
        Map<String, Object> object = BesuClient.castObject(raw);
        String name = requiredString(object, "name");
        validateName(name);
        String address = normalizeAddress(requiredString(object, "address"));
        long startBlock = requiredLong(object, "startBlock");
        validateStartBlock(startBlock);
        List<String> topics = object.get("topics") == null ? List.of() : parseTopics(object.get("topics"));
        boolean enabled = !(object.get("enabled") instanceof Boolean flag) || flag;
        String createdAt = optionalString(object, "createdAt", Instant.now().toString());
        String updatedAt = optionalString(object, "updatedAt", createdAt);
        return new TrackedContract(name, address, startBlock, topics, enabled, createdAt, updatedAt);
    }

    private synchronized void persist() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("contracts", new ArrayList<>(contracts.values().stream().map(TrackedContract::toMap).toList()));
        Path parent = path.getParent();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary, Json.stringify(root));
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist tracked contract configuration", exception);
        }
    }

    private static List<String> parseTopics(Object value) {
        if (!(value instanceof List<?> rawTopics)) {
            throw new IllegalStateException("Tracked contract topics must be an array");
        }
        List<String> topics = new ArrayList<>();
        for (Object topic : rawTopics) {
            if (!(topic instanceof String string)) {
                throw new IllegalStateException("Tracked contract topic must be a string");
            }
            topics.add(string);
        }
        return normalizeTopics(topics);
    }

    private static List<String> normalizeTopics(List<String> topics) {
        List<String> normalized = new ArrayList<>();
        for (String topic : topics) {
            String value = topic.trim().toLowerCase();
            if (!value.matches("0x[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Event topic must be a 32-byte hash");
            }
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeAddress(String address) {
        String normalized = address.trim().toLowerCase();
        if (!normalized.matches("0x[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid contract address");
        }
        return normalized;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 80) {
            throw new IllegalArgumentException("Contract name must be between 1 and 80 characters");
        }
    }

    private static void validateStartBlock(long startBlock) {
        if (startBlock < 0) {
            throw new IllegalArgumentException("Contract start block must not be negative");
        }
    }

    private static String requiredString(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value instanceof String string && !string.isBlank()) {
            return string.trim();
        }
        throw new IllegalStateException("Tracked contract field '" + name + "' is required");
    }

    private static long requiredLong(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value instanceof Number number) {
            try {
                return number instanceof BigDecimal decimal
                        ? decimal.toBigIntegerExact().longValueExact()
                        : BigInteger.valueOf(number.longValue()).longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalStateException(
                        "Tracked contract field '" + name + "' must be an integer in range", exception);
            }
        }
        throw new IllegalStateException("Tracked contract field '" + name + "' must be a number");
    }

    private static String optionalString(Map<String, Object> object, String name, String defaultValue) {
        Object value = object.get(name);
        return value instanceof String string && !string.isBlank() ? string : defaultValue;
    }

    private static String defaultName(String address) {
        return "Contract " + address.substring(0, 8) + "…" + address.substring(address.length() - 6);
    }

    record TrackedContract(
            String name,
            String address,
            long startBlock,
            List<String> topics,
            boolean enabled,
            String createdAt,
            String updatedAt) {
        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name);
            value.put("address", address);
            value.put("startBlock", startBlock);
            value.put("topics", topics);
            value.put("enabled", enabled);
            value.put("createdAt", createdAt);
            value.put("updatedAt", updatedAt);
            return value;
        }
    }
}
