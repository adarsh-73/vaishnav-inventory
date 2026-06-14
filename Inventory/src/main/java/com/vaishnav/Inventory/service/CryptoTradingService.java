package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.repository.CryptoPaperTradeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CryptoTradingService {

    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT");
    private static final int MAX_DAILY_PAPER_TRADES = 5;

    @Autowired
    private CryptoPaperTradeRepository paperTradeRepository;

    @Autowired
    private CryptoExchangeService cryptoExchangeService;

    @Autowired
    private CryptoMarketDataService marketDataService;

    @Autowired
    private CryptoIndicatorService indicatorService;

    public Map<String, Object> getDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> signals = new ArrayList<>();

        for (String symbol : SYMBOLS) {
            signals.add(buildSignal(symbol));
        }

        List<CryptoPaperTrade> trades =
                paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(40));

        response.put("mode", "REAL_CANDLE_PAPER_ONLY");
        response.put("realMoneyEnabled", false);
        response.put("cashMode", "DISABLED");
        response.put("maxDailyTrades", MAX_DAILY_PAPER_TRADES);
        response.put("symbols", signals);
        response.put("report", buildReport(trades));
        response.put("openTrades", paperTradeRepository.findByStatus("RUNNING"));
        response.put("recentTrades", trades.stream()
                .sorted(Comparator.comparing(CryptoPaperTrade::getId).reversed())
                .limit(25)
                .toList());
        response.put("binance", cryptoExchangeService.testnetClientStatus());

        return response;
    }

    public List<CryptoPaperTrade> runPaperScan() {
        List<CryptoPaperTrade> created = new ArrayList<>();

        if (!paperTradeRepository.findByStatus("RUNNING").isEmpty()) {
            return created;
        }

        long todayTrades = paperTradeRepository
                .findByCreatedAtAfter(java.time.LocalDate.now().atStartOfDay())
                .size();

        if (todayTrades >= MAX_DAILY_PAPER_TRADES) {
            return created;
        }

        for (String symbol : SYMBOLS) {
            Map<String, Object> signal = buildSignal(symbol);

            if (!Boolean.TRUE.equals(signal.get("allowed"))) {
                continue;
            }

            CryptoPaperTrade trade = new CryptoPaperTrade();
            trade.setSymbol(symbol);
            trade.setSide(String.valueOf(signal.get("finalSignal")));
            trade.setStatus("RUNNING");
            trade.setTimeframe("15m/1h/4h");
            trade.setEntryPrice(toDouble(signal.get("entry")));
            trade.setStopLoss(toDouble(signal.get("stopLoss")));
            trade.setTakeProfit(toDouble(signal.get("takeProfit")));
            trade.setTrailingStop(toDouble(signal.get("trailingStop")));
            trade.setQuantity(toDouble(signal.get("positionSize")));
            trade.setConfidence(toDouble(signal.get("confidence")));
            trade.setFinalScore(toDouble(signal.get("finalScore")));
            trade.setRiskReward(toDouble(signal.get("riskReward")));
            trade.setBestAi("Indicator Engine");
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
            double currentPrice = marketDataService.getLivePrice(trade.getSymbol());
            double direction = "LONG".equals(trade.getSide()) ? 1 : -1;
            double pnl = (currentPrice - trade.getEntryPrice()) * trade.getQuantity() * direction;

            trade.setExitPrice(currentPrice);
            trade.setPnl(pnl);

            if ("LONG".equals(trade.getSide())) {
                if (currentPrice <= trade.getStopLoss()) {
                    trade.setStatus("LOSS");
                    trade.setCloseReason("Live price hit stop loss");
                } else if (currentPrice >= trade.getTakeProfit()) {
                    trade.setStatus("PROFIT");
                    trade.setCloseReason("Live price hit take profit");
                } else {
                    trade.setStatus(pnl >= 0 ? "PROFIT" : "LOSS");
                    trade.setCloseReason("Manual close at live price");
                }
            } else {
                if (currentPrice >= trade.getStopLoss()) {
                    trade.setStatus("LOSS");
                    trade.setCloseReason("Live price hit stop loss");
                } else if (currentPrice <= trade.getTakeProfit()) {
                    trade.setStatus("PROFIT");
                    trade.setCloseReason("Live price hit take profit");
                } else {
                    trade.setStatus(pnl >= 0 ? "PROFIT" : "LOSS");
                    trade.setCloseReason("Manual close at live price");
                }
            }

            trade.setClosedAt(LocalDateTime.now());
            paperTradeRepository.save(trade);
        }

        return runningTrades;
    }

    private Map<String, Object> buildSignal(String symbol) {
        try {
            double livePrice = marketDataService.getLivePrice(symbol);

            Map<String, Object> a15 =
                    indicatorService.analyze(marketDataService.getCandles(symbol, "15m", 200));
            Map<String, Object> a1h =
                    indicatorService.analyze(marketDataService.getCandles(symbol, "1h", 200));
            Map<String, Object> a4h =
                    indicatorService.analyze(marketDataService.getCandles(symbol, "4h", 200));
            CryptoMarketDataService.FuturesStats futures = marketDataService.getFuturesStats(symbol);

            int longCount = 0;
            int shortCount = 0;

            if ("LONG".equals(a15.get("signal"))) longCount++; else shortCount++;
            if ("LONG".equals(a1h.get("signal"))) longCount++; else shortCount++;
            if ("LONG".equals(a4h.get("signal"))) longCount++; else shortCount++;

            String finalSignal = longCount >= 2 ? "LONG" : "SHORT";

            int avgScore = (int) Math.round(
                    (toDouble(a15.get("score")) + toDouble(a1h.get("score")) + toDouble(a4h.get("score"))) / 3.0
            );

            double atr = Math.max(toDouble(a15.get("atr14")), getAtr(symbol) * 0.25);
            double stopDistance = atr * 0.82;
            double riskReward = 2.0;

            double stopLoss = "LONG".equals(finalSignal)
                    ? livePrice - stopDistance
                    : livePrice + stopDistance;

            double takeProfit = "LONG".equals(finalSignal)
                    ? livePrice + stopDistance * riskReward
                    : livePrice - stopDistance * riskReward;

            double trailingStop = "LONG".equals(finalSignal)
                    ? livePrice + atr * 0.55
                    : livePrice - atr * 0.55;

            boolean aligned = longCount == 3 || shortCount == 3;
            boolean fundingRisk = Math.abs(futures.fundingRate) >= 0.001;
            boolean futuresAvailable = futures.openInterest > 0 || futures.markPrice > 0;
            boolean allowed = aligned && avgScore >= 65 && futuresAvailable && !fundingRisk;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("symbol", symbol);
            map.put("finalSignal", allowed ? finalSignal : "NO_TRADE");
            map.put("rawSignal", finalSignal);
            map.put("allowed", allowed);
            map.put("confidence", avgScore);
            map.put("finalScore", avgScore);
            map.put("entry", livePrice);
            map.put("currentPrice", livePrice);
            map.put("priceSource", "BINANCE_REAL");
            map.put("marketWarning", "");
            map.put("stopLoss", stopLoss);
            map.put("takeProfit", takeProfit);
            map.put("trailingStop", trailingStop);
            map.put("riskReward", riskReward);
            map.put("positionSize", 1000 / livePrice);
            map.put("futuresData", Map.of(
                    "openInterest", futures.openInterest,
                    "fundingRate", futures.fundingRate,
                    "markPrice", futures.markPrice,
                    "indexPrice", futures.indexPrice,
                    "priceChangePercent24h", futures.priceChangePercent24h,
                    "quoteVolume24h", futures.quoteVolume24h,
                    "source", "BINANCE_FUTURES_REAL"
            ));

            map.put("timeframes", List.of(
                    timeframeRow("15m", a15),
                    timeframeRow("1h", a1h),
                    timeframeRow("4h", a4h)
            ));

            map.put("aiVotes", List.of(
                    Map.of("ai", "Indicator Engine", "signal", finalSignal, "confidence", avgScore, "reason", "Real Binance candles"),
                    Map.of("ai", "Risk AI", "signal", allowed ? finalSignal : "NO_TRADE", "confidence", avgScore, "reason", "Risk filter checked")
            ));

            map.put("aiConsensus", "LONG=" + longCount + "/3 SHORT=" + shortCount + "/3");
            map.put("bestAi", "Indicator Engine");
            map.put("indicatorSummary", Map.of(
                    "total", 36,
                    "bullish", Math.round(
                            toDouble(a15.get("bullish")) + toDouble(a1h.get("bullish")) + toDouble(a4h.get("bullish"))
                    ),
                    "bearish", Math.round(
                            toDouble(a15.get("bearish")) + toDouble(a1h.get("bearish")) + toDouble(a4h.get("bearish"))
                    )
            ));
            map.put("technicalSummary", "Real Binance candles checked: SMA20/50/200, EMA20/50/200, RSI14, MACD, Bollinger Bands, ATR14, VWAP on 15m/1h/4h. Futures checked: open interest, funding, mark price, 24h futures volume.");
            map.put("newsRisk", fundingRisk ? "FUNDING_RISK" : "NORMAL");
            map.put("blockReason", allowed ? "" : !futuresAvailable ? "Binance futures data unavailable" : fundingRisk ? "Funding rate too risky" : "Timeframes not aligned or score below 65");

            return map;

        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("symbol", symbol);
            error.put("finalSignal", "NO_TRADE");
            error.put("rawSignal", "NO_TRADE");
            error.put("allowed", false);
            error.put("confidence", 0);
            error.put("finalScore", 0);
            error.put("entry", 0);
            error.put("currentPrice", 0);
            error.put("priceSource", "ERROR");
            error.put("marketWarning", e.getMessage());
            error.put("stopLoss", 0);
            error.put("takeProfit", 0);
            error.put("trailingStop", 0);
            error.put("riskReward", 0);
            error.put("positionSize", 0);
            error.put("timeframes", List.of());
            error.put("aiVotes", List.of());
            error.put("aiConsensus", "NO_DATA");
            error.put("indicatorSummary", Map.of("total", 0, "bullish", 0, "bearish", 0));
            error.put("technicalSummary", "Binance candle fetch failed");
            error.put("newsRisk", "UNKNOWN");
            error.put("blockReason", e.getMessage());
            return error;
        }
    }

    private Map<String, Object> timeframeRow(String name, Map<String, Object> analysis) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timeframe", name);
        row.put("signal", analysis.get("signal"));
        row.put("score", analysis.get("score"));
        row.put("ma50", analysis.get("sma50"));
        row.put("ma100", analysis.get("ema50"));
        row.put("ma200", analysis.get("sma200"));
        row.put("rsi", analysis.get("rsi14"));
        row.put("ema20", analysis.get("ema20"));
        row.put("ema50", analysis.get("ema50"));
        row.put("ema200", analysis.get("ema200"));
        row.put("macd", analysis.get("macd"));
        row.put("macdSignal", analysis.get("macdSignal"));
        row.put("bollingerPosition", analysis.get("bollingerPosition"));
        row.put("atr14", analysis.get("atr14"));
        row.put("vwap", analysis.get("vwap"));
        return row;
    }

    private double getAtr(String symbol) {
        return switch (symbol) {
            case "BTCUSDT" -> 1850.0;
            case "ETHUSDT" -> 112.0;
            case "SOLUSDT" -> 7.8;
            case "BNBUSDT" -> 18.0;
            default -> 10.0;
        };
    }

    private Map<String, Object> buildReport(List<CryptoPaperTrade> trades) {
        LocalDateTime todayStart = java.time.LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDateTime.now().minusDays(7);
        LocalDateTime monthStart = LocalDateTime.now().minusDays(30);

        long total = trades.size();
        long wins = trades.stream().filter(t -> "PROFIT".equals(t.getStatus())).count();
        long losses = trades.stream().filter(t -> "LOSS".equals(t.getStatus())).count();
        long running = trades.stream().filter(t -> "RUNNING".equals(t.getStatus())).count();

        long todayTrades = trades.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart))
                .count();

        double todayPnl = trades.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl())
                .sum();

        double weekPnl = trades.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(weekStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl())
                .sum();

        double monthPnl = trades.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(monthStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl())
                .sum();

        double pnl = trades.stream()
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl())
                .sum();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalTrades", total);
        report.put("profitableTrades", wins);
        report.put("lossTrades", losses);
        report.put("runningTrades", running);
        report.put("todayTrades", todayTrades);
        report.put("todayProfitableTrades", 0);
        report.put("todayPnl", todayPnl);
        report.put("weekPnl", weekPnl);
        report.put("monthPnl", monthPnl);
        report.put("maxDailyTrades", MAX_DAILY_PAPER_TRADES);
        report.put("todaySlotsLeft", Math.max(0, MAX_DAILY_PAPER_TRADES - todayTrades));
        report.put("winRate", total == 0 ? 0 : Math.round(wins * 100.0 / total));
        report.put("virtualPnl", pnl);
        report.put("maxLoss", 0);

        return report;
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
