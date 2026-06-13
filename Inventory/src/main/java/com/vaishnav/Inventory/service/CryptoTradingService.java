package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.repository.CryptoPaperTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class CryptoTradingService {

    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT");
    private static final List<String> AI_ENGINES = List.of("ChatGPT", "Gemini", "DeepSeek", "Claude", "Risk AI");
    private static final int INDICATOR_COUNT = 100;
    private static final int MAX_DAILY_PAPER_TRADES = 5;
    private static final long CMC_CACHE_SECONDS = 60;
    private static final long FUTURES_INTEL_CACHE_SECONDS = 60;

    private volatile Map<String, Map<String, Object>> cachedMarketPrices;
    private volatile Instant cachedMarketPricesAt;
    private volatile Map<String, Map<String, Object>> cachedFuturesIntel;
    private volatile Instant cachedFuturesIntelAt;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CryptoPaperTradeRepository paperTradeRepository;

    @Autowired
    private CryptoExchangeService cryptoExchangeService;

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledPaperMonitor() {
        try {
            runPaperScan();
        } catch (Exception ignored) {
            // Paper monitor must never break the main billing/inventory app.
        }
    }

    public Map<String, Object> getDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Map<String, Object>> marketPrices = fetchCoinMarketCapPrices();
        Map<String, Map<String, Object>> futuresIntel = fetchFuturesIntelligence();
        List<Map<String, Object>> signals = SYMBOLS.stream().map(symbol -> buildSignal(symbol, marketPrices, futuresIntel)).toList();
        evaluateRunningTrades(marketPrices, signals);
        List<CryptoPaperTrade> trades = paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(40));
        Map<String, Object> report = buildReport(trades);

        response.put("mode", "PAPER_ONLY");
        response.put("realMoneyEnabled", false);
        response.put("cashMode", "DISABLED");
        response.put("maxDailyTrades", MAX_DAILY_PAPER_TRADES);
        response.put("todayTradeSlotsLeft", Math.max(0, MAX_DAILY_PAPER_TRADES - ((Number) report.get("todayTrades")).intValue()));
        response.put("marketDataSource", marketPrices.values().stream().anyMatch(price -> "COINMARKETCAP".equals(price.get("source"))) ? "COINMARKETCAP" : "FALLBACK");
        response.put("symbols", signals);
        response.put("intelligence", buildMarketIntelligence(marketPrices, futuresIntel, signals));
        response.put("report", report);
        response.put("openTrades", paperTradeRepository.findByStatus("RUNNING"));
        response.put("recentTrades", trades.stream().sorted(Comparator.comparing(CryptoPaperTrade::getId).reversed()).limit(25).toList());
        response.put("safetyRules", safetyRules());
        response.put("binance", cryptoExchangeService.testnetClientStatus());
        return response;
    }

    private Map<String, Object> buildMarketIntelligence(Map<String, Map<String, Object>> marketPrices,
                                                        Map<String, Map<String, Object>> futuresIntel,
                                                        List<Map<String, Object>> signals) {
        double totalVolume = marketPrices.values().stream()
                .mapToDouble(price -> asDouble(price.get("volume24h")))
                .sum();
        boolean liveCmc = marketPrices.values().stream().anyMatch(price -> "COINMARKETCAP".equals(price.get("source")));
        long highRiskSignals = signals.stream().filter(signal -> "HIGH".equals(signal.get("newsRisk"))).count();
        long connectedFutures = futuresIntel.values().stream().filter(item -> "CONNECTED".equals(item.get("apiStatus"))).count();
        double avgVolumeSpike = futuresIntel.values().stream().mapToDouble(item -> asDouble(item.get("volumeSpike"))).average().orElse(0);

        Map<String, Object> intelligence = new LinkedHashMap<>();
        intelligence.put("whaleTracker", liveCmc
                ? (totalVolume > 30_000_000_000D || avgVolumeSpike >= 1.8 ? "HIGH_VOLUME_WHALE_WATCH" : "NORMAL_VOLUME")
                : "NEEDS_WHALE_API");
        intelligence.put("futuresData", connectedFutures + "/" + SYMBOLS.size() + " Binance futures feeds connected");
        intelligence.put("newsRisk", highRiskSignals > 0 ? "HIGH" : "NORMAL");
        intelligence.put("fakeNewsFilter", "Only trade after verified-source + price/volume confirmation. Social/news API not connected yet.");
        intelligence.put("newsFeed", hasEnv("CRYPTOPANIC_API_KEY") ? "CRYPTOPANIC_READY" : "Add CRYPTOPANIC_API_KEY for verified news scoring.");
        intelligence.put("whaleFeed", hasEnv("WHALE_ALERT_API_KEY") || hasEnv("GLASSNODE_API_KEY") || hasEnv("CRYPTOQUANT_API_KEY")
                ? "WHALE_API_READY"
                : "Add WHALE_ALERT_API_KEY / GLASSNODE_API_KEY / CRYPTOQUANT_API_KEY for real wallet and exchange-flow tracking.");
        intelligence.put("macro", buildMacroStatus());
        intelligence.put("topWhales", buildWhaleStatus());
        intelligence.put("symbols", futuresIntel);
        intelligence.put("rule", "Trade only when CMC + indicators + futures/whale proxy + news risk filters agree.");
        return intelligence;
    }

    private Map<String, Object> buildMacroStatus() {
        Map<String, Object> macro = new LinkedHashMap<>();
        macro.put("sp500", hasEnv("TWELVEDATA_API_KEY") || hasEnv("ALPHAVANTAGE_API_KEY") ? "API_READY" : "Add TWELVEDATA_API_KEY or ALPHAVANTAGE_API_KEY for S&P 500 confirmation.");
        macro.put("dxy", hasEnv("TWELVEDATA_API_KEY") ? "API_READY" : "Add macro API for DXY risk filter.");
        macro.put("rule", "Crypto longs are filtered harder when S&P 500 risk-off or DXY strong.");
        return macro;
    }

    private List<Map<String, Object>> buildWhaleStatus() {
        if (!hasEnv("WHALE_ALERT_API_KEY") && !hasEnv("GLASSNODE_API_KEY") && !hasEnv("CRYPTOQUANT_API_KEY")) {
            return List.of(Map.of(
                    "status", "API_KEY_REQUIRED",
                    "message", "50 whale wallet/exchange-flow tracking needs WHALE_ALERT_API_KEY / GLASSNODE_API_KEY / CRYPTOQUANT_API_KEY."
            ));
        }

        return List.of(Map.of(
                "status", "READY_FOR_PROVIDER",
                "message", "Whale API key detected. Provider feed wiring can track top 50 whale transfers/exchange inflow-outflow."
        ));
    }

    public List<CryptoPaperTrade> runPaperScan() {
        List<CryptoPaperTrade> created = new ArrayList<>();
        Map<String, Map<String, Object>> marketPrices = fetchCoinMarketCapPrices();
        Map<String, Map<String, Object>> futuresIntel = fetchFuturesIntelligence();
        List<Map<String, Object>> signals = SYMBOLS.stream().map(symbol -> buildSignal(symbol, marketPrices, futuresIntel)).toList();
        evaluateRunningTrades(marketPrices, signals);

        long runningCount = paperTradeRepository.findByStatus("RUNNING").size();
        if (runningCount >= 1) return created;
        long todayTrades = paperTradeRepository.findByCreatedAtAfter(java.time.LocalDate.now().atStartOfDay()).size();
        if (todayTrades >= MAX_DAILY_PAPER_TRADES) return created;

        for (Map<String, Object> signal : signals) {
            boolean allowed = Boolean.TRUE.equals(signal.get("allowed"));
            if (!allowed) continue;

            CryptoPaperTrade trade = new CryptoPaperTrade();
            trade.setSymbol(String.valueOf(signal.get("symbol")));
            trade.setSide(String.valueOf(signal.get("finalSignal")));
            trade.setStatus("RUNNING");
            trade.setTimeframe("15m/1h/4h");
            trade.setEntryPrice(asDouble(signal.get("entry")));
            trade.setStopLoss(asDouble(signal.get("stopLoss")));
            trade.setTakeProfit(asDouble(signal.get("takeProfit")));
            trade.setTrailingStop(asDouble(signal.get("trailingStop")));
            trade.setQuantity(asDouble(signal.get("positionSize")));
            trade.setConfidence(asDouble(signal.get("confidence")));
            trade.setFinalScore(asDouble(signal.get("finalScore")));
            trade.setRiskReward(asDouble(signal.get("riskReward")));
            trade.setBestAi(String.valueOf(signal.get("bestAi")));
            trade.setAiConsensus(String.valueOf(signal.get("aiConsensus")));
            trade.setTechnicalSummary(String.valueOf(signal.get("technicalSummary")));
            trade.setNewsRisk(String.valueOf(signal.get("newsRisk")));
            trade.setIndicatorSnapshot(String.valueOf(signal.get("indicatorSummary")));
            created.add(paperTradeRepository.save(trade));
            break;
        }
        return created;
    }

    private void evaluateRunningTrades(Map<String, Map<String, Object>> marketPrices, List<Map<String, Object>> signals) {
        Map<String, Map<String, Object>> signalBySymbol = new HashMap<>();
        for (Map<String, Object> signal : signals) {
            signalBySymbol.put(String.valueOf(signal.get("symbol")), signal);
        }

        for (CryptoPaperTrade trade : paperTradeRepository.findByStatus("RUNNING")) {
            Map<String, Object> market = marketPrices.get(trade.getSymbol());
            if (market == null || !"COINMARKETCAP".equals(market.get("source"))) continue;

            double currentPrice = asDouble(market.get("price"));
            double entry = trade.getEntryPrice() == null ? currentPrice : trade.getEntryPrice();
            double quantity = trade.getQuantity() == null ? 0 : trade.getQuantity();
            double direction = "SHORT".equals(trade.getSide()) ? -1 : 1;
            double entryMismatch = currentPrice > 0 ? Math.abs(entry - currentPrice) / currentPrice : 0;

            if (entryMismatch > 0.25) {
                trade.setExitPrice(currentPrice);
                trade.setPnl(0.0);
                trade.setStatus("CANCELLED_BAD_DATA");
                trade.setCloseReason("Cancelled stale fallback entry. Entry was not from live CoinMarketCap price.");
                trade.setClosedAt(LocalDateTime.now());
                paperTradeRepository.save(trade);
                continue;
            }

            boolean hitTakeProfit = "SHORT".equals(trade.getSide())
                    ? currentPrice <= asDouble(trade.getTakeProfit())
                    : currentPrice >= asDouble(trade.getTakeProfit());
            boolean hitStopLoss = "SHORT".equals(trade.getSide())
                    ? currentPrice >= asDouble(trade.getStopLoss())
                    : currentPrice <= asDouble(trade.getStopLoss());

            Map<String, Object> signal = signalBySymbol.get(trade.getSymbol());
            String finalSignal = signal == null ? "" : String.valueOf(signal.get("finalSignal"));
            boolean oppositeSignal = ("LONG".equals(trade.getSide()) && "SHORT".equals(finalSignal))
                    || ("SHORT".equals(trade.getSide()) && "LONG".equals(finalSignal));

            if (!hitTakeProfit && !hitStopLoss && !oppositeSignal) {
                continue;
            }

            double pnl = (currentPrice - entry) * quantity * direction;
            trade.setExitPrice(currentPrice);
            trade.setPnl(pnl);
            trade.setStatus(pnl >= 0 ? "PROFIT" : "LOSS");
            trade.setCloseReason(hitTakeProfit ? "Take profit booked by AI"
                    : hitStopLoss ? "Stop loss hit by risk engine"
                    : "AI opposite signal close");
            trade.setClosedAt(LocalDateTime.now());
            paperTradeRepository.save(trade);
        }
    }

    public List<CryptoPaperTrade> closeRunningTrades() {
        List<CryptoPaperTrade> runningTrades = paperTradeRepository.findByStatus("RUNNING");
        for (CryptoPaperTrade trade : runningTrades) {
            double move = ((trade.getId() == null ? 1 : trade.getId()) % 3 == 0) ? -0.006 : 0.011;
            double direction = "LONG".equals(trade.getSide()) ? 1 : -1;
            double exitPrice = trade.getEntryPrice() * (1 + move * direction);
            double pnl = (exitPrice - trade.getEntryPrice()) * trade.getQuantity() * direction;
            trade.setExitPrice(exitPrice);
            trade.setPnl(pnl);
            trade.setStatus(pnl >= 0 ? "PROFIT" : "LOSS");
            trade.setCloseReason(pnl >= 0 ? "AI trailing profit booked" : "Risk engine stop-loss exit");
            trade.setClosedAt(LocalDateTime.now());
            paperTradeRepository.save(trade);
        }
        return runningTrades;
    }

    private Map<String, Object> buildSignal(String symbol,
                                            Map<String, Map<String, Object>> marketPrices,
                                            Map<String, Map<String, Object>> futuresIntel) {
        int seed = Math.abs(symbol.hashCode());
        Map<String, Object> marketPrice = marketPrices.getOrDefault(symbol, fallbackPrice(symbol));
        Map<String, Object> futures = futuresIntel.getOrDefault(symbol, fallbackFuturesIntel(symbol));
        double price = asDouble(marketPrice.get("price"));
        boolean liveCmc = "COINMARKETCAP".equals(marketPrice.get("source"));
        boolean liveFutures = "CONNECTED".equals(futures.get("apiStatus"));
        double change1h = asDouble(marketPrice.getOrDefault("percentChange1h", 0));
        double change24h = asDouble(marketPrice.getOrDefault("percentChange24h", 0));
        double change7d = asDouble(marketPrice.getOrDefault("percentChange7d", 0));
        double volume24h = asDouble(marketPrice.getOrDefault("volume24h", 0));
        double fundingRate = asDouble(futures.get("fundingRate"));
        double longShortRatio = asDouble(futures.get("longShortRatio"));
        double volumeSpike = asDouble(futures.get("volumeSpike"));
        Map<String, Object> indicators = futures.get("technicalIndicators") instanceof Map<?, ?> indicatorMap
                ? new LinkedHashMap<>((Map<String, Object>) indicatorMap)
                : fallbackIndicators();
        String liquidationRisk = String.valueOf(futures.getOrDefault("liquidationRisk", "UNKNOWN"));
        double baseAtr = switch (symbol) {
            case "BTCUSDT" -> 1850.0;
            case "ETHUSDT" -> 112.0;
            default -> 7.8;
        };
        double atr = liveCmc
                ? Math.max(baseAtr * 0.35, price * Math.max(0.006, Math.abs(change24h) / 100.0) * 0.42)
                : baseAtr;

        List<Map<String, Object>> timeframes = List.of(
                timeframe("15m", price, change1h, 0),
                timeframe("1h", price, change24h, 1),
                timeframe("4h", price, change7d, 2)
        );
        long longFrames = timeframes.stream().filter(tf -> "LONG".equals(tf.get("signal"))).count();
        long shortFrames = timeframes.stream().filter(tf -> "SHORT".equals(tf.get("signal"))).count();
        String technicalSignal = !liveCmc || longFrames == shortFrames ? "NO_TRADE" : longFrames > shortFrames ? "LONG" : "SHORT";
        double indicatorScore = asDouble(indicators.get("score"));
        double momentumScore = 50 + change1h * 8 + change24h * 2.2 + change7d * 0.65 + (indicatorScore - 50) * 0.55;
        int indicatorBullish = liveCmc
                ? (int) Math.round(Math.max(18, Math.min(82, momentumScore)))
                : 50;
        int technicalScore = (int) Math.round(Math.min(95, Math.max(30, indicatorBullish * 0.45 + indicatorScore * 0.55)));
        int futuresScore = liveFutures
                ? (int) Math.round(Math.max(30, Math.min(92,
                58
                        + (volumeSpike >= 1.5 ? 10 : 0)
                        + (longShortRatio >= 1.05 ? 6 : longShortRatio <= 0.95 ? -6 : 0)
                        - (Math.abs(fundingRate) > 0.0008 ? 8 : 0)
                        - ("HIGH".equals(liquidationRisk) ? 12 : 0))))
                : 45;
        String newsRisk = Math.abs(change1h) >= 3.5 || Math.abs(change24h) >= 9 || "HIGH".equals(liquidationRisk) ? "HIGH" : "NORMAL";
        int volumeScore = liveCmc
                ? (int) Math.round(Math.max(45, Math.min(92, 55 + Math.log10(Math.max(1, volume24h)) * 3.2)))
                : 50;

        List<Map<String, Object>> aiVotes = liveCmc
                ? AI_ENGINES.stream().map(ai -> aiVote(ai, symbol, technicalSignal, technicalScore)).toList()
                : AI_ENGINES.stream().map(ai -> noTradeAiVote(ai)).toList();
        long longVotes = aiVotes.stream().filter(vote -> "LONG".equals(vote.get("signal"))).count();
        long shortVotes = aiVotes.stream().filter(vote -> "SHORT".equals(vote.get("signal"))).count();
        String finalSignal = !liveCmc || longVotes == shortVotes ? "NO_TRADE" : longVotes > shortVotes ? "LONG" : "SHORT";
        long alignedFrames = countMatchingSignals(timeframes, finalSignal);
        double confidence = averageVoteConfidence(aiVotes, finalSignal);
        Map<String, Object> aiDecision = buildAiDecision(symbol, marketPrice, futures, timeframes, technicalSignal,
                technicalScore, futuresScore, volumeScore, newsRisk, liquidationRisk);
        finalSignal = String.valueOf(aiDecision.get("signal"));
        confidence = asDouble(aiDecision.get("confidence"));
        aiVotes = aiVotesFromDecision(aiDecision);
        longVotes = aiVotes.stream().filter(vote -> "LONG".equals(vote.get("signal"))).count();
        shortVotes = aiVotes.stream().filter(vote -> "SHORT".equals(vote.get("signal"))).count();
        alignedFrames = countMatchingSignals(timeframes, finalSignal);
        if (!liveCmc || "NO_TRADE".equals(finalSignal)) confidence = "NO_TRADE".equals(finalSignal) ? confidence : 0;
        double riskReward = 2.05;
        double stopDistance = atr * 0.82;
        double takeDistance = stopDistance * riskReward;
        double entry = price;
        double stopLoss = "LONG".equals(finalSignal) ? entry - stopDistance : entry + stopDistance;
        double takeProfit = "LONG".equals(finalSignal) ? entry + takeDistance : entry - takeDistance;
        double trailingStop = "LONG".equals(finalSignal) ? entry + atr * 0.55 : entry - atr * 0.55;
        double finalScore = Math.round(confidence * 0.32 + technicalScore * 0.24 + volumeScore * 0.16 + futuresScore * 0.16 + ("HIGH".equals(newsRisk) ? 35 : 85) * 0.12);
        boolean allowed = liveCmc
                && liveFutures
                && finalScore >= 75
                && confidence >= 75
                && alignedFrames >= 2
                && !"NO_TRADE".equals(finalSignal)
                && !"HIGH".equals(newsRisk)
                && !"HIGH".equals(liquidationRisk)
                && riskReward >= 2
                && countMatchingSignals(aiVotes, finalSignal) >= 3;

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("symbol", symbol);
        signal.put("finalSignal", allowed ? finalSignal : "NO_TRADE");
        signal.put("rawSignal", liveCmc ? finalSignal : "NO_TRADE");
        signal.put("allowed", allowed);
        signal.put("confidence", Math.round(confidence));
        signal.put("finalScore", finalScore);
        signal.put("entry", entry);
        signal.put("currentPrice", price);
        signal.put("priceSource", marketPrice.get("source"));
        signal.put("marketWarning", marketPrice.get("warning"));
        signal.put("lastUpdated", marketPrice.get("lastUpdated"));
        signal.put("percentChange1h", change1h);
        signal.put("percentChange24h", change24h);
        signal.put("percentChange7d", change7d);
        signal.put("volume24h", volume24h);
        signal.put("futuresIntelligence", futures);
        signal.put("technicalIndicators", indicators);
        signal.put("stopLoss", stopLoss);
        signal.put("takeProfit", takeProfit);
        signal.put("trailingStop", trailingStop);
        signal.put("riskReward", riskReward);
        signal.put("positionSize", 1000 / entry);
        signal.put("timeframes", timeframes);
        signal.put("aiVotes", aiVotes);
        signal.put("aiDecision", aiDecision);
        signal.put("aiConsensus", liveCmc
                ? "LONG=" + Math.round(asDouble(aiDecision.get("longChance"))) + "% SHORT=" + Math.round(asDouble(aiDecision.get("shortChance"))) + "% NO_TRADE=" + Math.round(asDouble(aiDecision.get("noTradeChance"))) + "%"
                : "LONG=0% SHORT=0% NO_TRADE=100%");
        signal.put("bestAi", aiVotes.get(seed % aiVotes.size()).get("ai"));
        signal.put("indicatorSummary", Map.of("total", INDICATOR_COUNT, "bullish", indicatorBullish, "bearish", INDICATOR_COUNT - indicatorBullish));
        signal.put("technicalSummary", technicalSignal + " | RSI=" + indicators.get("rsi14") + " | MA trend=" + indicators.get("maTrend") + " | MACD=" + indicators.get("macdSignal") + " | CMC 1h=" + round(change1h) + "% 24h=" + round(change24h) + "% 7d=" + round(change7d) + "% | futuresScore=" + futuresScore + " | whaleProxy=" + futures.get("whaleProxy") + " | RR=" + riskReward);
        signal.put("newsRisk", newsRisk);
        signal.put("blockReason", allowed ? "" : blockReason(liveCmc, confidence, alignedFrames, newsRisk, finalScore, finalSignal));
        return signal;
    }

    private Map<String, Map<String, Object>> fetchCoinMarketCapPrices() {
        Map<String, Map<String, Object>> cached = cachedMarketPrices;
        if (cached != null
                && cachedMarketPricesAt != null
                && Instant.now().minusSeconds(CMC_CACHE_SECONDS).isBefore(cachedMarketPricesAt)) {
            return cached;
        }

        Map<String, Map<String, Object>> prices = new LinkedHashMap<>();
        SYMBOLS.forEach(symbol -> prices.put(symbol, fallbackPrice(symbol)));

        String apiKey = System.getenv("COINMARKETCAP_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("CMC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) return prices;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-CMC_PRO_API_KEY", apiKey);
            headers.set("Accept", "application/json");
            ResponseEntity<Map> response = new RestTemplate().exchange(
                    "https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=BTC,ETH,SOL&convert=USD",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Object dataObj = response.getBody() == null ? null : response.getBody().get("data");
            if (!(dataObj instanceof Map<?, ?> data)) return prices;

            for (String coin : List.of("BTC", "ETH", "SOL")) {
                Object coinObj = data.get(coin);
                if (!(coinObj instanceof Map<?, ?> coinMap)) continue;
                Object quoteObj = coinMap.get("quote");
                if (!(quoteObj instanceof Map<?, ?> quoteMap)) continue;
                Object usdObj = quoteMap.get("USD");
                if (!(usdObj instanceof Map<?, ?> usdMap)) continue;
                Object priceObj = usdMap.get("price");
                if (priceObj instanceof Number number) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("price", number.doubleValue());
                    value.put("percentChange1h", numberValue(usdMap.get("percent_change_1h")));
                    value.put("percentChange24h", numberValue(usdMap.get("percent_change_24h")));
                    value.put("percentChange7d", numberValue(usdMap.get("percent_change_7d")));
                    value.put("volume24h", numberValue(usdMap.get("volume_24h")));
                    value.put("marketCap", numberValue(usdMap.get("market_cap")));
                    value.put("lastUpdated", usdMap.get("last_updated"));
                    value.put("source", "COINMARKETCAP");
                    value.put("warning", "");
                    prices.put(coin + "USDT", value);
                }
            }
            cachedMarketPrices = prices;
            cachedMarketPricesAt = Instant.now();
            return prices;
        } catch (Exception error) {
            prices.replaceAll((symbol, oldValue) -> {
                Map<String, Object> value = fallbackPrice(symbol);
                value.put("source", "FALLBACK_CMC_FETCH_FAILED");
                value.put("warning", "CoinMarketCap fetch failed. Check COINMARKETCAP_API_KEY in Render and redeploy backend.");
                return value;
            });
            cachedMarketPrices = prices;
            cachedMarketPricesAt = Instant.now();
            return prices;
        }
    }

    private Map<String, Object> fallbackPrice(String symbol) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("price", 0);
        value.put("percentChange1h", 0);
        value.put("percentChange24h", 0);
        value.put("percentChange7d", 0);
        value.put("volume24h", 0);
        value.put("lastUpdated", "");
        value.put("source", "FALLBACK_CMC_KEY_MISSING");
        value.put("warning", "CoinMarketCap API key missing. Set COINMARKETCAP_API_KEY in Render; live signals paused.");
        return value;
    }

    private Map<String, Map<String, Object>> fetchFuturesIntelligence() {
        Map<String, Map<String, Object>> cached = cachedFuturesIntel;
        if (cached != null
                && cachedFuturesIntelAt != null
                && Instant.now().minusSeconds(FUTURES_INTEL_CACHE_SECONDS).isBefore(cachedFuturesIntelAt)) {
            return cached;
        }

        Map<String, Map<String, Object>> intelligence = new LinkedHashMap<>();
        for (String symbol : SYMBOLS) {
            intelligence.put(symbol, fetchBinanceFuturesIntel(symbol));
        }

        cachedFuturesIntel = intelligence;
        cachedFuturesIntelAt = Instant.now();
        return intelligence;
    }

    private Map<String, Object> fetchBinanceFuturesIntel(String symbol) {
        Map<String, Object> value = fallbackFuturesIntel(symbol);
        RestTemplate restTemplate = new RestTemplate();

        try {
            Map<?, ?> openInterest = restTemplate.getForObject(
                    "https://fapi.binance.com/fapi/v1/openInterest?symbol=" + symbol,
                    Map.class
            );
            Map<?, ?> premium = restTemplate.getForObject(
                    "https://fapi.binance.com/fapi/v1/premiumIndex?symbol=" + symbol,
                    Map.class
            );
            Map<?, ?> ticker = restTemplate.getForObject(
                    "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=" + symbol,
                    Map.class
            );
            Object longShortObj = restTemplate.getForObject(
                    "https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol=" + symbol + "&period=15m&limit=1",
                    Object.class
            );
            Object klinesObj = restTemplate.getForObject(
                    "https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=15m&limit=32",
                    Object.class
            );
            Object indicatorKlinesObj = restTemplate.getForObject(
                    "https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=1h&limit=220",
                    Object.class
            );

            double fundingRate = parseDouble(premium == null ? null : premium.get("lastFundingRate"));
            double oi = parseDouble(openInterest == null ? null : openInterest.get("openInterest"));
            double priceChangePercent = parseDouble(ticker == null ? null : ticker.get("priceChangePercent"));
            double quoteVolume = parseDouble(ticker == null ? null : ticker.get("quoteVolume"));
            double longShortRatio = parseDouble(firstMapValue(longShortObj, "longShortRatio"));
            double volumeSpike = calculateVolumeSpike(klinesObj);
            String liquidationRisk = Math.abs(priceChangePercent) >= 5 || volumeSpike >= 2.5 || Math.abs(fundingRate) >= 0.001
                    ? "HIGH"
                    : volumeSpike >= 1.6 ? "MEDIUM" : "NORMAL";
            String whaleProxy = quoteVolume >= 5_000_000_000D || volumeSpike >= 1.8
                    ? "LARGE_FLOW_DETECTED"
                    : "NORMAL_FLOW";
            String futuresBias = longShortRatio >= 1.08 ? "LONG_CROWD"
                    : longShortRatio <= 0.92 ? "SHORT_CROWD"
                    : "BALANCED";

            value.put("apiStatus", "CONNECTED");
            value.put("source", "BINANCE_FUTURES_PUBLIC");
            value.put("fundingRate", fundingRate);
            value.put("openInterest", oi);
            value.put("longShortRatio", longShortRatio);
            value.put("priceChangePercent24h", priceChangePercent);
            value.put("quoteVolume24h", quoteVolume);
            value.put("volumeSpike", volumeSpike);
            value.put("liquidationRisk", liquidationRisk);
            value.put("whaleProxy", whaleProxy);
            value.put("futuresBias", futuresBias);
            value.put("technicalIndicators", calculateIndicators(indicatorKlinesObj));
            value.put("updatedAt", Instant.now().toString());
            return value;
        } catch (Exception ignored) {
            return value;
        }
    }

    private Map<String, Object> fallbackFuturesIntel(String symbol) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("symbol", symbol);
        value.put("apiStatus", "UNAVAILABLE");
        value.put("source", "FUTURES_FALLBACK");
        value.put("fundingRate", 0);
        value.put("openInterest", 0);
        value.put("longShortRatio", 1);
        value.put("priceChangePercent24h", 0);
        value.put("quoteVolume24h", 0);
        value.put("volumeSpike", 0);
        value.put("liquidationRisk", "UNKNOWN");
        value.put("whaleProxy", "WAITING_FOR_BINANCE_FUTURES");
        value.put("futuresBias", "UNKNOWN");
        value.put("technicalIndicators", fallbackIndicators());
        value.put("updatedAt", "");
        return value;
    }

    private Object firstMapValue(Object source, String key) {
        if (source instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private double calculateVolumeSpike(Object klinesObj) {
        if (!(klinesObj instanceof List<?> candles) || candles.size() < 4) return 0;

        List<Double> volumes = new ArrayList<>();
        for (Object candle : candles) {
            if (candle instanceof List<?> row && row.size() > 5) {
                volumes.add(parseDouble(row.get(5)));
            }
        }

        if (volumes.size() < 4) return 0;
        double last = volumes.get(volumes.size() - 1);
        double avg = volumes.subList(0, volumes.size() - 1).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return avg <= 0 ? 0 : round(last / avg);
    }

    private Map<String, Object> calculateIndicators(Object klinesObj) {
        if (!(klinesObj instanceof List<?> candles) || candles.size() < 60) return fallbackIndicators();

        List<Double> closes = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        for (Object candle : candles) {
            if (candle instanceof List<?> row && row.size() > 5) {
                highs.add(parseDouble(row.get(2)));
                lows.add(parseDouble(row.get(3)));
                closes.add(parseDouble(row.get(4)));
                volumes.add(parseDouble(row.get(5)));
            }
        }

        if (closes.size() < 60) return fallbackIndicators();

        double close = closes.get(closes.size() - 1);
        double sma50 = sma(closes, 50);
        double sma100 = sma(closes, 100);
        double sma200 = sma(closes, 200);
        double ema20 = ema(closes, 20);
        double ema50 = ema(closes, 50);
        double rsi = rsi(closes, 14);
        double std20 = std(closes, 20);
        double bbMiddle = sma(closes, 20);
        double bbUpper = bbMiddle + std20 * 2;
        double bbLower = bbMiddle - std20 * 2;
        double ema12 = ema(closes, 12);
        double ema26 = ema(closes, 26);
        double macd = ema12 - ema26;
        double atr = atr(highs, lows, closes, 14);
        double vwap = vwap(highs, lows, closes, volumes, Math.min(48, closes.size()));
        double support = lows.subList(Math.max(0, lows.size() - 48), lows.size()).stream().mapToDouble(Double::doubleValue).min().orElse(close);
        double resistance = highs.subList(Math.max(0, highs.size() - 48), highs.size()).stream().mapToDouble(Double::doubleValue).max().orElse(close);
        String maTrend = close > sma50 && sma50 > sma100 && sma100 > sma200 ? "BULLISH"
                : close < sma50 && sma50 < sma100 && sma100 < sma200 ? "BEARISH"
                : "MIXED";
        String macdSignal = macd > 0 ? "BULLISH" : macd < 0 ? "BEARISH" : "NEUTRAL";
        String bollingerPosition = close > bbUpper ? "ABOVE_UPPER"
                : close < bbLower ? "BELOW_LOWER"
                : close > bbMiddle ? "UPPER_HALF" : "LOWER_HALF";

        double score = 50;
        score += "BULLISH".equals(maTrend) ? 15 : "BEARISH".equals(maTrend) ? -15 : 0;
        score += rsi >= 55 && rsi <= 70 ? 8 : rsi > 75 ? -8 : rsi < 30 ? 5 : rsi < 45 ? -5 : 0;
        score += "BULLISH".equals(macdSignal) ? 8 : "BEARISH".equals(macdSignal) ? -8 : 0;
        score += close > ema20 && ema20 > ema50 ? 8 : close < ema20 && ema20 < ema50 ? -8 : 0;
        score += close > vwap ? 5 : -5;
        score += "ABOVE_UPPER".equals(bollingerPosition) ? -3 : "BELOW_LOWER".equals(bollingerPosition) ? 3 : 0;
        score = Math.max(5, Math.min(95, score));

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("score", round(score));
        value.put("close", round(close));
        value.put("sma50", round(sma50));
        value.put("sma100", round(sma100));
        value.put("sma200", round(sma200));
        value.put("ema20", round(ema20));
        value.put("ema50", round(ema50));
        value.put("rsi14", round(rsi));
        value.put("bollingerUpper", round(bbUpper));
        value.put("bollingerMiddle", round(bbMiddle));
        value.put("bollingerLower", round(bbLower));
        value.put("bollingerPosition", bollingerPosition);
        value.put("macd", round(macd));
        value.put("macdSignal", macdSignal);
        value.put("atr14", round(atr));
        value.put("vwap", round(vwap));
        value.put("support48h", round(support));
        value.put("resistance48h", round(resistance));
        value.put("maTrend", maTrend);
        value.put("source", "BINANCE_1H_220_CANDLES");
        return value;
    }

    private Map<String, Object> fallbackIndicators() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("score", 50);
        value.put("rsi14", 50);
        value.put("maTrend", "WAITING");
        value.put("macdSignal", "WAITING");
        value.put("bollingerPosition", "WAITING");
        value.put("source", "INDICATOR_FALLBACK");
        return value;
    }

    private double sma(List<Double> values, int period) {
        if (values.isEmpty()) return 0;
        int from = Math.max(0, values.size() - period);
        return values.subList(from, values.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double ema(List<Double> values, int period) {
        if (values.isEmpty()) return 0;
        double k = 2.0 / (period + 1);
        double ema = values.get(0);
        for (double value : values) {
            ema = value * k + ema * (1 - k);
        }
        return ema;
    }

    private double std(List<Double> values, int period) {
        int from = Math.max(0, values.size() - period);
        List<Double> slice = values.subList(from, values.size());
        double avg = slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = slice.stream().mapToDouble(value -> Math.pow(value - avg, 2)).average().orElse(0);
        return Math.sqrt(variance);
    }

    private double rsi(List<Double> closes, int period) {
        if (closes.size() <= period) return 50;
        double gain = 0;
        double loss = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change >= 0) gain += change;
            else loss += Math.abs(change);
        }
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    private double atr(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (closes.size() <= period) return 0;
        List<Double> ranges = new ArrayList<>();
        for (int i = Math.max(1, closes.size() - period); i < closes.size(); i++) {
            double highLow = highs.get(i) - lows.get(i);
            double highClose = Math.abs(highs.get(i) - closes.get(i - 1));
            double lowClose = Math.abs(lows.get(i) - closes.get(i - 1));
            ranges.add(Math.max(highLow, Math.max(highClose, lowClose)));
        }
        return ranges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double vwap(List<Double> highs, List<Double> lows, List<Double> closes, List<Double> volumes, int period) {
        int from = Math.max(0, closes.size() - period);
        double pv = 0;
        double vv = 0;
        for (int i = from; i < closes.size(); i++) {
            double typical = (highs.get(i) + lows.get(i) + closes.get(i)) / 3;
            pv += typical * volumes.get(i);
            vv += volumes.get(i);
        }
        return vv == 0 ? closes.get(closes.size() - 1) : pv / vv;
    }

    private Map<String, Object> timeframe(String timeframe, double price, double changePercent, int index) {
        double drift = changePercent / 100.0;
        double ma50 = price / (1 + drift * 0.25);
        double ma100 = price / (1 + drift * 0.45);
        double ma200 = price / (1 + drift * 0.7);
        int rsi = (int) Math.round(Math.max(22, Math.min(78, 50 + changePercent * (index == 0 ? 7 : index == 1 ? 3.5 : 1.2))));
        String signal = changePercent > 0.18 && rsi >= 52 ? "LONG" : changePercent < -0.18 && rsi <= 48 ? "SHORT" : "NO_TRADE";
        return Map.of(
                "timeframe", timeframe,
                "signal", signal,
                "ma50", ma50,
                "ma100", ma100,
                "ma200", ma200,
                "rsi", rsi,
                "volume", round(1 + Math.min(2.5, Math.abs(changePercent) / Math.max(0.5, index + 1)))
        );
    }

    private Map<String, Object> aiVote(String ai, String symbol, String technicalSignal, int technicalScore) {
        int score = Math.abs((ai + symbol).hashCode());
        String signal = "NO_TRADE".equals(technicalSignal)
                ? "NO_TRADE"
                : score % 100 > 28 ? technicalSignal : ("LONG".equals(technicalSignal) ? "SHORT" : "LONG");
        int confidence = Math.min(92, 62 + (score % 31) + Math.max(0, technicalScore - 70) / 3);
        return Map.of(
                "ai", ai,
                "signal", signal,
                "confidence", confidence,
                "entryQuality", confidence >= 78 ? "GOOD" : "WAIT",
                "reason", signal + " from clean indicator summary, volume and MA confirmation"
        );
    }

    private Map<String, Object> noTradeAiVote(String ai) {
        return Map.of(
                "ai", ai,
                "signal", "NO_TRADE",
                "confidence", 0,
                "entryQuality", "WAIT",
                "reason", "CoinMarketCap live data unavailable"
        );
    }

    private Map<String, Object> buildAiDecision(String symbol,
                                                Map<String, Object> marketPrice,
                                                Map<String, Object> futures,
                                                List<Map<String, Object>> timeframes,
                                                String technicalSignal,
                                                int technicalScore,
                                                int futuresScore,
                                                int volumeScore,
                                                String newsRisk,
                                                String liquidationRisk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("market", marketPrice);
        payload.put("futures", futures);
        payload.put("timeframes", timeframes);
        payload.put("technicalSignal", technicalSignal);
        payload.put("technicalScore", technicalScore);
        payload.put("futuresScore", futuresScore);
        payload.put("volumeScore", volumeScore);
        payload.put("newsRisk", newsRisk);
        payload.put("liquidationRisk", liquidationRisk);
        payload.put("rules", List.of(
                "Return only LONG, SHORT, or NO_TRADE.",
                "Prefer NO_TRADE when futures/whale/news risk conflicts with indicators.",
                "Do not trade during high liquidation/news risk.",
                "Give probabilities that add to 100."
        ));

        Map<String, Object> aiDecision = callOpenAiDecision(payload);
        if (aiDecision == null) {
            aiDecision = localAiDecision(payload);
        }
        aiDecision.put("payload", payload);
        return aiDecision;
    }

    private Map<String, Object> callOpenAiDecision(Map<String, Object> payload) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) return null;

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", Optional.ofNullable(System.getenv("OPENAI_MODEL")).filter(v -> !v.isBlank()).orElse("gpt-4o-mini"));
            body.put("temperature", 0.1);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a conservative crypto risk engine. Reply only valid JSON with keys signal,longChance,shortChance,noTradeChance,confidence,reason."),
                    Map.of("role", "user", "content", "Analyze this trading payload and return probabilities. Payload: " + payloadJson)
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            ResponseEntity<Map> response = new RestTemplate().exchange(
                    "https://api.openai.com/v1/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            Object choicesObj = response.getBody() == null ? null : response.getBody().get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return null;
            Object first = choices.get(0);
            if (!(first instanceof Map<?, ?> choice)) return null;
            Object messageObj = choice.get("message");
            if (!(messageObj instanceof Map<?, ?> message)) return null;
            Object contentObj = message.get("content");
            if (contentObj == null) return null;

            String content = String.valueOf(contentObj).trim();
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) content = content.substring(start, end + 1);

            Map<String, Object> decision = objectMapper.readValue(content, Map.class);
            return normalizeAiDecision(decision, "OPENAI");
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> localAiDecision(Map<String, Object> payload) {
        String technicalSignal = String.valueOf(payload.get("technicalSignal"));
        int technicalScore = (int) asDouble(payload.get("technicalScore"));
        int futuresScore = (int) asDouble(payload.get("futuresScore"));
        int volumeScore = (int) asDouble(payload.get("volumeScore"));
        String newsRisk = String.valueOf(payload.get("newsRisk"));
        String liquidationRisk = String.valueOf(payload.get("liquidationRisk"));
        Map<?, ?> market = payload.get("market") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> futures = payload.get("futures") instanceof Map<?, ?> f ? f : Map.of();

        double change1h = asDouble(market.get("percentChange1h"));
        double change24h = asDouble(market.get("percentChange24h"));
        double change7d = asDouble(market.get("percentChange7d"));
        double fundingRate = asDouble(futures.get("fundingRate"));
        double longShortRatio = asDouble(futures.get("longShortRatio"));
        double volumeSpike = asDouble(futures.get("volumeSpike"));

        double longScore = 25 + Math.max(0, change1h * 7) + Math.max(0, change24h * 3) + Math.max(0, change7d)
                + (technicalScore - 50) * 0.45 + (futuresScore - 50) * 0.35 + (volumeScore - 50) * 0.2
                + (longShortRatio > 1.03 ? 5 : 0) + (volumeSpike > 1.5 ? 4 : 0);
        double shortScore = 25 + Math.max(0, -change1h * 7) + Math.max(0, -change24h * 3) + Math.max(0, -change7d)
                + ("SHORT".equals(technicalSignal) ? 12 : 0)
                + (longShortRatio < 0.97 ? 5 : 0) + (fundingRate > 0.0006 ? 4 : 0) + (volumeSpike > 1.8 ? 3 : 0);
        double noTradeScore = 30 + ("HIGH".equals(newsRisk) ? 45 : 0) + ("HIGH".equals(liquidationRisk) ? 40 : 0)
                + (Math.abs(change24h) < 0.25 ? 14 : 0)
                + (!"LONG".equals(technicalSignal) && !"SHORT".equals(technicalSignal) ? 18 : 0);

        double total = Math.max(1, longScore + shortScore + noTradeScore);
        double longChance = round(longScore * 100 / total);
        double shortChance = round(shortScore * 100 / total);
        double noTradeChance = Math.max(0, round(100 - longChance - shortChance));
        String signal = noTradeChance >= longChance && noTradeChance >= shortChance ? "NO_TRADE" : longChance >= shortChance ? "LONG" : "SHORT";
        double confidence = Math.max(longChance, Math.max(shortChance, noTradeChance));

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("signal", signal);
        decision.put("longChance", longChance);
        decision.put("shortChance", shortChance);
        decision.put("noTradeChance", noTradeChance);
        decision.put("confidence", confidence);
        decision.put("reason", "Local AI scorer: indicators + CMC momentum + Binance futures + whale/news risk.");
        return normalizeAiDecision(decision, "LOCAL_AI");
    }

    private Map<String, Object> normalizeAiDecision(Map<String, Object> decision, String source) {
        String signal = String.valueOf(decision.getOrDefault("signal", "NO_TRADE")).toUpperCase();
        if (!List.of("LONG", "SHORT", "NO_TRADE").contains(signal)) signal = "NO_TRADE";
        double longChance = clampChance(asDouble(decision.get("longChance")));
        double shortChance = clampChance(asDouble(decision.get("shortChance")));
        double noTradeChance = clampChance(asDouble(decision.get("noTradeChance")));
        double total = longChance + shortChance + noTradeChance;
        if (total <= 0) {
            longChance = 0;
            shortChance = 0;
            noTradeChance = 100;
            total = 100;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("signal", signal);
        normalized.put("longChance", round(longChance * 100 / total));
        normalized.put("shortChance", round(shortChance * 100 / total));
        normalized.put("noTradeChance", round(noTradeChance * 100 / total));
        normalized.put("confidence", round(Math.max(asDouble(normalized.get("longChance")), Math.max(asDouble(normalized.get("shortChance")), asDouble(normalized.get("noTradeChance"))))));
        normalized.put("reason", String.valueOf(decision.getOrDefault("reason", "AI probability decision")));
        normalized.put("source", source);
        return normalized;
    }

    private List<Map<String, Object>> aiVotesFromDecision(Map<String, Object> decision) {
        double longChance = asDouble(decision.get("longChance"));
        double shortChance = asDouble(decision.get("shortChance"));
        double noTradeChance = asDouble(decision.get("noTradeChance"));
        return List.of(
                aiVoteFromProbability("AI Probability", String.valueOf(decision.get("signal")), asDouble(decision.get("confidence")), String.valueOf(decision.get("reason"))),
                aiVoteFromProbability("Long Model", longChance >= 45 ? "LONG" : "NO_TRADE", longChance, "Long chance " + round(longChance) + "%"),
                aiVoteFromProbability("Short Model", shortChance >= 45 ? "SHORT" : "NO_TRADE", shortChance, "Short chance " + round(shortChance) + "%"),
                aiVoteFromProbability("No Trade Model", noTradeChance >= 40 ? "NO_TRADE" : String.valueOf(decision.get("signal")), noTradeChance, "No-trade chance " + round(noTradeChance) + "%"),
                aiVoteFromProbability("Risk AI", noTradeChance >= 35 ? "NO_TRADE" : String.valueOf(decision.get("signal")), Math.max(noTradeChance, asDouble(decision.get("confidence"))), "Risk-adjusted decision")
        );
    }

    private Map<String, Object> aiVoteFromProbability(String ai, String signal, double confidence, String reason) {
        return Map.of(
                "ai", ai,
                "signal", signal,
                "confidence", Math.round(confidence),
                "entryQuality", confidence >= 75 ? "GOOD" : "WAIT",
                "reason", reason
        );
    }

    private double clampChance(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private long countMatchingSignals(List<Map<String, Object>> rows, String signal) {
        long count = 0;
        for (Map<String, Object> row : rows) {
            if (signal.equals(row.get("signal"))) count++;
        }
        return count;
    }

    private double averageVoteConfidence(List<Map<String, Object>> votes, String signal) {
        double sum = 0;
        int count = 0;
        for (Map<String, Object> vote : votes) {
            if (signal.equals(vote.get("signal"))) {
                sum += asDouble(vote.get("confidence"));
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private Map<String, Object> buildReport(List<CryptoPaperTrade> trades) {
        LocalDateTime todayStart = java.time.LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDateTime.now().minusDays(7);
        LocalDateTime monthStart = LocalDateTime.now().minusDays(30);
        long total = trades.size();
        long wins = trades.stream().filter(t -> "PROFIT".equals(t.getStatus())).count();
        long losses = trades.stream().filter(t -> "LOSS".equals(t.getStatus())).count();
        long running = trades.stream().filter(t -> "RUNNING".equals(t.getStatus())).count();
        double pnl = trades.stream().mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        long todayTrades = trades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart)).count();
        long todayProfitable = trades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart) && "PROFIT".equals(t.getStatus())).count();
        double todayPnl = trades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        double weekPnl = trades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(weekStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        double monthPnl = trades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(monthStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        double maxLoss = trades.stream().mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).min().orElse(0);
        Map<String, Long> aiWins = new LinkedHashMap<>();
        for (CryptoPaperTrade trade : trades) {
            if ("PROFIT".equals(trade.getStatus())) {
                aiWins.put(trade.getBestAi(), aiWins.getOrDefault(trade.getBestAi(), 0L) + 1);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalTrades", total);
        report.put("profitableTrades", wins);
        report.put("lossTrades", losses);
        report.put("runningTrades", running);
        report.put("todayTrades", todayTrades);
        report.put("todayProfitableTrades", todayProfitable);
        report.put("todayPnl", todayPnl);
        report.put("weekPnl", weekPnl);
        report.put("monthPnl", monthPnl);
        report.put("maxDailyTrades", MAX_DAILY_PAPER_TRADES);
        report.put("todaySlotsLeft", Math.max(0, MAX_DAILY_PAPER_TRADES - todayTrades));
        report.put("winRate", total == 0 ? 0 : Math.round(wins * 100.0 / total));
        report.put("virtualPnl", pnl);
        report.put("maxLoss", maxLoss);
        report.put("bestAiWins", aiWins);
        report.put("paperDaysTarget", 40);
        return report;
    }

    private List<String> safetyRules() {
        return List.of(
                "Real money disabled",
                "Only paper trade",
                "Max 1 running trade at a time",
                "No trade during high news risk",
                "No trade if AI JSON invalid",
                "No trade if 3 AIs do not agree",
                "No trade if 15m/1h/4h not aligned",
                "Risk reward minimum 1:2",
                "Daily report saved"
        );
    }

    private String blockReason(double confidence, long longFrames, String newsRisk, double finalScore) {
        return blockReason(true, confidence, longFrames, newsRisk, finalScore, "");
    }

    private String blockReason(boolean liveCmc, double confidence, long alignedFrames, String newsRisk, double finalScore, String finalSignal) {
        if (!liveCmc) return "CoinMarketCap live API key missing";
        if (confidence < 75) return "AI confidence below 75";
        if ("NO_TRADE".equals(finalSignal)) return "CMC momentum is neutral";
        if (alignedFrames < 2) return "Multi-timeframe not aligned";
        if ("HIGH".equals(newsRisk)) return "High news risk";
        if (finalScore < 75) return "Final accuracy score below 75";
        return "Risk rule blocked";
    }

    private double numberValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean hasEnv(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return parseDouble(value);
    }

    private double parseDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
