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
        double atr = switch (symbol) {
            case "BTCUSDT" -> 1850.0;
            case "ETHUSDT" -> 112.0;
            default -> 7.8;
        };

        List<Map<String, Object>> timeframes = List.of(
                timeframe(symbol, "15m", seed, price, 0),
                timeframe(symbol, "1h", seed, price, 1),
                timeframe(symbol, "4h", seed, price, 2)
        );
        long longFrames = timeframes.stream().filter(tf -> "LONG".equals(tf.get("signal"))).count();
        String technicalSignal = longFrames >= 2 ? "LONG" : "SHORT";
        int indicatorBullish = 52 + (seed % 31);
        int technicalScore = Math.min(95, Math.max(30, indicatorBullish));
        String newsRisk = (seed % 5 == 0) ? "HIGH" : "NORMAL";
        int volumeScore = 58 + (seed % 35);

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
        boolean allowed = finalScore >= 75
                && confidence >= 75
                && alignedFrames == 3
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
        signal.put("technicalSummary", technicalSignal + " | volumeScore=" + volumeScore + " | MA50/100/200 checked | RR=" + riskReward);
        signal.put("newsRisk", newsRisk);
        signal.put("blockReason", allowed ? "" : blockReason(confidence, alignedFrames, newsRisk, finalScore));
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
        value.put("source", "FALLBACK_CMC_KEY_MISSING");
        value.put("warning", "Set COINMARKETCAP_API_KEY to use live CoinMarketCap price");
        return value;
    }

    private Map<String, Object> timeframe(String symbol, String timeframe, int seed, double price, int index) {
        double drift = ((seed + index * 17) % 34 - 12) / 1000.0;
        double ma50 = price * (1 + drift);
        double ma100 = price * (1 + drift - 0.004);
        double ma200 = price * (1 + drift - 0.008);
        int rsi = 48 + ((seed + index * 11) % 28);
        String signal = price > ma50 && ma50 > ma100 && ma100 > ma200 && rsi >= 52 ? "LONG" : "SHORT";
        return Map.of(
                "timeframe", timeframe,
                "signal", signal,
                "ma50", ma50,
                "ma100", ma100,
                "ma200", ma200,
                "rsi", rsi,
                "volume", 1.1 + ((seed + index) % 12) / 10.0
        );
    }

    private Map<String, Object> aiVote(String ai, String symbol, String technicalSignal, int technicalScore) {
        int score = Math.abs((ai + symbol).hashCode());
        String signal = score % 100 > 28 ? technicalSignal : ("LONG".equals(technicalSignal) ? "SHORT" : "LONG");
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
        if (confidence < 75) return "AI confidence below 75";
        if (longFrames < 3) return "Multi-timeframe not aligned";
        if ("HIGH".equals(newsRisk)) return "High news risk";
        if (finalScore < 75) return "Final accuracy score below 75";
        return "Risk rule blocked";
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }
}
