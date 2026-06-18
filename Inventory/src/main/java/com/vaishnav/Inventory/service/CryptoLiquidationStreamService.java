package com.vaishnav.Inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CryptoLiquidationStreamService {

    private static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final String STREAM_URL = "wss://fstream.binance.com/stream?streams="
            + "btcusdt@forceOrder/ethusdt@forceOrder/solusdt@forceOrder/bnbusdt@forceOrder";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Deque<LiquidationEvent>> events = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "binance-liquidation-stream");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WebSocket socket;
    private volatile long lastMessageAt;
    private volatile String status = "CONNECTING";

    @PostConstruct
    public void start() {
        connect();
        scheduler.scheduleWithFixedDelay(() -> {
            if (socket == null || (lastMessageAt > 0 && System.currentTimeMillis() - lastMessageAt > 10 * 60 * 1000L)) {
                closeSocket();
                connect();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        closeSocket();
        scheduler.shutdownNow();
    }

    public Map<String, Object> snapshot(String symbol) {
        Deque<LiquidationEvent> queue = events.computeIfAbsent(symbol.toUpperCase(Locale.ROOT), key -> new ConcurrentLinkedDeque<>());
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!queue.isEmpty() && queue.peekFirst().timestamp < cutoff) queue.pollFirst();

        double longLiquidations = 0;
        double shortLiquidations = 0;
        for (LiquidationEvent event : queue) {
            if ("SELL".equals(event.side)) longLiquidations += event.notional;
            else if ("BUY".equals(event.side)) shortLiquidations += event.notional;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("source", "BINANCE_FORCE_ORDER_WEBSOCKET_REAL");
        result.put("windowMinutes", 60);
        result.put("eventCount", queue.size());
        result.put("longLiquidations", longLiquidations);
        result.put("shortLiquidations", shortLiquidations);
        result.put("bias", longLiquidations > shortLiquidations ? "LONGS_LIQUIDATED" : shortLiquidations > longLiquidations ? "SHORTS_LIQUIDATED" : "NEUTRAL");
        result.put("lastMessageAt", lastMessageAt);
        return result;
    }

    private synchronized void connect() {
        if (socket != null) return;
        status = "CONNECTING";
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .buildAsync(URI.create(STREAM_URL), new Listener())
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        status = "RECONNECTING";
                        socket = null;
                    } else {
                        socket = webSocket;
                        status = "LIVE";
                    }
                });
    }

    private synchronized void closeSocket() {
        WebSocket existing = socket;
        socket = null;
        if (existing != null) existing.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
    }

    private void handleMessage(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode order = root.path("data").path("o");
            if (order.isMissingNode()) order = root.path("o");
            String symbol = order.path("s").asText("").toUpperCase(Locale.ROOT);
            if (symbol.isBlank()) return;
            String side = order.path("S").asText("");
            double quantity = order.path("z").asDouble(order.path("q").asDouble(0));
            double price = order.path("ap").asDouble(order.path("p").asDouble(0));
            long timestamp = order.path("T").asLong(System.currentTimeMillis());
            events.computeIfAbsent(symbol, key -> new ConcurrentLinkedDeque<>())
                    .addLast(new LiquidationEvent(timestamp, side, quantity * price));
            lastMessageAt = System.currentTimeMillis();
            status = "LIVE";
        } catch (Exception ignored) {
            status = "LIVE_PARSE_WARNING";
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            status = "LIVE";
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            status = "RECONNECTING";
            socket = null;
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            status = "RECONNECTING";
            socket = null;
        }
    }

    private record LiquidationEvent(long timestamp, String side, double notional) {}
}
