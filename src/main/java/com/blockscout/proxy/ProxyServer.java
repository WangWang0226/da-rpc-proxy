package com.blockscout.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class ProxyServer {
    private static final int MAX_REQUEST_BODY_BYTES = 256 * 1024;
    private static final int MAX_PATH_LENGTH = 2_048;
    private final HttpServer server;
    private final BesuClient client;
    private final SqliteStore store;
    private final InternalUiService internalUi;

    ProxyServer(InetSocketAddress address, BesuClient client) throws IOException {
        this(address, client, null);
    }

    ProxyServer(InetSocketAddress address, BesuClient client, SqliteStore store) throws IOException {
        this(
                address,
                client,
                store,
                new InternalUiService(NodeRegistry.single("primary", client.endpoint(), client)));
    }

    ProxyServer(
            InetSocketAddress address, BesuClient client, SqliteStore store, InternalUiService internalUi)
            throws IOException {
        this.server = HttpServer.create(address, 0);
        this.client = client;
        this.store = store;
        this.internalUi = internalUi;
        server.createContext("/", this::handle);
    }

    void start(Executor executor) {
        server.setExecutor(executor);
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            addSecurityHeaders(exchange);
            if (!List.of("GET", "POST").contains(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("message", "Only GET and documented POST endpoints are supported"));
                return;
            }
            String rawPath = exchange.getRequestURI().getRawPath();
            if (rawPath.length() > MAX_PATH_LENGTH) {
                sendJson(exchange, 414, Map.of("message", "Request path is too long"));
                return;
            }
            String path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            route(exchange, path);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, Map.of("message", "Request interrupted"));
        } catch (BesuClient.UpstreamException exception) {
            sendJson(exchange, exception.status(), Map.of("message", exception.getMessage()));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            sendJson(exchange, 422, Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            sendJson(exchange, 500, Map.of("message", "Internal server error"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path) throws IOException, InterruptedException {
        String method = exchange.getRequestMethod();
        if (path.equals("/api") || path.equals("/api/")) {
            etherscanApi(exchange);
            return;
        }
        if (path.startsWith("/internal/ui/")) {
            internalUiApi(exchange, path);
            return;
        }
        if ("POST".equals(method)) {
            sendJson(exchange, 405, Map.of("message", "POST is only supported by /api"));
            return;
        }
        if (path.equals("/")) {
            sendResource(exchange, "/static/index.html", "text/html; charset=utf-8");
        } else if (path.equals("/swagger") || path.equals("/swagger/")) {
            sendResource(exchange, "/static/swagger.html", "text/html; charset=utf-8");
        } else if (path.equals("/app.js")) {
            sendResource(exchange, "/static/app.js", "text/javascript; charset=utf-8");
        } else if (path.equals("/swagger.js")) {
            sendResource(exchange, "/static/swagger.js", "text/javascript; charset=utf-8");
        } else if (path.equals("/styles.css")) {
            sendResource(exchange, "/static/styles.css", "text/css; charset=utf-8");
        } else if (path.equals("/swagger.css")) {
            sendResource(exchange, "/static/swagger.css", "text/css; charset=utf-8");
        } else if (path.equals("/openapi.json")) {
            sendResource(exchange, "/openapi.json", "application/json; charset=utf-8");
        } else if (path.equals("/postman/besu-etherscan-compatible.postman_collection.json")) {
            sendResource(exchange, "/postman/besu-blockscout-v2.postman_collection.json", "application/json; charset=utf-8");
        } else if (path.equals("/api/health") || path.equals("/api/health/readiness")) {
            readiness(exchange);
        } else if (path.equals("/api/health/liveness")) {
            sendJson(exchange, 200, Map.of("status", "live"));
        } else {
            sendJson(exchange, 404, Map.of("message", "Endpoint not supported"));
        }
    }

    private void internalUiApi(HttpExchange exchange, String path) throws IOException, InterruptedException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("message", "Internal UI endpoints only support GET"));
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        Object response = switch (path) {
            case "/internal/ui/config" -> internalUi.configuration();
            case "/internal/ui/explorer" -> internalUi.explorer(
                    params.get("node"),
                    positiveParameter(params, "lookback", 5, 30),
                    positiveParameter(params, "transactionLimit", 20, 20));
            case "/internal/ui/nodes/status" -> internalUi.nodeStatus(params.get("node"));
            case "/internal/ui/validators" -> internalUi.validators(params.get("node"));
            default -> null;
        };
        if (response == null) {
            sendJson(exchange, 404, Map.of("message", "Internal UI endpoint not supported"));
        } else {
            sendJson(exchange, 200, response);
        }
    }

    private void etherscanApi(HttpExchange exchange) throws IOException, InterruptedException {
        Map<String, String> params = apiParameters(exchange);
        String module = params.get("module");
        String action = params.get("action");
        if (module == null || action == null) {
            sendJson(exchange, 200, etherscanError("Params 'module' and 'action' are required"));
            return;
        }
        try {
            Object response = switch (module.toLowerCase() + ":" + action.toLowerCase()) {
                case "account:balance" -> accountBalance(params);
                case "account:balancemulti" -> accountBalanceMulti(params);
                case "account:txlist" -> accountTransactionList(params);
                case "account:eth_get_balance" -> jsonRpcResult(client.call(
                        "eth_getBalance",
                        List.of(
                                requiredAddress(params),
                                normalizeBlockTagOrQuantity(params.getOrDefault("block", "latest")))));
                case "proxy:eth_getbalance" ->
                    jsonRpcResult(client.call("eth_getBalance", List.of(requiredAddress(params), blockTag(params))));
                case "block:eth_block_number", "proxy:eth_blocknumber" ->
                    jsonRpcResult(client.call("eth_blockNumber", List.of()));
                case "proxy:eth_chainid" -> jsonRpcResult(client.call("eth_chainId", List.of()));
                case "proxy:eth_gettransactioncount" -> jsonRpcResult(client.call(
                        "eth_getTransactionCount", List.of(requiredAddress(params), blockTag(params))));
                case "proxy:eth_gasprice" -> jsonRpcResult(client.call("eth_gasPrice", List.of()));
                case "proxy:eth_maxpriorityfeepergas" ->
                    jsonRpcResult(client.call("eth_maxPriorityFeePerGas", List.of()));
                case "proxy:eth_feehistory" -> proxyFeeHistory(params);
                case "proxy:eth_estimategas" -> proxyTransactionCall(params, "eth_estimateGas");
                case "proxy:eth_call" -> proxyTransactionCall(params, "eth_call");
                case "proxy:eth_getcode" -> jsonRpcResult(client.call(
                        "eth_getCode", List.of(requiredAddress(params), blockTag(params))));
                case "proxy:eth_gettransactionbyhash" -> jsonRpcResult(client.call(
                        "eth_getTransactionByHash", List.of(requiredHash(params, "txhash"))));
                case "proxy:eth_gettransactionreceipt" -> jsonRpcResult(client.call(
                        "eth_getTransactionReceipt", List.of(requiredHash(params, "txhash"))));
                case "proxy:eth_getblockbynumber" -> jsonRpcResult(client.call(
                        "eth_getBlockByNumber",
                        List.of(normalizeBlockTagOrQuantity(required(params, "tag")), booleanParameter(params, "boolean"))));
                case "proxy:eth_getblockbyhash" -> jsonRpcResult(client.call(
                        "eth_getBlockByHash",
                        List.of(requiredHash(params, "blockhash"), booleanParameter(params, "boolean"))));
                case "transaction:gettxreceiptstatus" -> transactionReceiptStatus(params);
                case "transaction:getstatus" -> transactionExecutionStatus(params);
                case "logs:getlogs" -> indexedLogs(params);
                case "logs:getindexedcontracts" -> indexedContracts();
                default -> etherscanError("Unsupported module/action");
            };
            sendJson(exchange, 200, response);
        } catch (IllegalArgumentException exception) {
            Object error = "proxy".equalsIgnoreCase(module)
                    ? jsonRpcError(-32602, exception.getMessage())
                    : etherscanError(exception.getMessage());
            sendJson(exchange, 200, error);
        }
    }

    private Object accountBalance(Map<String, String> params) throws InterruptedException {
        String result = requireQuantity(
                client.call("eth_getBalance", List.of(requiredAddress(params), blockTag(params))),
                "eth_getBalance");
        return etherscanSuccess(new BigInteger(result.substring(2), 16).toString());
    }

    private Object accountBalanceMulti(Map<String, String> params) throws InterruptedException {
        String[] addresses = required(params, "address").split(",");
        if (addresses.length > 20) {
            throw new IllegalArgumentException("A maximum of 20 addresses is supported");
        }
        List<Object> results = new ArrayList<>();
        for (String address : addresses) {
            validateAddress(address);
            String balance = requireQuantity(
                    client.call("eth_getBalance", List.of(address, blockTag(params))),
                    "eth_getBalance");
            results.add(Map.of(
                    "account", address,
                    "balance", new BigInteger(balance.substring(2), 16).toString(),
                    "stale", false));
        }
        return etherscanSuccess(results);
    }

    private Object accountTransactionList(Map<String, String> params) throws InterruptedException {
        if (store == null) {
            throw new IllegalArgumentException("The SQLite transaction index is not configured");
        }
        String address = requiredAddress(params).toLowerCase();
        String chainId = requireString(client.call("eth_chainId", List.of()), "chain id").toLowerCase();
        if (!store.indexedContract(chainId, address)) {
            throw new IllegalArgumentException("Address is not configured for contract transaction indexing");
        }
        long latestBlock = new BigInteger(
                        requireQuantity(client.call("eth_blockNumber", List.of()), "eth_blockNumber").substring(2),
                        16)
                .longValueExact();
        long fromBlock = logBlock(params.getOrDefault("startblock", "0"), false);
        long toBlock = logBlock(params.getOrDefault("endblock", "latest"), true);
        if (fromBlock > toBlock) {
            throw new IllegalArgumentException("startblock must not be greater than endblock");
        }
        int page = positiveParameter(params, "page", 1, 1_000_000);
        int offset = positiveParameter(params, "offset", 25, 1_000);
        String sort = params.getOrDefault("sort", "desc").toLowerCase();
        if (!List.of("asc", "desc").contains(sort)) {
            throw new IllegalArgumentException("sort must be asc or desc");
        }
        List<Map<String, Object>> transactions = store.findContractTransactions(
                new SqliteStore.ContractTransactionQuery(
                        chainId,
                        address,
                        fromBlock,
                        toBlock,
                        sort.equals("asc"),
                        page,
                        offset,
                        latestBlock));
        return transactions.isEmpty() ? etherscanError("No transactions found") : etherscanSuccess(transactions);
    }

    private Object proxyFeeHistory(Map<String, String> params) throws InterruptedException {
        String blockCount = normalizeFeeHistoryBlockCount(params.getOrDefault("blockCount", "10"));
        String newestBlock = normalizeBlockTagOrQuantity(params.getOrDefault("newestBlock", "latest"));
        List<Object> rewards = parsePercentiles(params.getOrDefault("rewardPercentiles", ""));
        return jsonRpcResult(client.call("eth_feeHistory", List.of(blockCount, newestBlock, rewards)));
    }

    private Object proxyTransactionCall(Map<String, String> params, String rpcMethod) throws InterruptedException {
        Map<String, Object> transaction = new LinkedHashMap<>();
        copyTransactionParameter(params, transaction, "from", false);
        copyTransactionParameter(params, transaction, "to", false);
        copyTransactionParameter(params, transaction, "gas", false);
        copyTransactionParameter(params, transaction, "gasPrice", false);
        copyTransactionParameter(params, transaction, "value", false);
        copyTransactionParameter(params, transaction, "data", true);
        copyTransactionParameter(params, transaction, "nonce", false);
        copyTransactionParameter(params, transaction, "maxFeePerGas", false);
        copyTransactionParameter(params, transaction, "maxPriorityFeePerGas", false);
        validateTransactionObject(transaction);
        return jsonRpcResult(client.call(rpcMethod, List.of(transaction, blockTag(params))));
    }

    private Object transactionReceiptStatus(Map<String, String> params) throws InterruptedException {
        Object value = client.call("eth_getTransactionReceipt", List.of(requiredHash(params, "txhash")));
        if (!(value instanceof Map<?, ?> raw)) {
            return etherscanError("Transaction receipt not found");
        }
        String status = requireQuantity(BesuClient.castObject(raw).get("status"), "receipt status");
        return etherscanSuccess(Map.of("status", quantitySign(status) == 0 ? "0" : "1"));
    }

    private Object transactionExecutionStatus(Map<String, String> params) throws InterruptedException {
        Object value = client.call("eth_getTransactionReceipt", List.of(requiredHash(params, "txhash")));
        if (!(value instanceof Map<?, ?> raw)) {
            return etherscanError("Transaction receipt not found");
        }
        String status = requireQuantity(BesuClient.castObject(raw).get("status"), "receipt status");
        boolean failed = quantitySign(status) == 0;
        return etherscanSuccess(Map.of("isError", failed ? "1" : "0", "errDescription", ""));
    }

    private Object indexedLogs(Map<String, String> params) throws InterruptedException {
        if (store == null) {
            throw new IllegalArgumentException("The SQLite event index is not configured");
        }
        for (String key : params.keySet()) {
            if (key.endsWith("_opr")) {
                throw new IllegalArgumentException("Topic AND/OR operators are not supported; configured topics use AND");
            }
        }
        String chainId = requireString(client.call("eth_chainId", List.of()), "chain id").toLowerCase();
        long fromBlock = logBlock(params.getOrDefault("fromBlock", "0"), false);
        long toBlock = logBlock(params.getOrDefault("toBlock", "latest"), true);
        if (fromBlock > toBlock) {
            throw new IllegalArgumentException("fromBlock must not be greater than toBlock");
        }
        String address = params.get("address");
        if (address != null && !address.isBlank()) {
            validateAddress(address);
            address = address.toLowerCase();
        } else {
            address = null;
        }
        List<String> topics = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String topic = params.get("topic" + index);
            if (topic != null && !topic.isBlank() && !topic.matches("0x[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("topic" + index + " must be a 32-byte hash");
            }
            topics.add(topic == null || topic.isBlank() ? null : topic.toLowerCase());
        }
        int page = positiveParameter(params, "page", 1, 1_000_000);
        int offset = positiveParameter(params, "offset", 100, 1_000);
        String sort = params.getOrDefault("sort", "asc").toLowerCase();
        if (!List.of("asc", "desc").contains(sort)) {
            throw new IllegalArgumentException("sort must be asc or desc");
        }
        List<Map<String, Object>> logs = store.findLogs(new SqliteStore.LogQuery(
                chainId, fromBlock, toBlock, address, topics, sort.equals("asc"), page, offset));
        return logs.isEmpty() ? etherscanError("No logs found") : etherscanSuccess(logs);
    }

    private Object indexedContracts() throws InterruptedException {
        if (store == null) {
            throw new IllegalArgumentException("The SQLite event index is not configured");
        }
        String chainId = requireString(client.call("eth_chainId", List.of()), "chain id").toLowerCase();
        return etherscanSuccess(store.indexedContracts(chainId));
    }

    private static long logBlock(String value, boolean latestAllowed) {
        if (latestAllowed && value.equalsIgnoreCase("latest")) {
            return Long.MAX_VALUE;
        }
        if (value.equalsIgnoreCase("earliest")) {
            return 0;
        }
        String normalized = normalizeQuantity(value, "block");
        try {
            return new BigInteger(normalized.substring(2), 16).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("block is too large");
        }
    }

    private static int positiveParameter(Map<String, String> params, String name, int defaultValue, int maximum) {
        String value = params.getOrDefault(name, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > maximum) {
                throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private void readiness(HttpExchange exchange) throws IOException, InterruptedException {
        Object chainId = client.call("eth_chainId", List.of());
        Object blockNumber = client.call("eth_blockNumber", List.of());
        sendJson(exchange, 200, Map.of("status", "ready", "chain_id", chainId, "block_number", blockNumber));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static Map<String, String> apiParameters(HttpExchange exchange) throws IOException {
        Map<String, String> values = parseQuery(exchange.getRequestURI().getRawQuery());
        if (!"POST".equals(exchange.getRequestMethod())) {
            return values;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BODY_BYTES) {
            throw new IllegalArgumentException("Request body exceeds 256 KiB");
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            values.putAll(parseQuery(new String(bytes, StandardCharsets.UTF_8)));
        } else if (bytes.length > 0) {
            throw new IllegalArgumentException("POST /api requires application/x-www-form-urlencoded");
        }
        return values;
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Query parameter '" + name + "' is required");
        }
        return value;
    }

    private static String requiredAddress(Map<String, String> params) {
        String address = required(params, "address");
        validateAddress(address);
        return address;
    }

    private static String requiredHash(Map<String, String> params, String name) {
        String hash = required(params, name);
        validateHash(hash, name);
        return hash;
    }

    private static String blockTag(Map<String, String> params) {
        return normalizeBlockTagOrQuantity(params.getOrDefault("tag", "latest"));
    }

    private static boolean booleanParameter(Map<String, String> params, String name) {
        String value = params.getOrDefault(name, "false");
        if (!List.of("true", "false").contains(value)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static void copyTransactionParameter(
            Map<String, String> params, Map<String, Object> transaction, String name, boolean required) {
        String value = params.get(name);
        if (value != null && !value.isBlank()) {
            transaction.put(name, value);
        } else if (required) {
            throw new IllegalArgumentException("Query parameter '" + name + "' is required");
        }
    }

    private static int quantitySign(String value) {
        return new BigInteger(value.substring(2), 16).signum();
    }

    private static Map<String, Object> jsonRpcResult(Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> jsonRpcError(int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private static Map<String, Object> etherscanSuccess(Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "1");
        response.put("message", "OK");
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> etherscanError(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "0");
        response.put("message", "NOTOK");
        response.put("result", message);
        return response;
    }

    private static String normalizeBlockTagOrQuantity(String value) {
        if (List.of("latest", "pending", "earliest", "safe", "finalized").contains(value)) {
            return value;
        }
        return normalizeQuantity(value, "block");
    }

    private static String normalizeQuantity(String value, String label) {
        if (value.matches("0x[0-9a-fA-F]+")) {
            return "0x" + new BigInteger(value.substring(2), 16).toString(16);
        }
        if (value.matches("[0-9]+")) {
            return "0x" + new BigInteger(value).toString(16);
        }
        throw new IllegalArgumentException(label + " must be a non-negative integer or hex quantity");
    }

    private static String normalizeFeeHistoryBlockCount(String value) {
        String quantity = normalizeQuantity(value, "block_count");
        BigInteger count = new BigInteger(quantity.substring(2), 16);
        if (count.signum() == 0 || count.compareTo(BigInteger.valueOf(1_024)) > 0) {
            throw new IllegalArgumentException("block_count must be between 1 and 1024");
        }
        return quantity;
    }

    private static List<Object> parsePercentiles(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        List<Object> percentiles = new ArrayList<>();
        BigDecimal previous = null;
        for (String item : value.split(",")) {
            BigDecimal percentile;
            try {
                percentile = new BigDecimal(item.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("reward_percentiles must be comma-separated numbers");
            }
            if (percentile.signum() < 0 || percentile.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("reward percentiles must be between 0 and 100");
            }
            if (previous != null && percentile.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("reward percentiles must be strictly increasing");
            }
            percentiles.add(percentile);
            previous = percentile;
        }
        return percentiles;
    }

    private static Map<String, Object> validateTransactionObject(Map<String, Object> transaction) {
        List<String> allowedFields = List.of(
                "from", "to", "gas", "gasPrice", "value", "data", "input", "nonce", "type",
                "maxFeePerGas", "maxPriorityFeePerGas", "accessList", "chainId");
        for (String key : transaction.keySet()) {
            if (!allowedFields.contains(key)) {
                throw new IllegalArgumentException("Unsupported transaction field: " + key);
            }
        }
        if (transaction.isEmpty()) {
            throw new IllegalArgumentException("transaction must not be empty");
        }
        validateOptionalAddress(transaction, "from");
        validateOptionalAddress(transaction, "to");
        for (String field : List.of(
                "gas", "gasPrice", "value", "nonce", "type", "maxFeePerGas", "maxPriorityFeePerGas", "chainId")) {
            if (transaction.containsKey(field)) {
                normalizeQuantity(requireString(transaction.get(field), field), field);
            }
        }
        validateOptionalData(transaction, "data");
        validateOptionalData(transaction, "input");
        return transaction;
    }

    private static void validateOptionalAddress(Map<String, Object> transaction, String field) {
        if (transaction.containsKey(field)) {
            validateAddress(requireString(transaction.get(field), field));
        }
    }

    private static void validateOptionalData(Map<String, Object> transaction, String field) {
        if (transaction.containsKey(field)) {
            String value = requireString(transaction.get(field), field);
            if (!value.matches("0x(?:[0-9a-fA-F]{2})*")) {
                throw new IllegalArgumentException(field + " must be 0x-prefixed even-length hex data");
            }
        }
    }

    private static String requireQuantity(Object value, String label) {
        String quantity = requireString(value, label);
        if (!quantity.matches("0x[0-9a-fA-F]+")) {
            throw new BesuClient.UpstreamException("Besu returned an invalid " + label + " quantity", 502);
        }
        return quantity;
    }

    private static void validateAddress(String address) {
        if (!address.matches("0x[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Invalid address hash");
        }
    }

    private static void validateHash(String hash, String label) {
        if (!hash.matches("0x[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private static String requireString(Object value, String label) {
        if (value instanceof String string) {
            return string;
        }
        throw new BesuClient.UpstreamException("Besu returned an invalid " + label, 502);
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self'");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResource(HttpExchange exchange, String path, String contentType) throws IOException {
        try (InputStream input = ProxyServer.class.getResourceAsStream(path)) {
            if (input == null) {
                sendJson(exchange, 500, Map.of("message", "Embedded resource missing"));
                return;
            }
            send(exchange, 200, contentType, input.readAllBytes());
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
