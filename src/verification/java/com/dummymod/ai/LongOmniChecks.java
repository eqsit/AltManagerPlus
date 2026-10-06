package com.dummymod.ai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in real HTTP check for a blueprint that finishes beyond the old 95s limit. */
public final class LongOmniChecks {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                int call = calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try {
                    if (call == 1) for (int i = 0; i < 48; i++) {
                        exchange.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush(); Thread.sleep(2000);
                    }
                    String content = "{\"reply\":\"Готово\",\"action\":\"chat\"}";
                    String chunk = new Gson().toJson(Map.of("choices", List.of(Map.of("delta", Map.of("content", content)))));
                    exchange.getResponseBody().write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException disconnected) { }
                finally { exchange.close(); }
            });
            server.start(); long start = System.nanoTime();
            try {
                JsonObject response = OmniClient.ask("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions", "test-key", "test-model", "system", List.of(new Conversation.Message("user", "hello")));
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                if (calls.get() != 1 || seconds < 95 || !response.get("reply").getAsString().equals("Готово")) throw new AssertionError("Long SSE was retried or truncated");
                System.out.printf(Locale.ROOT, "Long HTTP/SSE check passed: %.2fs, one request, complete JSON%n", seconds);
            } finally { server.stop(0); }
        }
    }
}
