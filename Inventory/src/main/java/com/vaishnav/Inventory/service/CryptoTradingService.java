package com.vaishnav.Inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.entity.CryptoDecisionAudit;
import com.vaishnav.Inventory.repository.CryptoPaperTradeRepository;
import com.vaishnav.Inventory.repository.CryptoDecisionAuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CryptoTradingService {

    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT");
    private static final int MAX_DAILY_PAPER_TRADES = 5;
    private static final double PAPER_ACCOUNT_EQUITY = 100_000;
    private static final double MAX_RISK_PER_TRADE_PERCENT = 1.0;
    private static final double EXPLORATORY_RISK_PER_TRADE_PERCENT = 0.25;
    private static final double LEARNING_SCOUT_RISK_PER_TRADE_PERCENT = 0.10;
    private static final double PRACTICE_SCOUT_RISK_PER_TRADE_PERCENT = 0.05;
    private static final double MAX_DAILY_LOSS_PERCENT = 3.0;
    private static final double MAX_WEEKLY_LOSS_PERCENT = 8.0;
    private static final double MAX_NOTIONAL_LEVERAGE = 3.0;
    private static final int MIN_LIVE_AI_PROVIDERS = 2;
    private static final int MIN_LEARNING_LIVE_AI_PROVIDERS = 1;
    private static final String ENGINE_VERSION = "AEGIS_V7_AI_FALLBACK_LEARNING";
    private static final int STANDARD_MIN_SCORE = 65;
    private static final int EXPLORATORY_MIN_SCORE = 56;
    private static final int LEARNING_SCOUT_MIN_SCORE = 52;
    private static final int STANDARD_MIN_DERIVATIVES = 55;
    private static final int EXPLORATORY_MIN_DERIVATIVES = 45;
    private static final int LEARNING_SCOUT_MIN_DERIVATIVES = 40;
    private static final int AI_PREFILTER_MIN_SCORE = 50;
    private static final int EXPLORATORY_MIN_AI_CONFIDENCE = 52;
    private static final int LEARNING_SCOUT_MIN_AI_CONFIDENCE = 45;
    private static final int PRACTICE_SCOUT_MIN_SCORE = 44;
    private static final int PRACTICE_SCOUT_MIN_DERIVATIVES = 35;
    private static final int MIN_LEARNING_SAMPLES = 20;
    private static final long LEARNING_CACHE_MS = 10 * 60 * 1000L;
    private static final Map<String, Double> BASE_WEIGHTS = Map.of(
            "technical", 0.30,
            "derivatives", 0.18,
            "macroNews", 0.14,
            "whales", 0.07,
            "onChain", 0.07,
            "historical", 0.12,
            "ai", 0.12
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Double> learnedWeights = BASE_WEIGHTS;
    private volatile long learnedWeightsAt;

    @Value("${CRYPTO_PRACTICE_SCOUT_ENABLED:true}")
    private boolean practiceScoutEnabled;

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

    @Autowired
    private CryptoHistoricalMemoryService historicalMemoryService;


    public Map<String, Object> getDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> signals = SYMBOLS.stream()
                .map(this::buildSignal)
                .toList();

        removeLegacyRunningTrades();
        List<CryptoPaperTrade> trades = paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(65)).stream()
                .filter(trade -> ENGINE_VERSION.equals(trade.getEngineVersion()))
                .toList();

        response.put("mode", "REAL_CANDLE_AUTONOMOUS_PAPER");
        response.put("realMoneyEnabled", false);
        response.put("cashMode", "DISABLED");
        response.put("practiceScoutEnabled", practiceScoutEnabled);
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
                "practiceScoutRiskPercent", PRACTICE_SCOUT_RISK_PER_TRADE_PERCENT,
                "practiceScoutMinimumScore", PRACTICE_SCOUT_MIN_SCORE,
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
        response.put("twoMonthReport", buildTwoMonthReport(trades));
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
            boolean timeStop = trade.getOpenedAt() != null && trade.getOpenedAt().isBefore(LocalDateTime.now().minusHours(24));
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
            if (!closed && timeStop) { closeTrade(trade, currentPrice, pnl, "24h time stop"); closed = true; }
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
            Map<String, Object> historicalMemory = historicalMemoryService.snapshot(symbol, macroNews, livePrice);

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
            boolean derivativesAligned = derivativesScore >= STANDARD_MIN_DERIVATIVES;

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
            aiSnapshot.put("technicalTimeframes", compactTimeframes(a15, a1h, a4h));
            aiSnapshot.put("macroAndPublishedNews", compactMacroNews(macroNews));
            aiSnapshot.put("attributedWhales", compactProviderSnapshot(whaleSnapshot));
            aiSnapshot.put("onChain", compactProviderSnapshot(onChainSnapshot));
            aiSnapshot.put("historicalPatternAndEventMemory", compactHistoricalMemory(historicalMemory));
            aiSnapshot.put("riskPolicy", Map.of(
                    "maxRiskPerTradePercent", MAX_RISK_PER_TRADE_PERCENT,
                    "maxDailyLossPercent", MAX_DAILY_LOSS_PERCENT,
                    "maxWeeklyLossPercent", MAX_WEEKLY_LOSS_PERCENT,
                    "maxNotionalLeverage", MAX_NOTIONAL_LEVERAGE,
                    "paperOnly", true
            ));
            aiSnapshot.put("mandatoryDataReadiness", Map.of("market", futuresAvailable, "macro", macroReady, "news", newsReady, "whales", whaleReady, "onChain", onChainReady));
            String macroBias = String.valueOf(macroNews.getOrDefault("macroBias", "NEUTRAL"));
            String newsRisk = String.valueOf(macroNews.getOrDefault("risk", "NORMAL"));
            String whaleBias = attributedWhaleReady
                    ? String.valueOf(whaleSnapshot.getOrDefault("bias", "NEUTRAL"))
                    : futures.largeTradeBias;
            String onChainBias = String.valueOf(onChainSnapshot.getOrDefault("bias", "NEUTRAL"));
            Map<?, ?> historicalPattern = historicalMemory.get("pricePattern") instanceof Map<?, ?> value ? value : Map.of();
            String historicalBias = String.valueOf(historicalPattern.containsKey("bias") ? historicalPattern.get("bias") : "NEUTRAL");
            boolean historicalReady = String.valueOf(historicalPattern.get("status")).startsWith("LIVE");
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
            int historicalLongScore = directionalScore("LONG", historicalBias, "NORMAL");
            int historicalShortScore = directionalScore("SHORT", historicalBias, "NORMAL");
            boolean macroBlocked = "HIGH".equals(newsRisk) || ("RISK_OFF".equals(macroBias) && "LONG".equals(finalSignal));
            int preAiLongScore = blendedDirectionScore(technicalLongScore, derivativesLongScore, macroLongScore, whaleLongScore, onChainLongScore, historicalLongScore, 50);
            int preAiShortScore = blendedDirectionScore(technicalShortScore, derivativesShortScore, macroShortScore, whaleShortScore, onChainShortScore, historicalShortScore, 50);
            boolean exploratoryDerivativesAligned = derivativesScore >= EXPLORATORY_MIN_DERIVATIVES;
            boolean learningScoutDerivativesAligned = derivativesScore >= LEARNING_SCOUT_MIN_DERIVATIVES;
            boolean aiReviewEligible = futuresAvailable && macroReady && newsReady && whaleReady && onChainReady && historicalReady
                    && aligned && learningScoutDerivativesAligned && !fundingRisk
                    && Math.max(preAiLongScore, preAiShortScore) >= AI_PREFILTER_MIN_SCORE;
            aiSnapshot.put("aiReviewEligible", aiReviewEligible);
            aiSnapshot.put("preAiLongScore", preAiLongScore);
            aiSnapshot.put("preAiShortScore", preAiShortScore);
            Map<String, Object> aiConsensus = aiReviewEligible
                    ? aiConsensusService.analyze(symbol, aiSnapshot)
                    : aiConsensusService.skippedByPrefilter();
            int aiProviderCount = (int) toDouble(aiConsensus.get("liveProviders"));
            String aiSignal = String.valueOf(aiConsensus.get("signal"));
            boolean aiQuorumReady = Boolean.TRUE.equals(aiConsensus.get("quorumReady"));
            boolean aiStrictQuorumReady = Boolean.TRUE.equals(aiConsensus.get("strictQuorumReady"));
            boolean aiAligned = aiQuorumReady && finalSignal.equals(aiSignal);
            int aiConfidence = (int) toDouble(aiConsensus.get("confidence"));
            int aiLongAverage = (int) toDouble(aiConsensus.get("aiLongAveragePercent"));
            int aiShortAverage = (int) toDouble(aiConsensus.get("aiShortAveragePercent"));
            int aiNoTradeAverage = (int) toDouble(aiConsensus.get("aiNoTradeAveragePercent"));
            int longBlend = blendedDirectionScore(technicalLongScore, derivativesLongScore, macroLongScore, whaleLongScore, onChainLongScore, historicalLongScore, aiLongAverage);
            int shortBlend = blendedDirectionScore(technicalShortScore, derivativesShortScore, macroShortScore, whaleShortScore, onChainShortScore, historicalShortScore, aiShortAverage);
            int finalScore = "LONG".equals(finalSignal) ? longBlend : shortBlend;
            int preAiCandidateScore = "LONG".equals(finalSignal) ? preAiLongScore : preAiShortScore;
            boolean marketEnginesReady = futuresAvailable && macroReady && newsReady && whaleReady && onChainReady && historicalReady;
            boolean standardAllowed = marketEnginesReady && aiStrictQuorumReady && aligned && finalScore >= STANDARD_MIN_SCORE && derivativesAligned && aiAligned && aiConfidence >= 60 && !fundingRisk && !macroBlocked;
            boolean exploratoryAllowed = marketEnginesReady && aiStrictQuorumReady && aligned && finalScore >= EXPLORATORY_MIN_SCORE && exploratoryDerivativesAligned
                    && aiAligned && aiConfidence >= EXPLORATORY_MIN_AI_CONFIDENCE && !fundingRisk && !macroBlocked;
            boolean learningScoutAllowed = marketEnginesReady && aiQuorumReady && aligned && finalScore >= LEARNING_SCOUT_MIN_SCORE && learningScoutDerivativesAligned
                    && aiAligned && aiConfidence >= LEARNING_SCOUT_MIN_AI_CONFIDENCE && !fundingRisk;
            boolean practiceMacroCompatible = !("RISK_OFF".equals(macroBias) && "LONG".equals(finalSignal))
                    && !("RISK_ON".equals(macroBias) && "SHORT".equals(finalSignal));
            boolean practiceScoutAllowed = practiceScoutEnabled
                    && marketEnginesReady
                    && aligned
                    && derivativesScore >= PRACTICE_SCOUT_MIN_DERIVATIVES
                    && preAiCandidateScore >= PRACTICE_SCOUT_MIN_SCORE
                    && !fundingRisk
                    && practiceMacroCompatible;
            boolean allowed = standardAllowed || exploratoryAllowed || learningScoutAllowed || practiceScoutAllowed;
            String opportunityTier = standardAllowed ? "STANDARD"
                    : exploratoryAllowed ? "EXPLORATORY_LEARNING"
                    : learningScoutAllowed ? "LEARNING_SCOUT"
                    : practiceScoutAllowed ? "PRACTICE_SCOUT"
                    : "BLOCKED";
            int decisionScore = practiceScoutAllowed && !standardAllowed && !exploratoryAllowed && !learningScoutAllowed
                    ? preAiCandidateScore
                    : finalScore;
            double effectiveRiskPercent = standardAllowed ? MAX_RISK_PER_TRADE_PERCENT
                    : exploratoryAllowed ? EXPLORATORY_RISK_PER_TRADE_PERCENT
                    : learningScoutAllowed ? LEARNING_SCOUT_RISK_PER_TRADE_PERCENT
                    : practiceScoutAllowed ? PRACTICE_SCOUT_RISK_PER_TRADE_PERCENT
                    : 0.0;

            double riskAmount = PAPER_ACCOUNT_EQUITY * effectiveRiskPercent / 100.0;
            double riskBasedQuantity = stopDistance <= 0 ? 0 : riskAmount / stopDistance;
            double maxQuantityByLeverage = PAPER_ACCOUNT_EQUITY * MAX_NOTIONAL_LEVERAGE / livePrice;
            double positionSize = Math.min(riskBasedQuantity, maxQuantityByLeverage);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("symbol", symbol);
            map.put("finalSignal", allowed ? finalSignal : "NO_TRADE");
            map.put("candidateSignal", finalSignal);
            map.put("rawSignal", finalSignal);
            map.put("allowed", allowed);
            map.put("opportunityTier", opportunityTier);
            map.put("confidence", decisionScore);
            map.put("finalScore", decisionScore);
            map.put("aiAdjustedScore", finalScore);
            map.put("practiceScout", practiceScoutAllowed);
            map.put("aiReviewEligible", aiReviewEligible);
            map.put("preAiLongScore", preAiLongScore);
            map.put("preAiShortScore", preAiShortScore);
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
            map.put("accountRiskPercent", effectiveRiskPercent);
            map.put("initialRiskAmount", riskAmount);
            map.put("macroBias", macroBias);
            map.put("newsRisk", newsRisk);
            map.put("whaleBias", whaleBias);
            map.put("onChainBias", onChainBias);
            map.put("historicalBias", historicalBias);
            map.put("dataReadiness", marketEnginesReady
                    ? practiceScoutAllowed ? "READY_PRACTICE_PAPER"
                    : aiQuorumReady ? "READY"
                    : "READY_WAITING_AI"
                    : "BLOCKED_MISSING_MANDATORY_DATA");
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
            map.put("historicalMemory", historicalMemory);

            map.put("timeframes", List.of(
                    timeframeRow("15m", a15),
                    timeframeRow("1h", a1h),
                    timeframeRow("4h", a4h)
            ));

            map.put("aiVotes", aiConsensus.get("votes"));
            map.put("aiConsensus", aiConsensus);
            map.put("bestAi", practiceScoutAllowed
                    ? "Deterministic practice scout; AI advisory unavailable/disagreed"
                    : aiQuorumReady
                    ? "Adaptive multi-provider AI consensus"
                    : "No AI quorum (any 1 live provider required)");
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
            readiness.put("historical", String.valueOf(historicalPattern.get("status")));
            readiness.put("ai", aiStrictQuorumReady
                    ? "LIVE_FULL (" + aiProviderCount + "/" + MIN_LIVE_AI_PROVIDERS + ")"
                    : aiQuorumReady
                    ? "LIVE_LEARNING (" + aiProviderCount + "/" + MIN_LEARNING_LIVE_AI_PROVIDERS + ")"
                    : "NEED_1_LIVE (" + aiProviderCount + "/" + MIN_LEARNING_LIVE_AI_PROVIDERS + ")");
            map.put("providerReadiness", readiness);
            int historicalScore = "LONG".equals(finalSignal) ? historicalLongScore : historicalShortScore;
            Map<String, Object> engineScores = Map.of("technical", avgScore, "derivatives", derivativesScore, "macroNews", macroNewsScore, "whales", whaleScore, "onChain", onChainScore, "historical", historicalScore, "ai", aiConfidence);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("engineScores", engineScores);
            evidence.put("timeframesAligned", aligned);
            evidence.put("derivativesAligned", derivativesAligned);
            evidence.put("aiAligned", aiAligned);
            evidence.put("aiLearningQuorumReady", aiQuorumReady);
            evidence.put("aiStrictQuorumReady", aiStrictQuorumReady);
            evidence.put("fundingSafe", !fundingRisk);
            evidence.put("macroGuardSafe", !macroBlocked);
            evidence.put("mandatoryProvidersReady", marketEnginesReady);
            evidence.put("candidateSignal", finalSignal);
            evidence.put("finalScore", decisionScore);
            evidence.put("aiAdjustedScore", finalScore);
            evidence.put("preAiCandidateScore", preAiCandidateScore);
            evidence.put("practiceScoutAllowed", practiceScoutAllowed);
            evidence.put("directionProbabilities", map.get("directionProbabilities"));
            map.put("decisionEvidence", evidence);
            map.put("completeSnapshot", aiSnapshot);
            List<String> blockers = buildBlockReasons(readiness, aligned, decisionScore, learningScoutDerivativesAligned, aiQuorumReady, aiAligned, aiConfidence, fundingRisk, macroBlocked);
            if (!allowed && practiceScoutEnabled) {
                if (!practiceMacroCompatible) blockers.add("Practice scout direction conflicts with macro regime");
                if (derivativesScore < PRACTICE_SCOUT_MIN_DERIVATIVES) {
                    blockers.add("Practice derivatives score below " + PRACTICE_SCOUT_MIN_DERIVATIVES + "%");
                }
                if (preAiCandidateScore < PRACTICE_SCOUT_MIN_SCORE) {
                    blockers.add("Practice pre-AI score below " + PRACTICE_SCOUT_MIN_SCORE + "%");
                }
            }
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

    private Map<String, Object> compactTimeframes(Map<String, Object> a15, Map<String, Object> a1h, Map<String, Object> a4h) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("15m", compactTimeframe(a15));
        compact.put("1h", compactTimeframe(a1h));
        compact.put("4h", compactTimeframe(a4h));
        return compact;
    }

    private Map<String, Object> compactTimeframe(Map<String, Object> analysis) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of(
                "signal", "score", "lastClose", "rsi14", "macd", "macdSignal", "macdHistogram",
                "ema20", "ema50", "ema200", "sma20", "sma50", "sma200", "vwap48",
                "bollingerPosition", "bollingerBandwidth", "atr14", "adx14", "plusDI14", "minusDI14",
                "roc10", "momentum10", "volumeRatio20", "priceVsVwapPercent", "bullish", "bearish"
        )) {
            if (analysis.containsKey(key)) row.put(key, analysis.get(key));
        }
        return row;
    }

    private Map<String, Object> compactMacroNews(Map<String, Object> macro) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of(
                "status", "risk", "macroBias", "headlineRiskCount", "headlinePositiveCount",
                "newsSentimentScore", "newsSource", "macroSource", "futureNewsDisclaimer"
        )) {
            if (macro.containsKey(key)) compact.put(key, macro.get(key));
        }
        Map<String, Object> assets = new LinkedHashMap<>();
        for (String key : List.of("sp500", "nasdaq", "vix", "dxy", "us10y", "gold", "oil")) {
            Object raw = macro.get(key);
            if (raw instanceof Map<?, ?> value) assets.put(key, compactMap(value, List.of("status", "source", "proxySymbol", "changePercent", "price")));
        }
        compact.put("assets", assets);
        if (macro.get("newsSourceStatus") instanceof Map<?, ?> status) compact.put("newsSourceStatus", status);
        if (macro.get("headlines") instanceof List<?> headlines) {
            compact.put("topHeadlines", compactList(headlines, 6, List.of("title", "source", "publishedAt", "sentiment", "sentimentLabel", "highImpact")));
        }
        return compact;
    }

    private Map<String, Object> compactProviderSnapshot(Map<String, Object> snapshot) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of(
                "status", "source", "bias", "score", "netFlow", "netflow", "exchangeReserve",
                "whaleRatio", "stablecoinSupplyRatio", "largeTransferCount", "buyNotional",
                "sellNotional", "inflow", "outflow", "fetchedAt"
        )) {
            if (snapshot.containsKey(key)) compact.put(key, snapshot.get(key));
        }
        for (String listKey : List.of("events", "transfers", "alerts", "topTransfers", "wallets", "metrics")) {
            if (snapshot.get(listKey) instanceof List<?> rows) {
                compact.put(listKey, compactList(rows, 8, List.of(
                        "symbol", "asset", "amount", "amountUsd", "usd", "from", "to", "direction",
                        "type", "source", "bias", "timestamp", "label", "entity", "metric", "value"
                )));
            }
        }
        return compact;
    }

    private Map<String, Object> compactHistoricalMemory(Map<String, Object> memory) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("status", "source", "bias", "score", "samples", "matchedEvents", "fetchedAt")) {
            if (memory.containsKey(key)) compact.put(key, memory.get(key));
        }
        Object pattern = memory.get("pricePattern");
        if (pattern instanceof Map<?, ?> value) compact.put("pricePattern", compactMap(value, List.of("status", "bias", "confidence", "similarity", "samples", "reason")));
        Object events = memory.get("eventMemory");
        if (events instanceof Map<?, ?> value) compact.put("eventMemory", compactMap(value, List.of("status", "bias", "samples", "winRate", "averageMovePercent", "reason")));
        return compact;
    }

    private List<Map<String, Object>> compactList(List<?> rows, int limit, List<String> keys) {
        List<Map<String, Object>> compact = new ArrayList<>();
        for (Object row : rows) {
            if (compact.size() >= limit) break;
            if (row instanceof Map<?, ?> map) compact.add(compactMap(map, keys));
            else if (row != null) compact.add(Map.of("value", truncate(String.valueOf(row), 180)));
        }
        return compact;
    }

    private Map<String, Object> compactMap(Map<?, ?> source, List<String> keys) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : keys) {
            if (!source.containsKey(key)) continue;
            Object value = source.get(key);
            compact.put(key, value instanceof String text ? truncate(text, 220) : value);
        }
        return compact;
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

    private int blendedDirectionScore(int technical, int derivatives, int macroNews, int whales, int onChain, int historical, int ai) {
        Map<String, Double> weights = adaptiveWeights();
        return (int) Math.round(
                technical * weights.get("technical")
                        + derivatives * weights.get("derivatives")
                        + macroNews * weights.get("macroNews")
                        + whales * weights.get("whales")
                        + onChain * weights.get("onChain")
                        + historical * weights.get("historical")
                        + ai * weights.get("ai")
        );
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
                                           boolean derivativesAligned, boolean aiQuorumReady, boolean aiAligned, int aiConfidence,
                                           boolean fundingRisk, boolean macroBlocked) {
        List<String> reasons = new ArrayList<>();
        List<String> missing = readiness.entrySet().stream()
                .filter(entry -> !"ai".equals(entry.getKey()))
                .filter(entry -> !String.valueOf(entry.getValue()).startsWith("LIVE"))
                .map(Map.Entry::getKey).toList();
        if (!missing.isEmpty()) reasons.add("Mandatory engines unavailable: " + String.join(", ", missing));
        if (macroBlocked) reasons.add("Macro/news risk is elevated; only tiny learning-scout risk is allowed when AI agrees");
        if (fundingRisk) reasons.add("Funding rate exceeds safety limit");
        if (!aligned) reasons.add("At least 2 of 3 technical timeframes do not agree");
        if (!derivativesAligned) reasons.add("Futures/order-book/CVD confirmation is below learning-scout minimum " + LEARNING_SCOUT_MIN_DERIVATIVES + "%");
        if (!aiQuorumReady) reasons.add("At least 1 live AI is required for learning paper trade verification");
        if (aiQuorumReady && !aiAligned) reasons.add("Live AI verification does not agree with the technical candidate");
        if (aiConfidence < LEARNING_SCOUT_MIN_AI_CONFIDENCE) reasons.add("Winning AI direction confidence is below learning-scout minimum " + LEARNING_SCOUT_MIN_AI_CONFIDENCE + "%");
        if (finalScore < LEARNING_SCOUT_MIN_SCORE) reasons.add("Weighted all-engine score is " + finalScore + "% (learning-scout minimum " + LEARNING_SCOUT_MIN_SCORE + "%)");
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
        learnedWeightsAt = 0;
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
        for (String engine : List.of("technical", "derivatives", "macroNews", "whales", "onChain", "historical", "ai")) stats.put(engine, new double[3]);
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
        Map<String, Double> activeWeights = calculateAdaptiveWeights(closed);
        Map<String, Object> engines = new LinkedHashMap<>();
        stats.forEach((name, value) -> engines.put(name, Map.of(
                "samples", (int) value[2],
                "averageEntryScore", value[2] == 0 ? 0 : Math.round(value[0] / value[2]),
                "profitableScoreContribution", value[2] == 0 ? 0 : Math.round(value[1] / value[2]),
                "reliabilityPercent", engineReliability(closed, name),
                "baseWeightPercent", Math.round(BASE_WEIGHTS.get(name) * 1000.0) / 10.0,
                "activeWeightPercent", Math.round(activeWeights.get(name) * 1000.0) / 10.0,
                "learningActive", closed.size() >= MIN_LEARNING_SAMPLES
        )));
        return Map.of("closedTradeSamples", closed.size(), "minimumSamplesForWeightAdjustment", MIN_LEARNING_SAMPLES, "engines", engines,
                "learningActive", closed.size() >= MIN_LEARNING_SAMPLES,
                "policy", "After 20 closed paper trades, engine weights adapt within safe bounded limits; risk thresholds and paper-only mode remain fixed.");
    }

    private synchronized Map<String, Double> adaptiveWeights() {
        if (System.currentTimeMillis() - learnedWeightsAt < LEARNING_CACHE_MS) return learnedWeights;
        List<CryptoPaperTrade> closed = paperTradeRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(65)).stream()
                .filter(t -> ENGINE_VERSION.equals(t.getEngineVersion()))
                .filter(t -> Set.of("PROFIT", "LOSS").contains(t.getStatus()))
                .toList();
        learnedWeights = calculateAdaptiveWeights(closed);
        learnedWeightsAt = System.currentTimeMillis();
        return learnedWeights;
    }

    private Map<String, Double> calculateAdaptiveWeights(List<CryptoPaperTrade> closed) {
        if (closed.size() < MIN_LEARNING_SAMPLES) return BASE_WEIGHTS;
        Map<String, Double> raw = new LinkedHashMap<>();
        double total = 0;
        for (String engine : BASE_WEIGHTS.keySet()) {
            double reliability = engineReliability(closed, engine) / 100.0;
            double multiplier = Math.max(0.75, Math.min(1.25, 0.75 + reliability * 0.5));
            double adjusted = BASE_WEIGHTS.get(engine) * multiplier;
            raw.put(engine, adjusted);
            total += adjusted;
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : raw.entrySet()) normalized.put(entry.getKey(), entry.getValue() / total);
        return Collections.unmodifiableMap(normalized);
    }

    private long engineReliability(List<CryptoPaperTrade> closed, String engine) {
        double correctStrength = 0;
        int samples = 0;
        for (CryptoPaperTrade trade : closed) {
            try {
                Map<?, ?> evidence = objectMapper.readValue(trade.getDecisionEvidence(), Map.class);
                if (!(evidence.get("engineScores") instanceof Map<?, ?> scores)) continue;
                double entryScore = toDouble(scores.get(engine));
                correctStrength += "PROFIT".equals(trade.getStatus()) ? entryScore : 100 - entryScore;
                samples++;
            } catch (Exception ignored) { }
        }
        return samples == 0 ? 50 : Math.round(correctStrength / samples);
    }

    private Map<String, Object> buildTwoMonthReport(List<CryptoPaperTrade> trades) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(60);
        List<CryptoPaperTrade> period = trades.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(cutoff))
                .toList();
        List<CryptoPaperTrade> closed = period.stream().filter(t -> Set.of("PROFIT", "LOSS").contains(t.getStatus())).toList();
        long wins = closed.stream().filter(t -> "PROFIT".equals(t.getStatus())).count();
        long losses = closed.size() - wins;
        double netPnl = closed.stream().mapToDouble(t -> orDefault(t.getPnl(), 0)).sum();
        double grossProfit = closed.stream().mapToDouble(t -> Math.max(0, orDefault(t.getPnl(), 0))).sum();
        double grossLoss = Math.abs(closed.stream().mapToDouble(t -> Math.min(0, orDefault(t.getPnl(), 0))).sum());

        Map<LocalDate, List<CryptoPaperTrade>> byDay = new TreeMap<>(Comparator.reverseOrder());
        for (CryptoPaperTrade trade : period) byDay.computeIfAbsent(trade.getCreatedAt().toLocalDate(), ignored -> new ArrayList<>()).add(trade);
        List<Map<String, Object>> daily = byDay.entrySet().stream().map(entry -> periodRow(entry.getKey().toString(), entry.getValue())).toList();

        Map<String, List<CryptoPaperTrade>> byMonth = new TreeMap<>(Comparator.reverseOrder());
        for (CryptoPaperTrade trade : period) {
            String month = trade.getCreatedAt().getYear() + "-" + String.format("%02d", trade.getCreatedAt().getMonthValue());
            byMonth.computeIfAbsent(month, ignored -> new ArrayList<>()).add(trade);
        }
        List<Map<String, Object>> monthly = byMonth.entrySet().stream().map(entry -> periodRow(entry.getKey(), entry.getValue())).toList();
        List<CryptoPaperTrade> last7Days = period.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(LocalDateTime.now().minusDays(7)))
                .toList();
        List<CryptoPaperTrade> last30Days = period.stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(LocalDateTime.now().minusDays(30)))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", 60);
        result.put("totalTrades", period.size());
        result.put("closedTrades", closed.size());
        result.put("runningTrades", period.size() - closed.size());
        result.put("wins", wins);
        result.put("losses", losses);
        result.put("winRate", closed.isEmpty() ? 0 : Math.round(wins * 1000.0 / closed.size()) / 10.0);
        result.put("netPnl", netPnl);
        result.put("averagePnl", closed.isEmpty() ? 0 : netPnl / closed.size());
        result.put("profitFactor", grossLoss == 0 ? (grossProfit > 0 ? 999 : 0) : grossProfit / grossLoss);
        result.put("activeDays", byDay.size());
        result.put("daily", daily);
        result.put("monthly", monthly);
        result.put("weeklySummary", periodRow("LAST_7_DAYS", last7Days));
        result.put("monthlySummary", periodRow("LAST_30_DAYS", last30Days));
        result.put("learningStartsAfterClosedTrades", MIN_LEARNING_SAMPLES);
        return result;
    }

    private Map<String, Object> periodRow(String period, List<CryptoPaperTrade> trades) {
        List<CryptoPaperTrade> closed = trades.stream().filter(t -> Set.of("PROFIT", "LOSS").contains(t.getStatus())).toList();
        long wins = closed.stream().filter(t -> "PROFIT".equals(t.getStatus())).count();
        double pnl = closed.stream().mapToDouble(t -> orDefault(t.getPnl(), 0)).sum();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", period);
        row.put("trades", trades.size());
        row.put("closed", closed.size());
        row.put("wins", wins);
        row.put("losses", closed.size() - wins);
        row.put("winRate", closed.isEmpty() ? 0 : Math.round(wins * 1000.0 / closed.size()) / 10.0);
        row.put("pnl", pnl);
        return row;
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
