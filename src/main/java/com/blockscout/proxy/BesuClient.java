package com.blockscout.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class BesuClient {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final AtomicLong requestId = new AtomicLong();

    BesuClient(URI endpoint, Duration timeout) {
        if (!List.of("http", "https").contains(endpoint.getScheme())) {
            throw new IllegalArgumentException("BESU_RPC_URL must use http or https");
        }
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    Object call(String method, List<?> params) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", requestId.incrementAndGet());
        payload.put("method", method);
        payload.put("params", params);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new UpstreamException("Cannot connect to Besu", 502);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new UpstreamException("Besu returned HTTP " + response.statusCode(), 502);
        }

        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (IllegalArgumentException exception) {
            throw new UpstreamException("Besu returned malformed JSON", 502);
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new UpstreamException("Besu returned a non-object response", 502);
        }
        Map<String, Object> object = castObject(raw);
        if (object.get("error") instanceof Map<?, ?> error) {
            Object message = error.get("message");
            throw new UpstreamException("Besu JSON-RPC error: " + message, 502);
        }
        if (!object.containsKey("result")) {
            throw new UpstreamException("Besu response did not contain result", 502);
        }
        return object.get("result");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castObject(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    static final class UpstreamException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        UpstreamException(String message, int status) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
