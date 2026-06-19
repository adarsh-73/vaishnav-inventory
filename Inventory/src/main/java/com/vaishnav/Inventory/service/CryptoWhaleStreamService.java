package com.vaishnav.Inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CryptoWhaleStreamService {
    private static final long WINDOW_MS = 6 * 60 * 60 * 1000L;
    private static final double MIN_VALUE_USD = 1_000_000;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Deque<WhaleEvent> events = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "whale-alert-stream");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${WHALE_ALERT_API_KEY:}") private String apiKey;
    private volatile WebSocket socket;
    private volatile String status = "NOT_CONFIGURED";
    private volatile long lastMessageAt;

    @PostConstruct
    public void start() {
        if (apiKey == null || apiKey.isBlank()) return;
        connect();
        scheduler.scheduleWithFixedDelay(() -> {
            if (socket == null || (lastMessageAt > 0 && System.currentTimeMillis() - lastMessageAt > 15 * 60 * 1000L)) {
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

    public Map<String, Object> snapshot(String tradingSymbol) {
        String symbol = tradingSymbol.replace("USDT", "").toUpperCase(Locale.ROOT);
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!events.isEmpty() && events.peekFirst().timestamp < cutoff) events.pollFirst();

        double exchangeInflow = 0, exchangeOutflow = 0, neutral = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (WhaleEvent event : events) {
            if (!event.symbols.contains(symbol)) continue;
            if (event.bias.equals("BEARISH")) exchangeInflow += event.valueUsd;
            else if (event.bias.equals("BULLISH")) exchangeOutflow += event.valueUsd;
            else neutral += event.valueUsd;
            if (recent.size() < 10) recent.add(event.asMap());
        }
        String bias = exchangeInflow > exchangeOutflow * 1.2 ? "BEARISH" : exchangeOutflow > exchangeInflow * 1.2 ? "BULLISH" : "NEUTRAL";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("configured", apiKey != null && !apiKey.isBlank());
        result.put("source", "WHALE_ALERT_ATTRIBUTED_WEBSOCKET");
        result.put("windowHours", 6);
        result.put("minimumAlertUsd", MIN_VALUE_USD);
        result.put("eventCount", recent.size());
        result.put("exchangeInflowUsd", exchangeInflow);
        result.put("exchangeOutflowUsd", exchangeOutflow);
        result.put("unclassifiedUsd", neutral);
        result.put("bias", bias);
        result.put("recent", recent);
        result.put("lastMessageAt", lastMessageAt);
        return result;
    }

    private synchronized void connect() {
        if (apiKey == null || apiKey.isBlank() || socket != null) return;
        status = "CONNECTING";
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .buildAsync(URI.create("wss://leviathan.whale-alert.io/ws?api_key=" + apiKey), new Listener())
                .whenComplete((webSocket, error) -> {
                    if (error != null) { status = "ERROR"; socket = null; }
                    else { socket = webSocket; status = "SUBSCRIBING"; }
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
            if (root.has("error")) { status = "ERROR: " + root.path("error").asText(); return; }
            if ("subscribed_alerts".equals(root.path("type").asText())) { status = "LIVE"; return; }
            if (!"alert".equals(root.path("type").asText())) return;
            Set<String> symbols = new HashSet<>();
            double valueUsd = 0;
            for (JsonNode amount : root.path("amounts")) {
                symbols.add(amount.path("symbol").asText("").toUpperCase(Locale.ROOT));
                valueUsd += amount.path("value_usd").asDouble(0);
            }
            String from = root.path("from").asText("unknown");
            String to = root.path("to").asText("unknown");
            String fromLower = from.toLowerCase(Locale.ROOT), toLower = to.toLowerCase(Locale.ROOT);
            boolean fromExchange = fromLower.contains("exchange") || knownExchange(fromLower);
            boolean toExchange = toLower.contains("exchange") || knownExchange(toLower);
            String bias = toExchange && !fromExchange ? "BEARISH" : fromExchange && !toExchange ? "BULLISH" : "NEUTRAL";
            events.addFirst(new WhaleEvent(root.path("timestamp").asLong(System.currentTimeMillis() / 1000) * 1000,
                    symbols, valueUsd, from, to, root.path("text").asText(""), bias));
            while (events.size() > 500) events.pollLast();
            lastMessageAt = System.currentTimeMillis();
            status = "LIVE";
        } catch (Exception ignored) {
            status = "LIVE_PARSE_WARNING";
        }
    }

    private boolean knownExchange(String value) {
        return List.of("binance", "coinbase", "kraken", "okx", "bybit", "bitfinex", "gemini", "kucoin", "gate.io").stream().anyMatch(value::contains);
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            String subscription = "{\"type\":\"subscribe_alerts\",\"id\":\"aegis-whales\",\"symbols\":[\"btc\",\"eth\",\"sol\",\"bnb\",\"usdt\",\"usdc\"],\"tx_types\":[\"transfer\",\"mint\",\"burn\"],\"min_value_usd\":" + (long) MIN_VALUE_USD + "}";
            webSocket.sendText(subscription, true);
            webSocket.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { handleMessage(buffer.toString()); buffer.setLength(0); }
            webSocket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) { status = "RECONNECTING"; socket = null; return WebSocket.Listener.super.onClose(webSocket, statusCode, reason); }
        @Override public void onError(WebSocket webSocket, Throwable error) { status = "ERROR"; socket = null; }
    }

    private record WhaleEvent(long timestamp, Set<String> symbols, double valueUsd, String from, String to, String text, String bias) {
        Map<String, Object> asMap() { return Map.of("timestamp", timestamp, "symbols", symbols, "valueUsd", valueUsd, "from", from, "to", to, "text", text, "bias", bias); }
    }
}
