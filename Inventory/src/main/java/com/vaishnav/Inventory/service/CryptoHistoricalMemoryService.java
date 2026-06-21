package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.CryptoNewsMemory;
import com.vaishnav.Inventory.repository.CryptoNewsMemoryRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CryptoHistoricalMemoryService {
    private static final long PATTERN_CACHE_MS = 4 * 60 * 60 * 1000L;
    private final CryptoMarketDataService marketDataService;
    private final CryptoNewsMemoryRepository repository;
    private final Map<String, CachedPattern> patternCache = new ConcurrentHashMap<>();

    public CryptoHistoricalMemoryService(CryptoMarketDataService marketDataService, CryptoNewsMemoryRepository repository) {
        this.marketDataService = marketDataService;
        this.repository = repository;
    }

    public Map<String, Object> snapshot(String symbol, Map<String, Object> macroNews, double livePrice) {
        rememberPublishedEvents(symbol, macroNews, livePrice);
        updateEventOutcomes(symbol, livePrice);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pricePattern", historicalPattern(symbol));
        result.put("eventMemory", eventMemory(symbol));
        result.put("institutionalWatch", institutionalWatch(symbol));
        result.put("cmeGap", cmeGapPolicy());
        result.put("policy", "Historical analogs are evidence, not guarantees. Only published events are stored; unknown causes are never invented.");
        return result;
    }

    private Map<String, Object> historicalPattern(String symbol) {
        CachedPattern cached = patternCache.get(symbol);
        if (cached != null && System.currentTimeMillis() - cached.createdAt < PATTERN_CACHE_MS) return cached.value;
        try {
            List<CryptoMarketDataService.Candle> candles = marketDataService.getCandles(symbol, "4h", 1000);
            if (candles.size() < 120) return unavailablePattern("Insufficient 4h history");
            int end = candles.size() - 1;
            Features current = features(candles, end);
            List<Analog> analogs = new ArrayList<>();
            for (int i = 60; i < end - 20; i += 3) {
                Features old = features(candles, i);
                double distance = Math.abs(current.return6 - old.return6) / 3.0
                        + Math.abs(current.return18 - old.return18) / 7.0
                        + Math.abs(current.rangePosition - old.rangePosition)
                        + Math.abs(current.volumeRatio - old.volumeRatio) / 2.0;
                double forward24h = percent(candles.get(i).close, candles.get(i + 6).close);
                double forward72h = percent(candles.get(i).close, candles.get(i + 18).close);
                analogs.add(new Analog(i, distance, forward24h, forward72h, candles.get(i).closeTime));
            }
            analogs.sort(Comparator.comparingDouble(Analog::distance));
            List<Analog> top = analogs.stream().limit(8).toList();
            double avg24 = top.stream().mapToDouble(Analog::forward24h).average().orElse(0);
            double avg72 = top.stream().mapToDouble(Analog::forward72h).average().orElse(0);
            double similarity = top.isEmpty() ? 0 : Math.max(0, Math.min(100, 100 / (1 + top.get(0).distance)));
            String bias = avg24 > 1 ? "BULLISH" : avg24 < -1 ? "BEARISH" : "NEUTRAL";
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", "LIVE_HISTORICAL");
            value.put("source", "BINANCE_4H_OHLCV_1000_CANDLES");
            value.put("historyCandles", candles.size());
            value.put("currentFeatures", current.asMap());
            value.put("analogsFound", top.size());
            value.put("similarityPercent", Math.round(similarity));
            value.put("averageForward24hPercent", avg24);
            value.put("averageForward72hPercent", avg72);
            value.put("bias", bias);
            value.put("confidence", Math.round(Math.min(90, similarity * Math.min(1, Math.abs(avg24) / 2.0))));
            value.put("analogs", top.stream().map(Analog::asMap).toList());
            patternCache.put(symbol, new CachedPattern(System.currentTimeMillis(), value));
            return value;
        } catch (Exception error) {
            return unavailablePattern(error.getMessage());
        }
    }

    private Features features(List<CryptoMarketDataService.Candle> candles, int i) {
        double return6 = percent(candles.get(i - 6).close, candles.get(i).close);
        double return18 = percent(candles.get(i - 18).close, candles.get(i).close);
        double high = candles.subList(i - 42, i + 1).stream().mapToDouble(c -> c.high).max().orElse(candles.get(i).high);
        double low = candles.subList(i - 42, i + 1).stream().mapToDouble(c -> c.low).min().orElse(candles.get(i).low);
        double rangePosition = high == low ? 0.5 : (candles.get(i).close - low) / (high - low);
        double recentVolume = candles.subList(i - 6, i + 1).stream().mapToDouble(c -> c.quoteVolume).average().orElse(0);
        double longVolume = candles.subList(i - 42, i + 1).stream().mapToDouble(c -> c.quoteVolume).average().orElse(1);
        return new Features(return6, return18, rangePosition, longVolume == 0 ? 1 : recentVolume / longVolume);
    }

    private void rememberPublishedEvents(String symbol, Map<String, Object> macroNews, double livePrice) {
        Object raw = macroNews.get("headlines");
        if (!(raw instanceof List<?> headlines)) return;
        for (Object item : headlines) {
            if (!(item instanceof Map<?, ?> headline)) continue;
            String title = String.valueOf(headline.containsKey("title") ? headline.get("title") : "");
            if (title.isBlank()) continue;
            String source = String.valueOf(headline.containsKey("source") ? headline.get("source") : "UNKNOWN");
            String url = String.valueOf(headline.containsKey("url") ? headline.get("url") : "");
            String key = sha256((source + "|" + (url.isBlank() ? title : url)).toLowerCase(Locale.ROOT));
            if (repository.findByEventKeyAndSymbol(key, symbol).isPresent()) continue;
            CryptoNewsMemory memory = new CryptoNewsMemory();
            memory.setEventKey(key);
            memory.setSymbol(symbol);
            memory.setTitle(title);
            memory.setUrl(url);
            memory.setSource(source);
            memory.setCategory(category(title));
            int sentiment = (int) number(headline.get("sentiment"));
            memory.setSentiment(sentiment > 0 ? "BULLISH" : sentiment < 0 ? "BEARISH" : "NEUTRAL");
            memory.setPriceAtObservation(livePrice);
            memory.setTags(tags(title));
            try { repository.save(memory); } catch (Exception ignored) { }
        }
    }

    private void updateEventOutcomes(String symbol, double livePrice) {
        LocalDateTime now = LocalDateTime.now();
        for (CryptoNewsMemory event : repository.findTop500BySymbolOrderByObservedAtDesc(symbol)) {
            if (event.getObservedAt() == null || event.getPriceAtObservation() == null || event.getPriceAtObservation() <= 0) continue;
            if (event.getPriceAfter24h() == null && event.getObservedAt().isBefore(now.minusHours(24))) {
                event.setPriceAfter24h(livePrice);
                event.setReturn24hPercent(percent(event.getPriceAtObservation(), livePrice));
                event.setOutcomeStatus("WAITING_72H");
                event.setUpdatedAt(now);
                repository.save(event);
            }
            if (event.getPriceAfter72h() == null && event.getObservedAt().isBefore(now.minusHours(72))) {
                event.setPriceAfter72h(livePrice);
                event.setReturn72hPercent(percent(event.getPriceAtObservation(), livePrice));
                event.setOutcomeStatus("COMPLETE");
                event.setUpdatedAt(now);
                repository.save(event);
            }
        }
    }

    private Map<String, Object> eventMemory(String symbol) {
        List<CryptoNewsMemory> events = repository.findTop500BySymbolOrderByObservedAtDesc(symbol);
        List<CryptoNewsMemory> completed = events.stream().filter(e -> e.getReturn24hPercent() != null).toList();
        Map<String, List<CryptoNewsMemory>> groups = new LinkedHashMap<>();
        for (CryptoNewsMemory event : completed) groups.computeIfAbsent(event.getCategory(), ignored -> new ArrayList<>()).add(event);
        Map<String, Object> categories = new LinkedHashMap<>();
        groups.forEach((name, rows) -> categories.put(name, Map.of(
                "samples", rows.size(),
                "average24hReturnPercent", rows.stream().mapToDouble(e -> value(e.getReturn24hPercent())).average().orElse(0),
                "average72hReturnPercent", rows.stream().mapToDouble(e -> value(e.getReturn72hPercent())).average().orElse(0)
        )));
        return Map.of(
                "storedEvents", events.size(),
                "completed24hOutcomes", completed.size(),
                "categories", categories,
                "recent", events.stream().limit(12).map(this::eventMap).toList()
        );
    }

    private Map<String, Object> institutionalWatch(String symbol) {
        List<CryptoNewsMemory> events = repository.findTop500BySymbolOrderByObservedAtDesc(symbol);
        long strategy = events.stream().filter(e -> "STRATEGY_MSTR".equals(e.getCategory())).count();
        long blackrock = events.stream().filter(e -> "BLACKROCK_IBIT".equals(e.getCategory())).count();
        long etf = events.stream().filter(e -> "ETF_FLOW".equals(e.getCategory())).count();
        return Map.of(
                "strategyEventsStored", strategy,
                "blackrockIbitEventsStored", blackrock,
                "etfEventsStored", etf,
                "rule", "Only verified published headlines/filings influence memory; institutional activity is not inferred from rumours."
        );
    }

    private Map<String, Object> cmeGapPolicy() {
        return Map.of(
                "status", "EDUCATIONAL_ONLY_NO_CME_PRICE_FEED",
                "definition", "A CME gap is the untraded range between a CME futures close and its next reopen. It is context, not a guaranteed fill target.",
                "currentRule", "Never score a CME gap without a verified CME futures price feed.",
                "marketChange", "CME announced continuous 24/7 crypto futures trading from May 29, 2026 with a weekly maintenance window; old weekend-gap behaviour may not apply the same way."
        );
    }

    private String category(String title) {
        String text = title.toLowerCase(Locale.ROOT);
        if (text.contains("microstrategy") || text.contains("strategy") && text.contains("bitcoin") || text.contains("mstr")) return "STRATEGY_MSTR";
        if (text.contains("blackrock") || text.contains("ibit")) return "BLACKROCK_IBIT";
        if (text.contains("etf") || text.contains("inflow") || text.contains("outflow")) return "ETF_FLOW";
        if (text.contains("fed") || text.contains("cpi") || text.contains("inflation") || text.contains("rate cut") || text.contains("rate hike")) return "MACRO_FED";
        if (text.contains("sec") || text.contains("regulation") || text.contains("lawsuit") || text.contains("ban")) return "REGULATION";
        if (text.contains("hack") || text.contains("exploit") || text.contains("attack")) return "SECURITY";
        return "GENERAL_CRYPTO";
    }

    private String tags(String title) { return category(title) + ",PUBLISHED_NEWS,OUTCOME_TRACKED"; }
    private double percent(double from, double to) { return from == 0 ? 0 : (to - from) * 100 / from; }
    private double value(Double value) { return value == null ? 0 : value; }
    private double number(Object value) { try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; } }
    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }
    private Map<String, Object> unavailablePattern(String reason) { return Map.of("status", "UNAVAILABLE", "reason", reason == null ? "Unknown" : reason, "bias", "NEUTRAL", "confidence", 0); }
    private Map<String, Object> eventMap(CryptoNewsMemory event) { return Map.of("title", event.getTitle(), "source", event.getSource(), "category", event.getCategory(), "sentiment", event.getSentiment(), "observedAt", event.getObservedAt().toString(), "return24hPercent", value(event.getReturn24hPercent()), "outcomeStatus", event.getOutcomeStatus()); }

    private record CachedPattern(long createdAt, Map<String, Object> value) {}
    private record Features(double return6, double return18, double rangePosition, double volumeRatio) { Map<String, Object> asMap() { return Map.of("return24hPercent", return6, "return72hPercent", return18, "rangePosition", rangePosition, "volumeRatio", volumeRatio); } }
    private record Analog(int index, double distance, double forward24h, double forward72h, long timestamp) { Map<String, Object> asMap() { return Map.of("timestamp", timestamp, "similarityPercent", Math.round(100 / (1 + distance)), "forward24hPercent", forward24h, "forward72hPercent", forward72h); } }
}
