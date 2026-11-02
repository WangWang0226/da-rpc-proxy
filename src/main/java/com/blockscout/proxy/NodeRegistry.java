package com.blockscout.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NodeRegistry {
    private final Map<String, Node> nodes;
    private final String defaultNode;

    private NodeRegistry(List<Node> configuredNodes) {
        if (configuredNodes.isEmpty()) {
            throw new IllegalArgumentException("At least one Besu node must be configured");
        }
        Map<String, Node> values = new LinkedHashMap<>();
        for (Node node : configuredNodes) {
            if (values.putIfAbsent(node.name(), node) != null) {
                throw new IllegalArgumentException("Duplicate Besu node name: " + node.name());
            }
        }
        nodes = Collections.unmodifiableMap(values);
        defaultNode = configuredNodes.getFirst().name();
    }

    static NodeRegistry fromEnvironment(URI fallbackUrl, Duration timeout, BesuClient fallbackClient) {
        String configPath = System.getenv("BESU_NODES_CONFIG");
        if (configPath == null || configPath.isBlank()) {
            return single("primary", fallbackUrl, fallbackClient);
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(Path.of(configPath.trim())));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cannot read BESU_NODES_CONFIG", exception);
        }
        if (!(parsed instanceof Map<?, ?> rawObject)) {
            throw new IllegalArgumentException("BESU_NODES_CONFIG must contain a JSON object");
        }
        Object rawNodes = BesuClient.castObject(rawObject).get("nodes");
        if (!(rawNodes instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException("BESU_NODES_CONFIG must contain a non-empty nodes array");
        }
        List<Node> nodes = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rawNode)) {
                throw new IllegalArgumentException("Each configured node must be an object");
            }
            Map<String, Object> object = BesuClient.castObject(rawNode);
            String name = requiredString(object, "name");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Invalid node name: " + name);
            }
            URI rpcUrl = URI.create(requiredString(object, "rpcUrl"));
            if (!List.of("http", "https").contains(rpcUrl.getScheme()) || rpcUrl.getHost() == null) {
                throw new IllegalArgumentException("Node rpcUrl must be an absolute HTTP(S) URL");
            }
            nodes.add(new Node(name, rpcUrl, new BesuClient(rpcUrl, timeout)));
        }
        return new NodeRegistry(nodes);
    }

    static NodeRegistry single(String name, URI endpoint, BesuClient client) {
        return new NodeRegistry(List.of(new Node(name, endpoint, client)));
    }

    Node get(String name) {
        String resolved = name == null || name.isBlank() ? defaultNode : name;
        Node node = nodes.get(resolved);
        if (node == null) {
            throw new IllegalArgumentException("Unknown Besu node: " + resolved);
        }
        return node;
    }

    List<Map<String, Object>> descriptions() {
        List<Map<String, Object>> descriptions = new ArrayList<>();
        for (Node node : nodes.values()) {
            descriptions.add(Map.of(
                    "name", node.name(),
                    "rpcUrl", node.endpoint().toString(),
                    "default", node.name().equals(defaultNode)));
        }
        return descriptions;
    }

    String defaultNode() {
        return defaultNode;
    }

    private static String requiredString(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value instanceof String string && !string.isBlank()) {
            return string.trim();
        }
        throw new IllegalArgumentException("Node field '" + name + "' is required");
    }

    record Node(String name, URI endpoint, BesuClient client) {}
}
