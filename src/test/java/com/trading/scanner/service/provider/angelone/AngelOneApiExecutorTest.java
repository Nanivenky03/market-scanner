package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AngelOneApiExecutorTest {

    private HttpServer server;
    private AngelOneApiExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = new AngelOneApiExecutor(new ObjectMapper());

        set("baseBackoffMs", 0L);
        set("jitterMaxMs", 0L);
        set("historicalRateMs", 0L);
        set("historicalRequestsPerSecond", 100);
        set("historicalRequestsPerMinute", 100);
        set("historicalMaxAttempts", 2);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postJsonForBody_shouldRetryRateLimitedRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        server.createContext("/test", exchange -> {
            int call = calls.incrementAndGet();
            int status = call == 1 ? 429 : 200;
            String body = call == 1
                    ? "{\"message\":\"exceeding access rate\"}"
                    : "{}";

            exchange.sendResponseHeaders(status, body.length());

            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body.getBytes());
            }
        });

        server.start();

        String result = executor.postJsonForBody(
                AngelOneApiExecutor.ApiEndpoint.HISTORICAL_CANDLE,
                "http://localhost:" + server.getAddress().getPort() + "/test",
                Map.of(),
                Map.of("request", "test"));

        assertEquals("{}", result);
        assertEquals(2, calls.get());
    }

    @Test
    void executeRawRequest_shouldExecuteGetAndPostDirectly() throws Exception {
        server.createContext("/api/proxy", exchange -> {
            String method = exchange.getRequestMethod();
            String response = "{\"method\":\"" + method + "\"}";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response.getBytes());
            }
        });

        server.start();

        String url = "http://localhost:" + server.getAddress().getPort() + "/api/proxy";

        var postRes = executor.executeRawRequest("POST", url, Map.of("X-Test", "123"), "{\"query\":\"test\"}");
        assertEquals(200, postRes.statusCode());
        assertEquals("{\"method\":\"POST\"}", postRes.body());

        var getRes = executor.executeRawRequest("GET", url, Map.of(), null);
        assertEquals(200, getRes.statusCode());
        assertEquals("{\"method\":\"GET\"}", getRes.body());
    }

    private void set(String name, Object value) throws Exception {
        Field field = AngelOneApiExecutor.class.getDeclaredField(name);
        field.setAccessible(true);

        if (field.getType() == long.class) {
            field.setLong(executor, (Long) value);
        } else if (field.getType() == int.class) {
            field.setInt(executor, (Integer) value);
        } else {
            field.set(executor, value);
        }
    }
}
