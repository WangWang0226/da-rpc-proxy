package com.blockscout.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProxyIntegrationTest {
    private static final String ADDRESS = "0x" + "1".repeat(40);
    private static final String CONTRACT = "0x" + "2".repeat(40);
    private static final String TRANSACTION_HASH = "0x" + "a".repeat(64);
    private static final String BLOCK_HASH = "0x" + "b".repeat(64);
    private static final String PARENT_HASH = "0x" + "c".repeat(64);

    private ProxyIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                FakeBesu besu = new FakeBesu(executor)) {
            BesuClient client = new BesuClient(besu.uri(), Duration.ofSeconds(2));
            Path database = Files.createTempFile("besu-indexer-test-", ".sqlite");
            SqliteStore store = indexedStoreFromBesu(client, database);
            ProxyServer proxy = new ProxyServer(new InetSocketAddress("127.0.0.1", 0), client, store);
            proxy.start(executor);
            try {
                runTests(proxy.port());
            } finally {
                proxy.stop();
                Files.deleteIfExists(database);
            }
        }
        System.out.println("All proxy integration tests passed");
    }

    private static void runTests(int port) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        assertResponse(http, port, "/api/health/liveness", 200, "\"status\":\"live\"");
        assertResponse(http, port, "/api/health/readiness", 200, "\"chain_id\":\"0x539\"");
        assertResponse(http, port, "/", 200, "Deployment preparation");
        assertResponse(http, port, "/swagger", 200, "Swagger / OpenAPI 3.0");
        assertResponse(http, port, "/swagger.js", 200, "loadSpec");
        assertResponse(http, port, "/openapi.json", 200, "\"openapi\": \"3.0.3\"");
        assertResponse(
                http,
                port,
                "/postman/besu-etherscan-compatible.postman_collection.json",
                200,
                "Besu Etherscan-Compatible Proxy");

        assertResponse(http, port, "/api?module=account&action=balance&address=" + ADDRESS, 200, "1000000000000000000");
        assertResponse(http, port, "/api?module=account&action=balancemulti&address=" + ADDRESS + "," + CONTRACT, 200, "\"stale\":false");
        assertResponse(http, port, "/api?module=account&action=eth_get_balance&address=" + ADDRESS + "&block=latest", 200, "\"jsonrpc\":\"2.0\"");
        assertResponse(http, port, "/api?module=block&action=eth_block_number", 200, "\"result\":\"0x10\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_chainId", 200, "\"result\":\"0x539\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_getBalance&address=" + ADDRESS, 200, "\"result\":\"0xde0b6b3a7640000\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_getTransactionCount&address=" + ADDRESS + "&tag=pending", 200, "\"result\":\"0x2\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_gasPrice", 200, "\"result\":\"0x7\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_maxPriorityFeePerGas", 200, "\"result\":\"0x2\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_feeHistory&blockCount=4&rewardPercentiles=10,50,90", 200, "\"baseFeePerGas\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_estimateGas&from=" + ADDRESS + "&data=0x6000&value=0x0&tag=pending", 200, "\"result\":\"0x100000\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_call&to=" + CONTRACT + "&data=0x1234&tag=latest", 200, "\"result\":\"0x01\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_getCode&address=" + CONTRACT, 200, "\"result\":\"0x6000\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_getTransactionByHash&txhash=" + TRANSACTION_HASH, 200, TRANSACTION_HASH);
        assertResponse(http, port, "/api?module=proxy&action=eth_getTransactionReceipt&txhash=" + TRANSACTION_HASH, 200, "\"contractAddress\"");
        assertResponse(http, port, "/api?module=proxy&action=eth_getBlockByNumber&tag=latest&boolean=true", 200, BLOCK_HASH);
        assertResponse(http, port, "/api?module=proxy&action=eth_getBlockByHash&blockhash=" + BLOCK_HASH + "&boolean=false", 200, BLOCK_HASH);
        assertResponse(http, port, "/api?module=transaction&action=gettxreceiptstatus&txhash=" + TRANSACTION_HASH, 200, "\"status\":\"1\"");
        assertResponse(http, port, "/api?module=transaction&action=getstatus&txhash=" + TRANSACTION_HASH, 200, "\"isError\":\"0\"");
        assertResponse(http, port, "/api?module=logs&action=getLogs&fromBlock=16&toBlock=latest&address=" + CONTRACT, 200, TRANSACTION_HASH);
        assertResponse(http, port, "/api?module=logs&action=getLogs&fromBlock=0&toBlock=15", 200, "No logs found");
        assertResponse(http, port, "/api?module=logs&action=getIndexedContracts", 200, CONTRACT);
        assertResponse(http, port, "/api?module=proxy&action=eth_feeHistory&rewardPercentiles=50,10", 200, "strictly increasing");
        assertResponse(http, port, "/api?module=proxy&action=unknown", 200, "Unsupported module/action");
        assertResponse(http, port, "/api/v2/blocks/latest", 404, "Endpoint not supported");
        assertFormPost(http, port, "module=proxy&action=eth_chainId", "\"result\":\"0x539\"");
    }

    private static SqliteStore indexedStoreFromBesu(BesuClient client, Path database) throws InterruptedException {
        SqliteStore store = new SqliteStore(database);
        Configuration configuration = new Configuration(
                "indexer",
                "127.0.0.1",
                8080,
                URI.create("http://127.0.0.1:8545"),
                Duration.ofSeconds(2),
                database,
                Duration.ofSeconds(1),
                100,
                0,
                16L,
                16,
                List.of(CONTRACT),
                List.of());
        new EventIndexer(client, store, configuration).pollOnce();
        return store;
    }

    private static void assertResponse(
            HttpClient http, int port, String path, int expectedStatus, String expectedBodyFragment)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), path + " status");
        if (!response.body().contains(expectedBodyFragment)) {
            throw new AssertionError(path + " response did not contain " + expectedBodyFragment + ": " + response.body());
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertPostResponse(
            HttpClient http, int port, String path, String json, int expectedStatus, String expectedBodyFragment)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), path + " status");
        if (!response.body().contains(expectedBodyFragment)) {
            throw new AssertionError(path + " response did not contain " + expectedBodyFragment + ": " + response.body());
        }
    }

    private static void assertFormPost(HttpClient http, int port, String form, String expectedBodyFragment)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST /api status");
        if (!response.body().contains(expectedBodyFragment)) {
            throw new AssertionError("POST /api response did not contain " + expectedBodyFragment + ": " + response.body());
        }
    }

    private static final class FakeBesu implements AutoCloseable {
        private final HttpServer server;

        private FakeBesu(ExecutorService executor) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                Object parsed = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Map<String, Object> request = BesuClient.castObject((Map<?, ?>) parsed);
                String method = (String) request.get("method");
                List<?> params = (List<?>) request.get("params");
                Object result = result(method, params);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("jsonrpc", "2.0");
                response.put("id", request.get("id"));
                response.put("result", result);
                byte[] body = Json.stringify(response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        private Object result(String method, List<?> params) {
            return switch (method) {
                case "eth_chainId" -> "0x539";
                case "eth_blockNumber" -> "0x10";
                case "eth_getBalance" -> "0xde0b6b3a7640000";
                case "eth_getTransactionCount" -> "0x2";
                case "eth_getCode" -> CONTRACT.equals(params.get(0)) ? "0x6000" : "0x";
                case "eth_gasPrice" -> "0x7";
                case "eth_maxPriorityFeePerGas" -> "0x2";
                case "eth_estimateGas" -> "0x100000";
                case "eth_call" -> "0x01";
                case "eth_feeHistory" -> feeHistory();
                case "eth_getBlockByNumber", "eth_getBlockByHash" -> block((Boolean) params.get(1));
                case "eth_getTransactionByHash" -> transaction();
                case "eth_getTransactionReceipt" -> receipt();
                case "eth_getLogs" -> List.of(eventLog());
                default -> null;
            };
        }

        private Map<String, Object> feeHistory() {
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("oldestBlock", "0xd");
            history.put("baseFeePerGas", List.of("0x7", "0x8", "0x9", "0xa", "0xb"));
            history.put("gasUsedRatio", List.of(0.5, 0.6, 0.7, 0.8));
            history.put("reward", List.of(List.of("0x1", "0x2", "0x3")));
            return history;
        }

        private Map<String, Object> block(boolean fullTransactions) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("number", "0x10");
            block.put("timestamp", "0x6553f100");
            block.put("transactions", fullTransactions ? List.of(transaction()) : List.of(TRANSACTION_HASH));
            block.put("miner", ADDRESS);
            block.put("size", "0x200");
            block.put("hash", BLOCK_HASH);
            block.put("parentHash", PARENT_HASH);
            block.put("difficulty", "0x1");
            block.put("totalDifficulty", "0x10");
            block.put("gasUsed", "0x5208");
            block.put("gasLimit", "0x1c9c380");
            block.put("nonce", "0x0000000000000000");
            block.put("baseFeePerGas", "0x7");
            return block;
        }

        private Map<String, Object> transaction() {
            Map<String, Object> transaction = new LinkedHashMap<>();
            transaction.put("hash", TRANSACTION_HASH);
            transaction.put("blockNumber", "0x10");
            transaction.put("blockHash", BLOCK_HASH);
            transaction.put("transactionIndex", "0x0");
            transaction.put("from", ADDRESS);
            transaction.put("to", null);
            transaction.put("value", "0x0");
            transaction.put("gas", "0x100000");
            transaction.put("gasPrice", "0x7");
            transaction.put("maxFeePerGas", null);
            transaction.put("maxPriorityFeePerGas", null);
            transaction.put("nonce", "0x2");
            transaction.put("input", "0x6000");
            transaction.put("type", "0x0");
            return transaction;
        }

        private Map<String, Object> receipt() {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("logIndex", "0x0");
            log.put("transactionHash", TRANSACTION_HASH);
            log.put("address", CONTRACT);
            log.put("data", "0x");
            log.put("topics", List.of());

            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("transactionHash", TRANSACTION_HASH);
            receipt.put("status", "0x1");
            receipt.put("gasUsed", "0x5208");
            receipt.put("contractAddress", CONTRACT);
            receipt.put("logs", List.of(log));
            return receipt;
        }

        private Map<String, Object> eventLog() {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("blockNumber", "0x10");
            log.put("blockHash", BLOCK_HASH);
            log.put("transactionHash", TRANSACTION_HASH);
            log.put("transactionIndex", "0x0");
            log.put("logIndex", "0x0");
            log.put("address", CONTRACT);
            log.put("topics", List.of("0x" + "d".repeat(64)));
            log.put("data", "0x1234");
            return log;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
