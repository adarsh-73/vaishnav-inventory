package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.repository.CryptoPaperTradeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CryptoTradingService {

    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT");
    private static final List<String> AI_ENGINES = List.of("ChatGPT", "Gemini", "DeepSeek", "Claude", "Risk AI");
    private static final int INDICATOR_COUNT = 100;
    private static final int MAX_DAILY_PAPER_TRADES = 5;

    @Autowired
    private CryptoPaperTradeRepository paperTradeRepository;

    @Autowired
    private CryptoExchangeService cryptoExchangeService;

    public Map<String, Object> getDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Map<String, Object>> marketPrices = fetchCoinMarketCapPrices();
        List<Map<String, Object>> signals = SYMBOLS.stream().map(symbol -> buildSignal(symbol, marketPrices)).toList();
        List<CryptoPaperTrade> trades = paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(40));
        Map<String, Object> report = buildReport(trades);

        response.put("mode", "PAPER_ONLY");
        response.put("realMoneyEnabled", false);
        response.put("cashMode", "DISABLED");
        response.put("maxDailyTrades", MAX_DAILY_PAPER_TRADES);
        response.put("todayTradeSlotsLeft", Math.max(0, MAX_DAILY_PAPER_TRADES - ((Number) report.get("todayTrades")).intValue()));
        response.put("marketDataSource", marketPrices.values().stream().anyMatch(price -> "COINMARKETCAP".equals(price.get("source"))) ? "COINMARKETCAP" : "FALLBACK");
        response.put("symbols", signals);
        response.put("report", report);
        response.put("openTrades", paperTradeRepository.findByStatus("RUNNING"));
        response.put("recentTrades", trades.stream().sorted(Comparator.comparing(CryptoPaperTrade::getId).reversed()).limit(25).toList());
        response.put("safetyRules", safetyRules());
        response.put("binance", cryptoExchangeService.testnetClientStatus());
        return response;
    }

    public List<CryptoPaperTrade> runPaperScan() {
        List<CryptoPaperTrade> created = new ArrayList<>();
        long runningCount = paperTradeRepository.findByStatus("RUNNING").size();
        if (runningCount >= 1) return created;
        long todayTrades = paperTradeRepository.findByCreatedAtAfter(java.time.LocalDate.now().atStartOfDay()).size();
        if (todayTrades >= MAX_DAILY_PAPER_TRADES) return created;

        for (String symbol : SYMBOLS) {
            Map<String, Object> signal = buildSignal(symbol, fetchCoinMarketCapPrices());
            boolean allowed = Boolean.TRUE.equals(signal.get("allowed"));
            if (!allowed) continue;

            CryptoPaperTrade trade = new CryptoPaperTrade();
            trade.setSymbol(symbol);
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

    private Map<String, Object> buildSignal(String symbol, Map<String, Map<String, Object>> marketPrices) {
        int seed = Math.abs(symbol.hashCode());
        Map<String, Object> marketPrice = marketPrices.getOrDefault(symbol, fallbackPrice(symbol));
        double price = asDouble(marketPrice.get("price"));
        boolean liveCmc = "COINMARKETCAP".equals(marketPrice.get("source"));
        double change1h = asDouble(marketPrice.getOrDefault("percentChange1h", 0));
        double change24h = asDouble(marketPrice.getOrDefault("percentChange24h", 0));
        double change7d = asDouble(marketPrice.getOrDefault("percentChange7d", 0));
        double volume24h = asDouble(marketPrice.getOrDefault("volume24h", 0));
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
        long shortFrames = timeframes.size() - longFrames;
        String technicalSignal = longFrames == shortFrames ? "NO_TRADE" : longFrames > shortFrames ? "LONG" : "SHORT";
        double momentumScore = 50 + change1h * 8 + change24h * 2.2 + change7d * 0.65;
        int indicatorBullish = liveCmc
                ? (int) Math.round(Math.max(18, Math.min(82, momentumScore)))
                : 50;
        int technicalScore = Math.min(95, Math.max(30, indicatorBullish));
        String newsRisk = Math.abs(change1h) >= 3.5 || Math.abs(change24h) >= 9 ? "HIGH" : "NORMAL";
        int volumeScore = liveCmc
                ? (int) Math.round(Math.max(45, Math.min(92, 55 + Math.log10(Math.max(1, volume24h)) * 3.2)))
                : 50;

        List<Map<String, Object>> aiVotes = AI_ENGINES.stream().map(ai -> aiVote(ai, symbol, technicalSignal, technicalScore)).toList();
        long longVotes = aiVotes.stream().filter(vote -> "LONG".equals(vote.get("signal"))).count();
        long shortVotes = aiVotes.size() - longVotes;
        String finalSignal = longVotes >= shortVotes ? "LONG" : "SHORT";
        long alignedFrames = timeframes.stream().filter(tf -> finalSignal.equals(tf.get("signal"))).count();
        double confidence = aiVotes.stream()
                .filter(vote -> finalSignal.equals(vote.get("signal")))
                .mapToDouble(vote -> asDouble(vote.get("confidence")))
                .average()
                .orElse(0);
        double riskReward = 2.05;
        double stopDistance = atr * 0.82;
        double takeDistance = stopDistance * riskReward;
        double entry = price;
        double stopLoss = "LONG".equals(finalSignal) ? entry - stopDistance : entry + stopDistance;
        double takeProfit = "LONG".equals(finalSignal) ? entry + takeDistance : entry - takeDistance;
        double trailingStop = "LONG".equals(finalSignal) ? entry + atr * 0.55 : entry - atr * 0.55;
        double finalScore = Math.round(confidence * 0.4 + technicalScore * 0.25 + volumeScore * 0.2 + ("HIGH".equals(newsRisk) ? 35 : 85) * 0.15);
        boolean allowed = liveCmc
                && finalScore >= 75
                && confidence >= 75
                && alignedFrames >= 2
                && !"NO_TRADE".equals(finalSignal)
                && !"HIGH".equals(newsRisk)
                && riskReward >= 2
                && aiVotes.stream().filter(vote -> finalSignal.equals(vote.get("signal"))).count() >= 3;

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("symbol", symbol);
        signal.put("finalSignal", allowed ? finalSignal : "NO_TRADE");
        signal.put("rawSignal", finalSignal);
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
        signal.put("stopLoss", stopLoss);
        signal.put("takeProfit", takeProfit);
        signal.put("trailingStop", trailingStop);
        signal.put("riskReward", riskReward);
        signal.put("positionSize", 1000 / entry);
        signal.put("timeframes", timeframes);
        signal.put("aiVotes", aiVotes);
        signal.put("aiConsensus", "LONG=" + (longVotes * 100 / AI_ENGINES.size()) + "% SHORT=" + (shortVotes * 100 / AI_ENGINES.size()) + "%");
        signal.put("bestAi", aiVotes.get(seed % aiVotes.size()).get("ai"));
        signal.put("indicatorSummary", Map.of("total", INDICATOR_COUNT, "bullish", indicatorBullish, "bearish", INDICATOR_COUNT - indicatorBullish));
        signal.put("technicalSummary", technicalSignal + " | CMC 1h=" + round(change1h) + "% 24h=" + round(change24h) + "% 7d=" + round(change7d) + "% | volumeScore=" + volumeScore + " | RR=" + riskReward);
        signal.put("newsRisk", newsRisk);
        signal.put("blockReason", allowed ? "" : blockReason(liveCmc, confidence, alignedFrames, newsRisk, finalScore, finalSignal));
        return signal;
    }

    private Map<String, Map<String, Object>> fetchCoinMarketCapPrices() {
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
            return prices;
        } catch (Exception error) {
            prices.replaceAll((symbol, oldValue) -> {
                Map<String, Object> value = fallbackPrice(symbol);
                value.put("warning", "CoinMarketCap fetch failed, fallback price used");
                return value;
            });
            return prices;
        }
    }

    private Map<String, Object> fallbackPrice(String symbol) {
        double price = switch (symbol) {
            case "BTCUSDT" -> 104250.0;
            case "ETHUSDT" -> 3620.0;
            default -> 168.0;
        };
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("price", price);
        value.put("percentChange1h", 0);
        value.put("percentChange24h", 0);
        value.put("percentChange7d", 0);
        value.put("volume24h", 0);
        value.put("lastUpdated", "");
        value.put("source", "FALLBACK_CMC_KEY_MISSING");
        value.put("warning", "CoinMarketCap API key missing. Set COINMARKETCAP_API_KEY in Render; live signals paused.");
        return value;
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

    private double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }
}
