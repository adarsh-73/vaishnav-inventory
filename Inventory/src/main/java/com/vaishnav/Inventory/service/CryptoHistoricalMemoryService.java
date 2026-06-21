package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.CryptoNewsMemory;
import com.vaishnav.Inventory.repository.CryptoNewsMemoryRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CryptoHistoricalMemoryService {
    private static final long PATTERN_CACHE_MS = 4 * 60 * 60 * 1000L;
    private static final long LONG_HISTORY_CACHE_MS = 24 * 60 * 60 * 1000L;
    private static final long HISTORY_START_MS = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    private final CryptoMarketDataService marketDataService;
    private final CryptoNewsMemoryRepository repository;
    private final Map<String, CachedPattern> patternCache = new ConcurrentHashMap<>();
    private final Map<String, CachedPattern> longHistoryCache = new ConcurrentHashMap<>();

    public CryptoHistoricalMemoryService(CryptoMarketDataService marketDataService, CryptoNewsMemoryRepository repository) {
        this.marketDataService = marketDataService;
        this.repository = repository;
    }

    public Map<String, Object> snapshot(String symbol, Map<String, Object> macroNews, double livePrice) {
        rememberPublishedEvents(symbol, macroNews, livePrice);
        updateEventOutcomes(symbol, livePrice);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pricePattern", historicalPattern(symbol));
        result.put("longCycle", longCycleMemory(symbol, livePrice));
        result.put("eventMemory", eventMemory(symbol));
        result.put("institutionalWatch", institutionalWatch(symbol));
        result.put("cmeGap", cmeGapPolicy(symbol, livePrice));
        result.put("policy", "Historical analogs are evidence, not guarantees. Only published events are stored; unknown causes are never invented.");
        return result;
    }

    private Map<String, Object> longCycleMemory(String symbol, double livePrice) {
        CachedPattern cached = longHistoryCache.get(symbol);
        if (cached != null && System.currentTimeMillis() - cached.createdAt < LONG_HISTORY_CACHE_MS) return cached.value;
        try {
            List<CryptoMarketDataService.Candle> candles = marketDataService.getCandlesSince(symbol, "1d", HISTORY_START_MS, 3000);
            if (candles.size() < 365) return Map.of("status", "UNAVAILABLE", "reason", "Less than one year of daily history");
            int end = candles.size() - 1;
            double ath = candles.stream().mapToDouble(c -> c.high).max().orElse(livePrice);
            double sma50 = candles.subList(Math.max(0, candles.size() - 50), candles.size()).stream().mapToDouble(c -> c.close).average().orElse(livePrice);
            double sma200 = candles.subList(Math.max(0, candles.size() - 200), candles.size()).stream().mapToDouble(c -> c.close).average().orElse(livePrice);
            double return30 = percent(candles.get(Math.max(0, end - 30)).close, candles.get(end).close);
            double volatility30 = standardDeviation(candles, Math.max(1, end - 29), end);
            String regime = livePrice < sma200 ? "BEAR_BELOW_200D" : sma50 < sma200 ? "RECOVERY_BELOW_50_200_CROSS" : "BULL_ABOVE_50D_200D";
            List<Map<String, Object>> shocks = pumpDumpEvents(candles);
            List<Map<String, Object>> analogs = dailyRegimeAnalogs(candles, end);
            Map<String, Object> downside = downsideScenarios(candles, livePrice, analogs);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", "LIVE_2020_PRESENT");
            value.put("source", "BINANCE_DAILY_OHLCV_PAGINATED");
            value.put("historyStart", date(candles.get(0).openTime));
            value.put("historyEnd", date(candles.get(end).openTime));
            value.put("dailyCandles", candles.size());
            value.put("regime", regime);
            value.put("currentVsHistory", Map.of(
                    "sma50", sma50, "sma200", sma200, "ath", ath,
                    "drawdownFromAthPercent", percent(ath, livePrice),
                    "return30dPercent", return30, "realizedVolatility30dPercent", volatility30,
                    "differentFromOldMarket", true,
                    "why", "ETF/options, institutional treasury participation and 24/7 CME access changed market structure; historical price behaviour remains evidence, not a copy."
            ));
            value.put("pumpDumpEvents", shocks);
            value.put("majorEventCount", shocks.size());
            value.put("regimeAnalogs", analogs);
            value.put("downsideScenarios", downside);
            value.put("liquidationHistoryPolicy", Map.of(
                    "status", "EXACT_2020_HISTORY_NOT_AVAILABLE_FREE",
                    "available", "Live Binance force-order events captured by this app from deployment onward",
                    "neverDo", "Do not convert candle volume into fake liquidation totals",
                    "lesson", "Most cascades combine leverage, crowded positioning, thin order books and stop/maintenance-margin triggers; use bounded leverage, hard stops and avoid adding to liquidation momentum."
            ));
            longHistoryCache.put(symbol, new CachedPattern(System.currentTimeMillis(), value));
            return value;
        } catch (Exception error) {
            return Map.of("status", "UNAVAILABLE", "reason", error.getMessage() == null ? "Historical fetch failed" : error.getMessage());
        }
    }

    private List<Map<String, Object>> pumpDumpEvents(List<CryptoMarketDataService.Candle> candles) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 30; i < candles.size() - 30; i++) {
            double day = percent(candles.get(i - 1).close, candles.get(i).close);
            double seven = percent(candles.get(i - 7).close, candles.get(i).close);
            if (Math.abs(day) < 7 && Math.abs(seven) < 15) continue;
            double forward7 = percent(candles.get(i).close, candles.get(i + 7).close);
            double forward30 = percent(candles.get(i).close, candles.get(i + 30).close);
            double worst = candles.subList(i, i + 31).stream().mapToDouble(c -> c.low).min().orElse(candles.get(i).low);
            String eventDate = date(candles.get(i).openTime);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", eventDate);
            row.put("direction", day >= 0 ? "PUMP" : "DUMP");
            row.put("dayMovePercent", day);
            row.put("sevenDayMovePercent", seven);
            row.put("next7dPercent", forward7);
            row.put("next30dPercent", forward30);
            row.put("next30dWorstDrawdownPercent", percent(candles.get(i).close, worst));
            row.put("knownContext", knownEventContext(eventDate));
            row.put("causePolicy", "Known context is a candidate explanation, never proof of a single cause.");
            events.add(row);
        }
        return events.stream().sorted(Comparator.comparingDouble(e -> -Math.abs(number(e.get("dayMovePercent"))))).limit(60).toList();
    }

    private List<Map<String, Object>> dailyRegimeAnalogs(List<CryptoMarketDataService.Candle> candles, int end) {
        double current30 = percent(candles.get(end - 30).close, candles.get(end).close);
        double current90 = percent(candles.get(end - 90).close, candles.get(end).close);
        double currentVol = standardDeviation(candles, end - 29, end);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 200; i < end - 30; i += 7) {
            double r30 = percent(candles.get(i - 30).close, candles.get(i).close);
            double r90 = percent(candles.get(i - 90).close, candles.get(i).close);
            double vol = standardDeviation(candles, i - 29, i);
            double distance = Math.abs(current30 - r30) / 8 + Math.abs(current90 - r90) / 20 + Math.abs(currentVol - vol) / 3;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date(candles.get(i).openTime));
            row.put("similarityPercent", Math.round(100 / (1 + distance)));
            row.put("then30dReturnPercent", r30);
            row.put("forward7dPercent", percent(candles.get(i).close, candles.get(i + 7).close));
            row.put("forward30dPercent", percent(candles.get(i).close, candles.get(i + 30).close));
            double low = candles.subList(i, i + 31).stream().mapToDouble(c -> c.low).min().orElse(candles.get(i).low);
            row.put("forward30dWorstPercent", percent(candles.get(i).close, low));
            rows.add(row);
        }
        return rows.stream().sorted(Comparator.comparingLong(e -> -Math.round(number(e.get("similarityPercent"))))).limit(12).toList();
    }

    private Map<String, Object> downsideScenarios(List<CryptoMarketDataService.Candle> candles, double livePrice, List<Map<String, Object>> analogs) {
        List<Double> analogWorst = analogs.stream().map(e -> number(e.get("forward30dWorstPercent"))).sorted().toList();
        double analogMedian = percentile(analogWorst, 0.5);
        double analogStress = percentile(analogWorst, 0.15);
        List<Double> swingLows = new ArrayList<>();
        for (int i = Math.max(5, candles.size() - 730); i < candles.size() - 5; i++) {
            double low = candles.get(i).low;
            boolean local = true;
            for (int j = i - 5; j <= i + 5; j++) if (candles.get(j).low < low) local = false;
            if (local && low < livePrice) swingLows.add(low);
        }
        swingLows.sort(Comparator.reverseOrder());
        List<Double> supports = swingLows.stream().filter(v -> v < livePrice * 0.995).limit(4).toList();
        return Map.of(
                "currentPrice", livePrice,
                "baseCaseAnalogDownsidePercent", analogMedian,
                "stressCaseAnalogDownsidePercent", analogStress,
                "baseCasePrice", livePrice * (1 + analogMedian / 100),
                "stressCasePrice", livePrice * (1 + analogStress / 100),
                "historicalSwingSupports", supports,
                "label", "SCENARIOS_NOT_FORECAST_OR_GUARANTEE",
                "rule", "Use nearest valid support and invalidation; never assume the stress target must trade."
        );
    }

    private double standardDeviation(List<CryptoMarketDataService.Candle> candles, int from, int to) {
        List<Double> returns = new ArrayList<>();
        for (int i = Math.max(1, from); i <= to; i++) returns.add(percent(candles.get(i - 1).close, candles.get(i).close));
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(returns.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, (int) Math.floor((sorted.size() - 1) * p))));
    }

    private String knownEventContext(String date) {
        return switch (date) {
            case "2020-03-12", "2020-03-13" -> "COVID-19 global liquidity shock and leveraged deleveraging";
            case "2021-02-08" -> "Tesla disclosed a bitcoin purchase; institutional adoption repricing";
            case "2021-05-19" -> "China restriction headlines plus crowded leverage unwind";
            case "2021-09-07" -> "El Salvador launch-day volatility and leverage flush";
            case "2022-05-09", "2022-05-10", "2022-05-11", "2022-05-12" -> "Terra/UST-LUNA collapse and contagion";
            case "2022-06-13" -> "Celsius withdrawal pause and credit-contagion fears";
            case "2022-11-08", "2022-11-09", "2022-11-10" -> "FTX solvency crisis and exchange contagion";
            case "2023-03-10", "2023-03-13" -> "US banking stress and stablecoin/liquidity repricing";
            case "2024-01-10", "2024-01-11" -> "US spot bitcoin ETF approval and launch flows";
            case "2024-04-19", "2024-04-20" -> "Bitcoin halving-period positioning";
            case "2024-08-05" -> "Global risk-off and yen carry-trade unwind";
            default -> "NO_VERIFIED_EVENT_MATCH_IN_CURATED_CALENDAR";
        };
    }

    private String date(long millis) { return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(); }

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

    private Map<String, Object> cmeGapPolicy(String symbol, double livePrice) {
        if (!"BTCUSDT".equals(symbol)) return Map.of("status", "NOT_APPLICABLE", "definition", "CME bitcoin gap tracking applies to BTC.");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", "NO_NEW_CLASSIC_WEEKEND_GAP_AFTER_24_7_LAUNCH");
        value.put("currentPrice", livePrice);
        value.put("definition", "A CME gap was the untraded range between the old Friday futures close and Sunday reopen. It is context, not a guaranteed fill target.");
        value.put("marketChange", "CME moved crypto futures to continuous 24/7 trading on May 29, 2026, except a weekly maintenance window; the classic weekend-gap setup no longer forms the old way.");
        value.put("legacyReportedLevels", List.of(
                Map.of("level", 80_000, "side", "ABOVE", "reportedAt", "2026-05-28", "verification", "REQUIRES_CME_CONTRACT_SERIES"),
                Map.of("level", 78_500, "side", "ABOVE", "reportedAt", "2026-05-28", "verification", "REQUIRES_CME_CONTRACT_SERIES"),
                Map.of("level", 70_000, "side", "BELOW", "reportedAt", "2026-05-28", "verification", livePrice < 70_000 ? "SPOT_TRADED_BELOW_LEVEL_NOT_PROOF_OF_CME_FILL" : "REQUIRES_CME_CONTRACT_SERIES")
        ));
        value.put("currentRule", "No CME-gap score enters the trade until an official/licensed contract candle feed verifies open and filled status.");
        return value;
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
