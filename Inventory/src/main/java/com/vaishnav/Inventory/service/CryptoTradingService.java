package com.vaishnav.Inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.entity.CryptoDecisionAudit;
import com.vaishnav.Inventory.repository.CryptoPaperTradeRepository;
import com.vaishnav.Inventory.repository.CryptoDecisionAuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CryptoTradingService {

    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT");
    private static final int MAX_DAILY_PAPER_TRADES = 5;
    private static final double PAPER_ACCOUNT_EQUITY = 100_000;
    private static final double MAX_RISK_PER_TRADE_PERCENT = 1.0;
    private static final double MAX_DAILY_LOSS_PERCENT = 3.0;
    private static final double MAX_WEEKLY_LOSS_PERCENT = 8.0;
    private static final double MAX_NOTIONAL_LEVERAGE = 3.0;
    private static final int MIN_LIVE_AI_PROVIDERS = 2;
    private static final String ENGINE_VERSION = "AEGIS_V3_EXPLAINABLE_2AI";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CryptoPaperTradeRepository paperTradeRepository;

    @Autowired
    private CryptoDecisionAuditRepository decisionAuditRepository;

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

    @Autowired
    private CryptoMacroNewsService macroNewsService;

    @Autowired
    private CryptoWhaleStreamService whaleStreamService;

    @Autowired
    private CryptoOnChainService onChainService;


    public Map<String, Object> getDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> signals = SYMBOLS.stream()
                .map(this::buildSignal)
                .toList();

        removeLegacyRunningTrades();
        List<CryptoPaperTrade> trades = paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(40)).stream()
                .filter(trade -> ENGINE_VERSION.equals(trade.getEngineVersion()))
                .toList();

        response.put("mode", "REAL_CANDLE_PAPER_ONLY");
        response.put("realMoneyEnabled", false);
        response.put("cashMode", "DISABLED");
        response.put("maxDailyTrades", MAX_DAILY_PAPER_TRADES);
        response.put("fearGreed", marketDataService.getFearGreed());
        response.put("macroNews", macroNewsService.getContext());
        response.put("aiProviderStatus", aiConsensusService.providerStatus());
        response.put("riskPolicy", Map.of(
                "paperEquity", PAPER_ACCOUNT_EQUITY,
                "maxRiskPerTradePercent", MAX_RISK_PER_TRADE_PERCENT,
                "maxDailyLossPercent", MAX_DAILY_LOSS_PERCENT,
                "maxWeeklyLossPercent", MAX_WEEKLY_LOSS_PERCENT,
                "maxNotionalLeverage", MAX_NOTIONAL_LEVERAGE,
                "authority", "DETERMINISTIC_RISK_ENGINE"
        ));
        response.put("dataPolicy", "No simulated market values. Missing providers return NOT_CONFIGURED or UNAVAILABLE.");
        response.put("symbols", signals);
        response.put("report", buildReport(trades));
        response.put("openTrades", trades.stream()
                .filter(trade -> "RUNNING".equals(trade.getStatus()))
                .map(this::tradeSummary)
                .toList());
        response.put("recentTrades", trades.stream()
                .sorted(Comparator.comparing(CryptoPaperTrade::getId).reversed())
                .limit(25)
                .map(this::tradeSummary)
                .toList());
        response.put("learningReport", buildLearningReport(trades));
        response.put("recentDecisions", decisionAuditRepository.findTop50ByOrderByIdDesc().stream()
                .map(this::decisionSummary)
                .toList());
        response.put("binance", cryptoExchangeService.testnetClientStatus());

        return response;
    }

    private Map<String, Object> decisionSummary(CryptoDecisionAudit audit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", audit.getId());
        summary.put("symbol", audit.getSymbol());
        summary.put("candidateSignal", audit.getCandidateSignal());
        summary.put("finalSignal", audit.getFinalSignal());
        summary.put("allowed", audit.getAllowed());
        summary.put("finalScore", audit.getFinalScore());
        summary.put("dataReadiness", audit.getDataReadiness());
        summary.put("blockReason", audit.getBlockReason());
        summary.put("engineVersion", audit.getEngineVersion());
        summary.put("openedTradeId", audit.getOpenedTradeId());
        summary.put("createdAt", audit.getCreatedAt());
        return summary;
    }

    private Map<String, Object> tradeSummary(CryptoPaperTrade trade) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", trade.getId());
        summary.put("symbol", trade.getSymbol());
        summary.put("side", trade.getSide());
        summary.put("status", trade.getStatus());
        summary.put("timeframe", trade.getTimeframe());
        summary.put("entryPrice", trade.getEntryPrice());
        summary.put("stopLoss", trade.getStopLoss());
        summary.put("takeProfit", trade.getTakeProfit());
        summary.put("exitPrice", trade.getExitPrice());
        summary.put("quantity", trade.getQuantity());
        summary.put("pnl", trade.getPnl());
        summary.put("confidence", trade.getConfidence());
        summary.put("finalScore", trade.getFinalScore());
        summary.put("riskReward", trade.getRiskReward());
        summary.put("accountRiskPercent", trade.getAccountRiskPercent());
        summary.put("bestAi", trade.getBestAi());
        summary.put("macroBias", trade.getMacroBias());
        summary.put("whaleBias", trade.getWhaleBias());
        summary.put("onChainBias", trade.getOnChainBias());
        summary.put("dataReadiness", trade.getDataReadiness());
        summary.put("closeReason", trade.getCloseReason());
        summary.put("openedAt", trade.getOpenedAt());
        summary.put("closedAt", trade.getClosedAt());
        summary.put("createdAt", trade.getCreatedAt());
        summary.put("engineVersion", trade.getEngineVersion());
        summary.put("decisionAuditId", trade.getDecisionAuditId());
        return summary;
    }

    public List<CryptoPaperTrade> runPaperScan() {
        List<CryptoPaperTrade> created = new ArrayList<>();

        removeLegacyRunningTrades();
        if (paperTradeRepository.findByStatus("RUNNING").stream()
                .anyMatch(trade -> ENGINE_VERSION.equals(trade.getEngineVersion()))) {
            return created;
        }

        long todayTrades = paperTradeRepository
                .findByCreatedAtAfter(java.time.LocalDate.now().atStartOfDay())
                .size();

        if (todayTrades >= MAX_DAILY_PAPER_TRADES || !riskCircuitAllowsNewTrade()) {
            return created;
        }

        for (String symbol : SYMBOLS) {
            Map<String, Object> signal = buildSignal(symbol);
            CryptoDecisionAudit audit = saveDecisionAudit(signal);

            if (!Boolean.TRUE.equals(signal.get("allowed"))) {
                continue;
            }

            CryptoPaperTrade trade = new CryptoPaperTrade();
            trade.setSymbol(symbol);
            trade.setSide(String.valueOf(signal.get("finalSignal")));
            trade.setStatus("RUNNING");
            trade.setTimeframe("15m/1h/4h");
            trade.setCandidateSignal(String.valueOf(signal.get("candidateSignal")));
            trade.setEngineVersion(ENGINE_VERSION);
            trade.setDecisionAuditId(audit.getId());
            trade.setEntryPrice(toDouble(signal.get("entry")));
            trade.setStopLoss(toDouble(signal.get("stopLoss")));
            trade.setTakeProfit(toDouble(signal.get("takeProfit")));
            trade.setTrailingStop(toDouble(signal.get("trailingStop")));
            trade.setQuantity(toDouble(signal.get("positionSize")));
            trade.setConfidence(toDouble(signal.get("confidence")));
            trade.setFinalScore(toDouble(signal.get("finalScore")));
            trade.setRiskReward(toDouble(signal.get("riskReward")));
            trade.setAccountRiskPercent(toDouble(signal.get("accountRiskPercent")));
            trade.setInitialRiskAmount(toDouble(signal.get("initialRiskAmount")));
            trade.setMaxFavorablePrice(toDouble(signal.get("entry")));
            trade.setMaxAdversePrice(toDouble(signal.get("entry")));
            trade.setBestAi(String.valueOf(signal.get("bestAi")));
            trade.setAiConsensus(String.valueOf(signal.get("aiConsensus")));
            trade.setTechnicalSummary(String.valueOf(signal.get("technicalSummary")));
            trade.setNewsRisk(String.valueOf(signal.get("newsRisk")));
            trade.setMacroBias(String.valueOf(signal.get("macroBias")));
            trade.setWhaleBias(String.valueOf(signal.get("whaleBias")));
            trade.setOnChainBias(String.valueOf(signal.get("onChainBias")));
            trade.setDataReadiness(String.valueOf(signal.get("dataReadiness")));
            trade.setIndicatorSnapshot(String.valueOf(signal.get("indicatorSummary")));
            trade.setEntrySnapshot(toJson(signal.get("completeSnapshot")));
            trade.setDecisionEvidence(toJson(signal.get("decisionEvidence")));
            trade.setAiVotesJson(toJson(signal.get("aiVotes")));

            CryptoPaperTrade saved = paperTradeRepository.save(trade);
            audit.setOpenedTradeId(saved.getId());
            decisionAuditRepository.save(audit);
            created.add(saved);
            break;
        }

        return created;
    }

    public List<CryptoPaperTrade> closeRunningTrades() {
        removeLegacyRunningTrades();
        List<CryptoPaperTrade> runningTrades = paperTradeRepository.findByStatus("RUNNING");

        for (CryptoPaperTrade trade : runningTrades) {
            double currentPrice = marketDataService.getLivePrice(trade.getSymbol());
            double direction = "LONG".equals(trade.getSide()) ? 1 : -1;
            double pnl = (currentPrice - trade.getEntryPrice()) * trade.getQuantity() * direction;
            trade.setPnl(pnl);
            double riskDistance = trade.getQuantity() != null && trade.getQuantity() > 0 && trade.getInitialRiskAmount() != null
                    ? trade.getInitialRiskAmount() / trade.getQuantity()
                    : Math.abs(trade.getEntryPrice() - trade.getStopLoss());
            if (riskDistance <= 0) riskDistance = trade.getEntryPrice() * 0.01;

            boolean closed = false;
            if ("LONG".equals(trade.getSide())) {
                trade.setMaxFavorablePrice(Math.max(orDefault(trade.getMaxFavorablePrice(), trade.getEntryPrice()), currentPrice));
                trade.setMaxAdversePrice(Math.min(orDefault(trade.getMaxAdversePrice(), trade.getEntryPrice()), currentPrice));
                if (currentPrice >= trade.getEntryPrice() + riskDistance) {
                    trade.setStopLoss(Math.max(trade.getStopLoss(), trade.getEntryPrice()));
                    trade.setBreakEvenMoved(true);
                }
                if (currentPrice >= trade.getEntryPrice() + riskDistance * 1.5) {
                    trade.setStopLoss(Math.max(trade.getStopLoss(), currentPrice - riskDistance * 0.75));
                }
                if (currentPrice <= trade.getStopLoss()) { closeTrade(trade, currentPrice, pnl, "Stop/trailing stop hit"); closed = true; }
                else if (currentPrice >= trade.getTakeProfit()) { closeTrade(trade, currentPrice, pnl, "Take profit hit"); closed = true; }
            } else {
                trade.setMaxFavorablePrice(Math.min(orDefault(trade.getMaxFavorablePrice(), trade.getEntryPrice()), currentPrice));
                trade.setMaxAdversePrice(Math.max(orDefault(trade.getMaxAdversePrice(), trade.getEntryPrice()), currentPrice));
                if (currentPrice <= trade.getEntryPrice() - riskDistance) {
                    trade.setStopLoss(Math.min(trade.getStopLoss(), trade.getEntryPrice()));
                    trade.setBreakEvenMoved(true);
                }
                if (currentPrice <= trade.getEntryPrice() - riskDistance * 1.5) {
                    trade.setStopLoss(Math.min(trade.getStopLoss(), currentPrice + riskDistance * 0.75));
                }
                if (currentPrice >= trade.getStopLoss()) { closeTrade(trade, currentPrice, pnl, "Stop/trailing stop hit"); closed = true; }
                else if (currentPrice <= trade.getTakeProfit()) { closeTrade(trade, currentPrice, pnl, "Take profit hit"); closed = true; }
            }
            if (!closed) trade.setExitPrice(currentPrice);
            paperTradeRepository.save(trade);
        }

        return runningTrades;
    }

    private void removeLegacyRunningTrades() {
        paperTradeRepository.findByStatus("RUNNING").stream()
                .filter(trade -> !ENGINE_VERSION.equals(trade.getEngineVersion()))
                .forEach(paperTradeRepository::delete);
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
            CryptoMarketDataService.FuturesStats futures = marketDataService.getFuturesStats(symbol, livePrice);
            Map<String, Object> liquidations = liquidationStreamService.snapshot(symbol);
            Map<String, Object> macroNews = macroNewsService.getContext();
            Map<String, Object> whaleSnapshot = whaleStreamService.snapshot(symbol);
            Map<String, Object> onChainSnapshot = onChainService.snapshot(symbol);

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

            boolean aligned = longCount >= 2 || shortCount >= 2;
            boolean fundingRisk = Math.abs(futures.fundingRate) >= 0.001;
            boolean futuresAvailable = futures.openInterest > 0 || futures.markPrice > 0;
            int derivativesLongScore = derivativesScore("LONG", futures);
            int derivativesShortScore = derivativesScore("SHORT", futures);
            int derivativesScore = "LONG".equals(finalSignal) ? derivativesLongScore : derivativesShortScore;
            boolean derivativesAligned = derivativesScore >= 55;

            boolean macroReady = macroDataReady(macroNews);
            boolean newsReady = macroNews.get("headlines") instanceof List<?> list && !list.isEmpty();
            boolean attributedWhaleReady = String.valueOf(whaleSnapshot.get("status")).startsWith("LIVE");
            boolean exchangeWhaleProxyReady = futures.largeTradeCount > 0 || futures.takerBuyNotional > 0 || futures.takerSellNotional > 0;
            boolean whaleReady = attributedWhaleReady || exchangeWhaleProxyReady;
            boolean onChainReady = String.valueOf(onChainSnapshot.get("status")).startsWith("LIVE");

            Map<String, Object> marketSnapshot = new LinkedHashMap<>();
            marketSnapshot.put("spotPrice", livePrice);
            marketSnapshot.put("markPrice", futures.markPrice);
            marketSnapshot.put("indexPrice", futures.indexPrice);
            marketSnapshot.put("spotFuturesBasisPercent", futures.spotFuturesBasisPercent);
            marketSnapshot.put("volume24h", futures.quoteVolume24h);
            marketSnapshot.put("openInterest", futures.openInterest);
            marketSnapshot.put("openInterestValue", futures.openInterestValue);
            marketSnapshot.put("openInterestChangePercent", futures.openInterestChangePercent);
            marketSnapshot.put("fundingRate", futures.fundingRate);
            marketSnapshot.put("longShortRatio", futures.longShortRatio);
            marketSnapshot.put("takerBuySellRatio", futures.takerBuySellRatio);
            marketSnapshot.put("orderBookImbalancePercent", futures.orderBookImbalancePercent);
            marketSnapshot.put("bidDepthNotional", futures.bidDepthNotional);
            marketSnapshot.put("askDepthNotional", futures.askDepthNotional);
            marketSnapshot.put("cvd", futures.cvd);
            marketSnapshot.put("largeTradeBias", futures.largeTradeBias);
            marketSnapshot.put("largeBuyNotional", futures.largeBuyNotional);
            marketSnapshot.put("largeSellNotional", futures.largeSellNotional);
            marketSnapshot.put("liquidations", liquidations);

            Map<String, Object> aiSnapshot = new LinkedHashMap<>();
            aiSnapshot.put("symbol", symbol);
            aiSnapshot.put("candidateSignal", finalSignal);
            aiSnapshot.put("marketMicrostructure", marketSnapshot);
            aiSnapshot.put("technicalTimeframes", Map.of("15m", a15, "1h", a1h, "4h", a4h));
            aiSnapshot.put("macroAndPublishedNews", macroNews);
            aiSnapshot.put("attributedWhales", whaleSnapshot);
            aiSnapshot.put("onChain", onChainSnapshot);
            aiSnapshot.put("riskPolicy", Map.of(
                    "maxRiskPerTradePercent", MAX_RISK_PER_TRADE_PERCENT,
                    "maxDailyLossPercent", MAX_DAILY_LOSS_PERCENT,
                    "maxWeeklyLossPercent", MAX_WEEKLY_LOSS_PERCENT,
                    "maxNotionalLeverage", MAX_NOTIONAL_LEVERAGE,
                    "paperOnly", true
            ));
            aiSnapshot.put("mandatoryDataReadiness", Map.of("market", futuresAvailable, "macro", macroReady, "news", newsReady, "whales", whaleReady, "onChain", onChainReady));
            Map<String, Object> aiConsensus = aiConsensusService.analyze(symbol, aiSnapshot);
            int aiProviderCount = (int) toDouble(aiConsensus.get("configuredProviders"));
            String aiSignal = String.valueOf(aiConsensus.get("signal"));
            boolean aiQuorumReady = Boolean.TRUE.equals(aiConsensus.get("quorumReady"));
            boolean aiAligned = aiQuorumReady && finalSignal.equals(aiSignal);
            int aiConfidence = (int) toDouble(aiConsensus.get("confidence"));

            String macroBias = String.valueOf(macroNews.getOrDefault("macroBias", "NEUTRAL"));
            String newsRisk = String.valueOf(macroNews.getOrDefault("risk", "NORMAL"));
            String whaleBias = attributedWhaleReady
                    ? String.valueOf(whaleSnapshot.getOrDefault("bias", "NEUTRAL"))
                    : futures.largeTradeBias;
            String onChainBias = String.valueOf(onChainSnapshot.getOrDefault("bias", "NEUTRAL"));
            int macroNewsScore = directionalScore(finalSignal, macroBias, newsRisk);
            int whaleScore = directionalScore(finalSignal, whaleBias, "NORMAL");
            int onChainScore = directionalScore(finalSignal, onChainBias, "NORMAL");
            int technicalLongScore = technicalDirectionScore("LONG", List.of(a15, a1h, a4h));
            int technicalShortScore = technicalDirectionScore("SHORT", List.of(a15, a1h, a4h));
            int macroLongScore = directionalScore("LONG", macroBias, newsRisk);
            int macroShortScore = directionalScore("SHORT", macroBias, newsRisk);
            int whaleLongScore = directionalScore("LONG", whaleBias, "NORMAL");
            int whaleShortScore = directionalScore("SHORT", whaleBias, "NORMAL");
            int onChainLongScore = directionalScore("LONG", onChainBias, "NORMAL");
            int onChainShortScore = directionalScore("SHORT", onChainBias, "NORMAL");
            int aiLongAverage = (int) toDouble(aiConsensus.get("aiLongAveragePercent"));
            int aiShortAverage = (int) toDouble(aiConsensus.get("aiShortAveragePercent"));
            int aiNoTradeAverage = (int) toDouble(aiConsensus.get("aiNoTradeAveragePercent"));
            int longBlend = blendedDirectionScore(technicalLongScore, derivativesLongScore, macroLongScore, whaleLongScore, onChainLongScore, aiLongAverage);
            int shortBlend = blendedDirectionScore(technicalShortScore, derivativesShortScore, macroShortScore, whaleShortScore, onChainShortScore, aiShortAverage);
            int finalScore = "LONG".equals(finalSignal) ? longBlend : shortBlend;
            boolean macroBlocked = "HIGH".equals(newsRisk) || ("RISK_OFF".equals(macroBias) && "LONG".equals(finalSignal));
            boolean mandatoryReady = futuresAvailable && macroReady && newsReady && whaleReady && onChainReady && aiQuorumReady;
            boolean allowed = mandatoryReady && aligned && finalScore >= 65 && derivativesAligned && aiAligned && aiConfidence >= 60 && !fundingRisk && !macroBlocked;

            double riskAmount = PAPER_ACCOUNT_EQUITY * MAX_RISK_PER_TRADE_PERCENT / 100.0;
            double riskBasedQuantity = stopDistance <= 0 ? 0 : riskAmount / stopDistance;
            double maxQuantityByLeverage = PAPER_ACCOUNT_EQUITY * MAX_NOTIONAL_LEVERAGE / livePrice;
            double positionSize = Math.min(riskBasedQuantity, maxQuantityByLeverage);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("symbol", symbol);
            map.put("finalSignal", allowed ? finalSignal : "NO_TRADE");
            map.put("candidateSignal", finalSignal);
            map.put("rawSignal", finalSignal);
            map.put("allowed", allowed);
            map.put("confidence", finalScore);
            map.put("finalScore", finalScore);
            map.put("directionProbabilities", Map.of(
                    "longPercent", longBlend,
                    "shortPercent", shortBlend,
                    "aiLongAveragePercent", aiLongAverage,
                    "aiShortAveragePercent", aiShortAverage,
                    "aiNoTradeAveragePercent", aiNoTradeAverage,
                    "label", "WEIGHTED_DECISION_STRENGTH_NOT_GUARANTEED_PROBABILITY"
            ));
            map.put("entry", livePrice);
            map.put("currentPrice", livePrice);
            map.put("priceSource", marketDataService.getSpotSource());
            map.put("marketWarning", "");
            map.put("stopLoss", stopLoss);
            map.put("takeProfit", takeProfit);
            map.put("trailingStop", trailingStop);
            map.put("riskReward", riskReward);
            map.put("positionSize", positionSize);
            map.put("positionNotional", positionSize * livePrice);
            map.put("accountRiskPercent", MAX_RISK_PER_TRADE_PERCENT);
            map.put("initialRiskAmount", riskAmount);
            map.put("macroBias", macroBias);
            map.put("newsRisk", newsRisk);
            map.put("whaleBias", whaleBias);
            map.put("onChainBias", onChainBias);
            map.put("dataReadiness", mandatoryReady ? "READY" : "BLOCKED_MISSING_MANDATORY_DATA");
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
            futuresData.put("spotPrice", futures.spotPrice);
            futuresData.put("spotFuturesBasisPercent", futures.spotFuturesBasisPercent);
            futuresData.put("bidDepthNotional", futures.bidDepthNotional);
            futuresData.put("askDepthNotional", futures.askDepthNotional);
            futuresData.put("orderBookImbalancePercent", futures.orderBookImbalancePercent);
            futuresData.put("takerBuyNotional", futures.takerBuyNotional);
            futuresData.put("takerSellNotional", futures.takerSellNotional);
            futuresData.put("cvd", futures.cvd);
            futuresData.put("longShortRatio", futures.longShortRatio);
            futuresData.put("longAccount", futures.longAccount);
            futuresData.put("shortAccount", futures.shortAccount);
            futuresData.put("takerBuySellRatio", futures.takerBuySellRatio);
            futuresData.put("largeBuyNotional", futures.largeBuyNotional);
            futuresData.put("largeSellNotional", futures.largeSellNotional);
            futuresData.put("largeTradeCount", futures.largeTradeCount);
            futuresData.put("largeTradeThreshold", futures.largeTradeThreshold);
            futuresData.put("largeTradeBias", futures.largeTradeBias);
            futuresData.put("largeTradeSource", futures.largeTradeSource);
            futuresData.put("derivativesScore", derivativesScore);
            futuresData.put("fetchedAt", futures.fetchedAt);
            futuresData.put("source", marketDataService.getFuturesSource());
            map.put("futuresData", futuresData);
            map.put("liquidationData", liquidations);
            map.put("exchangeLargeTradeProxy", Map.of(
                    "type", "EXCHANGE_LARGE_TRADE_PROXY",
                    "status", futures.largeTradeCount > 0 ? "LIVE" : "NO_LARGE_TRADES_IN_SAMPLE",
                    "buyNotional", futures.largeBuyNotional,
                    "sellNotional", futures.largeSellNotional,
                    "bias", futures.largeTradeBias,
                    "source", futures.largeTradeSource,
                    "onChain", false
            ));
            map.put("whaleData", whaleSnapshot);
            map.put("onChainData", onChainSnapshot);
            map.put("macroNewsData", macroNews);

            map.put("timeframes", List.of(
                    timeframeRow("15m", a15),
                    timeframeRow("1h", a1h),
                    timeframeRow("4h", a4h)
            ));

            map.put("aiVotes", aiConsensus.get("votes"));
            map.put("aiConsensus", aiConsensus);
            map.put("bestAi", aiQuorumReady ? "Adaptive multi-provider AI consensus" : "No AI quorum (any 2 live providers required)");
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
            Map<String, Object> readiness = new LinkedHashMap<>();
            readiness.put("market", futuresAvailable ? "LIVE" : "UNAVAILABLE");
            readiness.put("macro", macroReady ? "LIVE" : "UNAVAILABLE");
            readiness.put("news", newsReady ? "LIVE" : "UNAVAILABLE");
            readiness.put("whales", attributedWhaleReady ? "LIVE_ATTRIBUTED" : exchangeWhaleProxyReady ? "LIVE_PROXY" : String.valueOf(whaleSnapshot.get("status")));
            readiness.put("onChain", onChainReady ? "LIVE" : String.valueOf(onChainSnapshot.get("status")));
            readiness.put("ai", aiQuorumReady
                    ? "LIVE (" + aiProviderCount + "/" + MIN_LIVE_AI_PROVIDERS + ")"
                    : "NEED_2_LIVE (" + aiProviderCount + "/" + MIN_LIVE_AI_PROVIDERS + ")");
            map.put("providerReadiness", readiness);
            Map<String, Object> engineScores = Map.of("technical", avgScore, "derivatives", derivativesScore, "macroNews", macroNewsScore, "whales", whaleScore, "onChain", onChainScore, "ai", aiConfidence);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("engineScores", engineScores);
            evidence.put("timeframesAligned", aligned);
            evidence.put("derivativesAligned", derivativesAligned);
            evidence.put("aiAligned", aiAligned);
            evidence.put("fundingSafe", !fundingRisk);
            evidence.put("macroGuardSafe", !macroBlocked);
            evidence.put("mandatoryProvidersReady", mandatoryReady);
            evidence.put("candidateSignal", finalSignal);
            evidence.put("finalScore", finalScore);
            evidence.put("directionProbabilities", map.get("directionProbabilities"));
            map.put("decisionEvidence", evidence);
            map.put("completeSnapshot", aiSnapshot);
            List<String> blockers = buildBlockReasons(readiness, aligned, finalScore, derivativesAligned, aiAligned, aiConfidence, fundingRisk, macroBlocked);
            map.put("blockers", blockers);
            map.put("blockReason", allowed ? "" : String.join(" | ", blockers));

            return map;

        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("symbol", symbol);
            error.put("finalSignal", "NO_TRADE");
            error.put("candidateSignal", "NO_TRADE");
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
            error.put("dataReadiness", "ERROR");
            error.put("providerReadiness", Map.of("market", "ERROR"));
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
        if ((futures.orderBookImbalancePercent > 5 && isLong) || (futures.orderBookImbalancePercent < -5 && !isLong)) score += 8;
        else if (Math.abs(futures.orderBookImbalancePercent) > 5) score -= 6;
        if ((futures.cvd > 0 && isLong) || (futures.cvd < 0 && !isLong)) score += 8; else if (futures.cvd != 0) score -= 6;
        if (Math.abs(futures.fundingRate) >= 0.001) score -= 25;
        return Math.max(0, Math.min(100, score));
    }

    private int technicalDirectionScore(String direction, List<Map<String, Object>> timeframes) {
        double total = 0;
        for (Map<String, Object> timeframe : timeframes) {
            double confidence = toDouble(timeframe.get("score"));
            total += direction.equals(timeframe.get("signal")) ? confidence : 100 - confidence;
        }
        return (int) Math.round(total / Math.max(1, timeframes.size()));
    }

    private int blendedDirectionScore(int technical, int derivatives, int macroNews, int whales, int onChain, int ai) {
        return (int) Math.round(technical * 0.35 + derivatives * 0.20 + macroNews * 0.15
                + whales * 0.075 + onChain * 0.075 + ai * 0.15);
    }

    public Map<String, Object> resetOldPaperTrades() {
        long count = paperTradeRepository.count();
        long decisions = decisionAuditRepository.count();
        paperTradeRepository.deleteAll();
        decisionAuditRepository.deleteAll();
        return Map.of("status", "RESET", "deletedCryptoPaperTrades", count, "deletedCryptoDecisionAudits", decisions,
                "preservedBillingAndInventoryData", true, "engineVersion", ENGINE_VERSION);
    }

    private CryptoDecisionAudit saveDecisionAudit(Map<String, Object> signal) {
        CryptoDecisionAudit audit = new CryptoDecisionAudit();
        audit.setSymbol(String.valueOf(signal.get("symbol")));
        audit.setCandidateSignal(String.valueOf(signal.getOrDefault("candidateSignal", "NO_TRADE")));
        audit.setFinalSignal(String.valueOf(signal.getOrDefault("finalSignal", "NO_TRADE")));
        audit.setAllowed(Boolean.TRUE.equals(signal.get("allowed")));
        audit.setFinalScore(toDouble(signal.get("finalScore")));
        audit.setDataReadiness(String.valueOf(signal.getOrDefault("dataReadiness", "UNKNOWN")));
        audit.setBlockReason(truncate(String.valueOf(signal.getOrDefault("blockReason", "")), 250));
        audit.setEngineVersion(ENGINE_VERSION);
        audit.setCompleteSnapshot(toJson(signal.get("completeSnapshot")));
        audit.setDecisionEvidence(toJson(signal.get("decisionEvidence")));
        audit.setAiVotesJson(toJson(signal.get("aiVotes")));
        return decisionAuditRepository.save(audit);
    }

    private boolean macroDataReady(Map<String, Object> macro) {
        int live = 0;
        for (String key : List.of("sp500", "nasdaq", "dxy", "us10y", "gold", "oil", "vix")) {
            Object raw = macro.get(key);
            if (raw instanceof Map<?, ?> value && String.valueOf(value.get("status")).startsWith("LIVE")) live++;
        }
        return live >= 5;
    }

    private int directionalScore(String candidate, String bias, String risk) {
        if ("HIGH".equals(risk)) return 10;
        if ("NEUTRAL".equals(bias) || "NORMAL".equals(bias) || "UNKNOWN".equals(bias)) return 50;
        boolean longCandidate = "LONG".equals(candidate);
        boolean bullish = "BULLISH".equals(bias) || "RISK_ON".equals(bias) || "BUY".equals(bias);
        boolean bearish = "BEARISH".equals(bias) || "RISK_OFF".equals(bias) || "SELL".equals(bias);
        if ((longCandidate && bullish) || (!longCandidate && bearish)) return 80;
        if ((longCandidate && bearish) || (!longCandidate && bullish)) return 20;
        return 50;
    }

    private List<String> buildBlockReasons(Map<String, Object> readiness, boolean aligned, int finalScore,
                                           boolean derivativesAligned, boolean aiAligned, int aiConfidence,
                                           boolean fundingRisk, boolean macroBlocked) {
        List<String> reasons = new ArrayList<>();
        List<String> missing = readiness.entrySet().stream()
                .filter(entry -> !String.valueOf(entry.getValue()).startsWith("LIVE"))
                .map(Map.Entry::getKey).toList();
        if (!missing.isEmpty()) reasons.add("Mandatory engines unavailable: " + String.join(", ", missing));
        if (macroBlocked) reasons.add("Macro/news risk guard blocked this direction");
        if (fundingRisk) reasons.add("Funding rate exceeds safety limit");
        if (!aligned) reasons.add("At least 2 of 3 technical timeframes do not agree");
        if (!derivativesAligned) reasons.add("Futures/order-book/CVD confirmation is below 55%");
        if (!aiAligned) reasons.add("Live multi-provider AI consensus does not agree with the technical candidate");
        if (aiConfidence < 60) reasons.add("Winning AI direction confidence is below 60%");
        if (finalScore < 65) reasons.add("Weighted all-engine score is " + finalScore + "% (minimum 65%)");
        if (reasons.isEmpty()) reasons.add("Deterministic risk circuit blocked the trade");
        return reasons;
    }

    private boolean riskCircuitAllowsNewTrade() {
        List<CryptoPaperTrade> trades = paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(8));
        LocalDateTime today = java.time.LocalDate.now().atStartOfDay();
        double dailyPnl = trades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(today)).mapToDouble(t -> orDefault(t.getPnl(), 0)).sum();
        double weeklyPnl = trades.stream().mapToDouble(t -> orDefault(t.getPnl(), 0)).sum();
        return dailyPnl > -(PAPER_ACCOUNT_EQUITY * MAX_DAILY_LOSS_PERCENT / 100.0)
                && weeklyPnl > -(PAPER_ACCOUNT_EQUITY * MAX_WEEKLY_LOSS_PERCENT / 100.0);
    }

    private void closeTrade(CryptoPaperTrade trade, double currentPrice, double pnl, String reason) {
        trade.setExitPrice(currentPrice);
        trade.setPnl(pnl);
        trade.setStatus(pnl >= 0 ? "PROFIT" : "LOSS");
        trade.setCloseReason(reason);
        trade.setClosedAt(LocalDateTime.now());
        trade.setExitSnapshot(toJson(Map.of(
                "price", currentPrice,
                "pnl", pnl,
                "reason", reason,
                "maxFavorablePrice", orDefault(trade.getMaxFavorablePrice(), currentPrice),
                "maxAdversePrice", orDefault(trade.getMaxAdversePrice(), currentPrice),
                "breakEvenMoved", Boolean.TRUE.equals(trade.getBreakEvenMoved()),
                "closedAt", trade.getClosedAt().toString()
        )));
    }

    private Map<String, Object> buildLearningReport(List<CryptoPaperTrade> trades) {
        List<CryptoPaperTrade> closed = trades.stream().filter(t -> Set.of("PROFIT", "LOSS").contains(t.getStatus())).toList();
        Map<String, double[]> stats = new LinkedHashMap<>();
        for (String engine : List.of("technical", "derivatives", "macroNews", "whales", "onChain", "ai")) stats.put(engine, new double[3]);
        for (CryptoPaperTrade trade : closed) {
            try {
                Map<?, ?> evidence = objectMapper.readValue(trade.getDecisionEvidence(), Map.class);
                if (!(evidence.get("engineScores") instanceof Map<?, ?> scores)) continue;
                for (Map.Entry<String, double[]> entry : stats.entrySet()) {
                    double score = toDouble(scores.get(entry.getKey()));
                    entry.getValue()[0] += score;
                    entry.getValue()[1] += "PROFIT".equals(trade.getStatus()) ? score : 0;
                    entry.getValue()[2] += 1;
                }
            } catch (Exception ignored) { }
        }
        Map<String, Object> engines = new LinkedHashMap<>();
        stats.forEach((name, value) -> engines.put(name, Map.of(
                "samples", (int) value[2],
                "averageEntryScore", value[2] == 0 ? 0 : Math.round(value[0] / value[2]),
                "profitableScoreContribution", value[2] == 0 ? 0 : Math.round(value[1] / value[2]),
                "learningActive", value[2] >= 20
        )));
        return Map.of("closedTradeSamples", closed.size(), "minimumSamplesForWeightAdjustment", 20, "engines", engines,
                "policy", "Store evidence now; adjust bounded weights only after 20 closed paper trades");
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception ignored) { return "{}"; }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private double orDefault(Double value, double fallback) { return value == null ? fallback : value; }

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
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }
}
