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

    @Autowired
    private CryptoLiquidationStreamService liquidationStreamService;

    @Autowired
    private CryptoAiConsensusService aiConsensusService;

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
        response.put("fearGreed", marketDataService.getFearGreed());
        response.put("dataPolicy", "No simulated market values. Missing providers return NOT_CONFIGURED or UNAVAILABLE.");
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
            Map<String, Object> liquidations = liquidationStreamService.snapshot(symbol);

            int longCount = 0;
            int shortCount = 0;

            if ("LONG".equals(a15.get("signal"))) longCount++; else shortCount++;
            if ("LONG".equals(a1h.get("signal"))) longCount++; else shortCount++;
            if ("LONG".equals(a4h.get("signal"))) longCount++; else shortCount++;

            String finalSignal = longCount >= 2 ? "LONG" : "SHORT";

            int avgScore = (int) Math.round(
                    (toDouble(a15.get("score")) + toDouble(a1h.get("score")) + toDouble(a4h.get("score"))) / 3.0
            );

            double atr = toDouble(a15.get("atr14"));
            if (atr <= 0) atr = livePrice * 0.01;
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
            int derivativesScore = derivativesScore(finalSignal, futures);
            boolean derivativesAligned = derivativesScore >= 55;

            Map<String, Object> aiSnapshot = new LinkedHashMap<>();
            aiSnapshot.put("technicalSignal", finalSignal);
            aiSnapshot.put("technicalScore", avgScore);
            aiSnapshot.put("timeframeSignals", List.of(a15.get("signal"), a1h.get("signal"), a4h.get("signal")));
            aiSnapshot.put("rsi15m", a15.get("rsi14"));
            aiSnapshot.put("macd15m", a15.get("macdHistogram"));
            aiSnapshot.put("adx15m", a15.get("adx14"));
            aiSnapshot.put("atr15m", a15.get("atr14"));
            aiSnapshot.put("openInterestChangePercent", futures.openInterestChangePercent);
            aiSnapshot.put("fundingRate", futures.fundingRate);
            aiSnapshot.put("longShortRatio", futures.longShortRatio);
            aiSnapshot.put("takerBuySellRatio", futures.takerBuySellRatio);
            aiSnapshot.put("largeTradeBias", futures.largeTradeBias);
            aiSnapshot.put("liquidations", liquidations);
            Map<String, Object> aiConsensus = aiConsensusService.analyze(symbol, aiSnapshot);
            int aiProviderCount = (int) toDouble(aiConsensus.get("configuredProviders"));
            String aiSignal = String.valueOf(aiConsensus.get("signal"));
            boolean aiAligned = aiProviderCount == 0 || finalSignal.equals(aiSignal);
            int aiConfidence = (int) toDouble(aiConsensus.get("confidence"));

            int finalScore = aiProviderCount > 0
                    ? (int) Math.round(avgScore * 0.65 + derivativesScore * 0.20 + aiConfidence * 0.15)
                    : (int) Math.round(avgScore * 0.75 + derivativesScore * 0.25);
            boolean allowed = aligned && finalScore >= 65 && futuresAvailable && derivativesAligned && aiAligned && !fundingRisk;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("symbol", symbol);
            map.put("finalSignal", allowed ? finalSignal : "NO_TRADE");
            map.put("rawSignal", finalSignal);
            map.put("allowed", allowed);
            map.put("confidence", finalScore);
            map.put("finalScore", finalScore);
            map.put("entry", livePrice);
            map.put("currentPrice", livePrice);
            map.put("priceSource", "BINANCE_REAL");
            map.put("marketWarning", "");
            map.put("stopLoss", stopLoss);
            map.put("takeProfit", takeProfit);
            map.put("trailingStop", trailingStop);
            map.put("riskReward", riskReward);
            map.put("positionSize", 1000 / livePrice);
            Map<String, Object> futuresData = new LinkedHashMap<>();
            futuresData.put("openInterest", futures.openInterest);
            futuresData.put("openInterestValue", futures.openInterestValue);
            futuresData.put("openInterestChangePercent", futures.openInterestChangePercent);
            futuresData.put("fundingRate", futures.fundingRate);
            futuresData.put("nextFundingTime", futures.nextFundingTime);
            futuresData.put("markPrice", futures.markPrice);
            futuresData.put("indexPrice", futures.indexPrice);
            futuresData.put("priceChangePercent24h", futures.priceChangePercent24h);
            futuresData.put("quoteVolume24h", futures.quoteVolume24h);
            futuresData.put("longShortRatio", futures.longShortRatio);
            futuresData.put("longAccount", futures.longAccount);
            futuresData.put("shortAccount", futures.shortAccount);
            futuresData.put("takerBuySellRatio", futures.takerBuySellRatio);
            futuresData.put("largeBuyNotional", futures.largeBuyNotional);
            futuresData.put("largeSellNotional", futures.largeSellNotional);
            futuresData.put("largeTradeCount", futures.largeTradeCount);
            futuresData.put("largeTradeThreshold", futures.largeTradeThreshold);
            futuresData.put("largeTradeBias", futures.largeTradeBias);
            futuresData.put("derivativesScore", derivativesScore);
            futuresData.put("fetchedAt", futures.fetchedAt);
            futuresData.put("source", "BINANCE_FUTURES_REAL");
            map.put("futuresData", futuresData);
            map.put("liquidationData", liquidations);
            map.put("whaleData", Map.of(
                    "type", "EXCHANGE_LARGE_TRADE_PROXY",
                    "status", futures.largeTradeCount > 0 ? "LIVE" : "NO_LARGE_TRADES_IN_SAMPLE",
                    "buyNotional", futures.largeBuyNotional,
                    "sellNotional", futures.largeSellNotional,
                    "bias", futures.largeTradeBias,
                    "source", "BINANCE_AGG_TRADES_REAL",
                    "onChain", false
            ));

            map.put("timeframes", List.of(
                    timeframeRow("15m", a15),
                    timeframeRow("1h", a1h),
                    timeframeRow("4h", a4h)
            ));

            map.put("aiVotes", aiConsensus.get("votes"));
            map.put("aiConsensus", aiConsensus);
            map.put("bestAi", aiProviderCount > 0 ? "Provider Consensus" : "Indicator Engine (LLM keys not configured)");
            map.put("indicatorSummary", Map.of(
                    "total", Math.round(toDouble(a15.get("indicatorCount")) + toDouble(a1h.get("indicatorCount")) + toDouble(a4h.get("indicatorCount"))),
                    "perTimeframe", a15.get("indicatorCount"),
                    "bullish", Math.round(
                            toDouble(a15.get("bullish")) + toDouble(a1h.get("bullish")) + toDouble(a4h.get("bullish"))
                    ),
                    "bearish", Math.round(
                            toDouble(a15.get("bearish")) + toDouble(a1h.get("bearish")) + toDouble(a4h.get("bearish"))
                    )
            ));
            map.put("technicalSummary", "60+ real values per timeframe calculated from Binance OHLCV: SMA/EMA families, RSI, MACD, Bollinger, ATR, VWAP, ROC, momentum, Donchian, Williams %R, CCI, MFI, OBV, CMF, stochastic, DMI/ADX and volume flow. Futures: OI history, funding, long/short, taker flow and large trades.");
            map.put("newsRisk", fundingRisk ? "FUNDING_RISK" : "NORMAL");
            map.put("blockReason", allowed ? "" : !futuresAvailable ? "Binance futures data unavailable" : fundingRisk ? "Funding rate too risky" : !aiAligned ? "Configured AI providers disagree with technical direction" : !derivativesAligned ? "Derivatives flow does not confirm direction" : "Timeframes not aligned or final score below 65");

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
        row.put("adx14", analysis.get("adx14"));
        row.put("indicatorCount", analysis.get("indicatorCount"));
        row.put("indicators", analysis.get("indicators"));
        return row;
    }

    private int derivativesScore(String signal, CryptoMarketDataService.FuturesStats futures) {
        int score = 50;
        boolean isLong = "LONG".equals(signal);
        if (futures.openInterestChangePercent > 0) score += 8;
        if ((futures.takerBuySellRatio >= 1 && isLong) || (futures.takerBuySellRatio > 0 && futures.takerBuySellRatio < 1 && !isLong)) score += 12; else score -= 8;
        if (("BUY".equals(futures.largeTradeBias) && isLong) || ("SELL".equals(futures.largeTradeBias) && !isLong)) score += 12; else if (!"NEUTRAL".equals(futures.largeTradeBias)) score -= 8;
        if ((futures.priceChangePercent24h >= 0 && isLong) || (futures.priceChangePercent24h < 0 && !isLong)) score += 8; else score -= 5;
        if (Math.abs(futures.fundingRate) >= 0.001) score -= 25;
        return Math.max(0, Math.min(100, score));
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
