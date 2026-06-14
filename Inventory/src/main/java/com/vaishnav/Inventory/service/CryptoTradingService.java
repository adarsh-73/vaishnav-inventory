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
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class CryptoTradingService {

    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT");
    private static final List<String> AI_ENGINES = List.of("ChatGPT", "Gemini", "DeepSeek", "Claude", "Risk AI");
    private static final int INDICATOR_COUNT = 100;
    private static final int MAX_DAILY_PAPER_TRADES = 5;
    private static final long CMC_CACHE_SECONDS = 60;
    private static final long FUTURES_INTEL_CACHE_SECONDS = 60;
    private static final long EXTERNAL_FEED_CACHE_SECONDS = 300;

    private volatile Map<String, Map<String, Object>> cachedMarketPrices;
    private volatile Instant cachedMarketPricesAt;
    private volatile Map<String, Map<String, Object>> cachedFuturesIntel;
    private volatile Instant cachedFuturesIntelAt;
    private volatile Map<String, Object> cachedExternalFeeds;
    private volatile Instant cachedExternalFeedsAt;

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
        Map<String, Object> macroStatus = fetchMacroStatus();
        Map<String, Object> externalFeeds = fetchExternalFeeds(marketPrices, futuresIntel, macroStatus);
        List<Map<String, Object>> signals = SYMBOLS.stream().map(symbol -> buildSignal(symbol, marketPrices, futuresIntel, macroStatus, externalFeeds)).toList();
        cleanupBadFallbackTrades(marketPrices);
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
        response.put("intelligence", buildMarketIntelligence(marketPrices, futuresIntel, signals, macroStatus, externalFeeds));
        response.put("report", report);
        response.put("openTrades", paperTradeRepository.findByStatus("RUNNING"));
        response.put("liveTrades", buildLiveTrades(marketPrices));
        response.put("recentTrades", trades.stream().sorted(Comparator.comparing(CryptoPaperTrade::getId).reversed()).limit(25).toList());
        response.put("safetyRules", safetyRules());
        response.put("binance", cryptoExchangeService.testnetClientStatus());
        return response;
    }

    private List<Map<String, Object>> buildLiveTrades(Map<String, Map<String, Object>> marketPrices) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CryptoPaperTrade trade : paperTradeRepository.findByStatus("RUNNING")) {
            Map<String, Object> market = marketPrices.getOrDefault(trade.getSymbol(), fallbackPrice(trade.getSymbol()));
            double currentPrice = asDouble(market.get("price"));
            double entry = trade.getEntryPrice() == null ? currentPrice : trade.getEntryPrice();
            double quantity = trade.getQuantity() == null ? 0 : trade.getQuantity();
            double direction = "SHORT".equals(trade.getSide()) ? -1 : 1;
            double unrealizedPnl = currentPrice > 0 ? (currentPrice - entry) * quantity * direction : 0;
            double pnlPercent = entry > 0 ? ((currentPrice - entry) / entry) * 100 * direction : 0;
            double stopDistancePercent = currentPrice > 0
                    ? Math.abs(currentPrice - asDouble(trade.getStopLoss())) * 100 / currentPrice
                    : 0;
            double targetDistancePercent = currentPrice > 0
                    ? Math.abs(asDouble(trade.getTakeProfit()) - currentPrice) * 100 / currentPrice
                    : 0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", trade.getId());
            row.put("symbol", trade.getSymbol());
            row.put("side", trade.getSide());
            row.put("status", trade.getStatus());
            row.put("timeframe", trade.getTimeframe());
            row.put("entryPrice", entry);
            row.put("currentPrice", currentPrice);
            row.put("stopLoss", trade.getStopLoss());
            row.put("takeProfit", trade.getTakeProfit());
            row.put("trailingStop", trade.getTrailingStop());
            row.put("quantity", quantity);
            row.put("unrealizedPnl", round(unrealizedPnl));
            row.put("unrealizedPnlPercent", round(pnlPercent));
            row.put("stopDistancePercent", round(stopDistancePercent));
            row.put("targetDistancePercent", round(targetDistancePercent));
            row.put("confidence", trade.getConfidence());
            row.put("finalScore", trade.getFinalScore());
            row.put("riskReward", trade.getRiskReward());
            row.put("aiConsensus", trade.getAiConsensus());
            row.put("technicalSummary", trade.getTechnicalSummary());
            row.put("newsRisk", trade.getNewsRisk());
            row.put("priceSource", market.get("source"));
            row.put("lastUpdated", market.get("lastUpdated"));
            row.put("openedAt", trade.getOpenedAt());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> buildMarketIntelligence(Map<String, Map<String, Object>> marketPrices,
                                                        Map<String, Map<String, Object>> futuresIntel,
                                                        List<Map<String, Object>> signals,
                                                        Map<String, Object> macroStatus,
                                                        Map<String, Object> externalFeeds) {
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
        intelligence.put("fakeNewsFilter", "Trade only after verified-source + price/volume confirmation. Telegram/social claims are not trusted alone.");
        intelligence.put("newsFeed", nestedValue(externalFeeds, "news", "status"));
        intelligence.put("whaleFeed", nestedValue(externalFeeds, "whales", "status"));
        intelligence.put("etfFeed", nestedValue(externalFeeds, "etf", "status"));
        intelligence.put("macro", macroStatus);
        intelligence.put("topWhales", nestedValue(externalFeeds, "whales", "items"));
        intelligence.put("freeWhaleSources", nestedValue(externalFeeds, "whales", "freeSources"));
        intelligence.put("verifiedNews", nestedValue(externalFeeds, "news", "items"));
        intelligence.put("etfFlows", nestedValue(externalFeeds, "etf", "items"));
        intelligence.put("smartMoneyScore", externalFeeds.get("smartMoneyScore"));
        intelligence.put("externalRisk", externalFeeds.get("externalRisk"));
        intelligence.put("symbols", futuresIntel);
        intelligence.put("rule", "Trade only when whale/on-chain + derivatives + technicals + macro agree. News is only a risk filter, not the main signal.");
        return intelligence;
    }

    private Map<String, Object> fetchMacroStatus() {
        Map<String, Object> macro = new LinkedHashMap<>();
        macro.put("sp500", hasEnv("TWELVEDATA_API_KEY") || hasEnv("ALPHAVANTAGE_API_KEY") ? "API_READY" : "Add TWELVEDATA_API_KEY or ALPHAVANTAGE_API_KEY for S&P 500 confirmation.");
        macro.put("dxy", hasEnv("TWELVEDATA_API_KEY") || hasEnv("ALPHAVANTAGE_API_KEY") ? "API_READY" : "Add macro API for DXY risk filter.");
        macro.put("macroRisk", "UNKNOWN");
        macro.put("rule", "Crypto longs are filtered harder when S&P 500 risk-off or DXY strong.");
        String alphaKey = System.getenv("ALPHAVANTAGE_API_KEY");
        if (alphaKey == null || alphaKey.isBlank()) return macro;

        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<?, ?> spy = restTemplate.getForObject(
                    "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=SPY&apikey=" + alphaKey,
                    Map.class
            );
            Map<?, ?> uup = restTemplate.getForObject(
                    "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=UUP&apikey=" + alphaKey,
                    Map.class
            );
            double spyChange = parsePercent(globalQuoteValue(spy, "10. change percent"));
            double uupChange = parsePercent(globalQuoteValue(uup, "10. change percent"));
            macro.put("sp500", "CONNECTED");
            macro.put("sp500Proxy", "SPY");
            macro.put("sp500ChangePercent", round(spyChange));
            macro.put("dxy", "CONNECTED");
            macro.put("dxyProxy", "UUP");
            macro.put("dxyChangePercent", round(uupChange));
            macro.put("macroRisk", spyChange <= -1.0 || uupChange >= 0.8 ? "HIGH" : spyChange <= -0.35 || uupChange >= 0.35 ? "MEDIUM" : "NORMAL");
            macro.put("rule", "SPY down or UUP/DXY proxy strong means crypto longs need stronger confirmation.");
        } catch (Exception error) {
            macro.put("macroRisk", "UNKNOWN");
            macro.put("warning", "AlphaVantage macro fetch failed. Check ALPHAVANTAGE_API_KEY.");
        }
        return macro;
    }

    private List<Map<String, Object>> buildWhaleStatus() {
        if (!hasEnv("WHALE_ALERT_API_KEY") && !hasEnv("GLASSNODE_API_KEY") && !hasEnv("CRYPTOQUANT_API_KEY")) {
            return List.of(Map.of(
                    "status", "API_KEY_REQUIRED",
                    "message", "200+ whale wallet/exchange-flow tracking needs WHALE_ALERT_API_KEY / GLASSNODE_API_KEY / CRYPTOQUANT_API_KEY. Free mode uses Binance large-flow proxy plus manual Arkham/Lookonchain/Whale Alert watchlist."
            ));
        }

        return List.of(Map.of(
                "status", "READY_FOR_PROVIDER",
                "message", "Whale/on-chain API key detected. Provider feed wiring can track 200+ whale transfers, exchange reserves and inflow/outflow."
        ));
    }

    private Map<String, Object> fetchExternalFeeds(Map<String, Map<String, Object>> marketPrices,
                                                   Map<String, Map<String, Object>> futuresIntel,
                                                   Map<String, Object> macroStatus) {
        Map<String, Object> cached = cachedExternalFeeds;
        if (cached != null
                && cachedExternalFeedsAt != null
                && Instant.now().minusSeconds(EXTERNAL_FEED_CACHE_SECONDS).isBefore(cachedExternalFeedsAt)) {
            return cached;
        }

        Map<String, Object> news = fetchCryptoPanicNews();
        Map<String, Object> whales = buildWhaleFlowFeed(marketPrices, futuresIntel);
        Map<String, Object> etf = fetchEtfProxyFeed();
        int smartMoneyScore = calculateSmartMoneyScore(whales, etf, news, macroStatus);
        String externalRisk = smartMoneyScore <= 38 || "HIGH".equals(news.get("risk")) ? "HIGH"
                : smartMoneyScore <= 55 || "MEDIUM".equals(news.get("risk")) ? "MEDIUM"
                : "NORMAL";

        Map<String, Object> feeds = new LinkedHashMap<>();
        feeds.put("news", news);
        feeds.put("whales", whales);
        feeds.put("etf", etf);
        feeds.put("smartMoneyScore", smartMoneyScore);
        feeds.put("externalRisk", externalRisk);
        feeds.put("sourceNote", "Primary focus: Binance futures, CMC market structure, whale/exchange-flow proxy, ETF/macro proxy. News is low-weight risk filter only.");
        cachedExternalFeeds = feeds;
        cachedExternalFeedsAt = Instant.now();
        return feeds;
    }

    private Map<String, Object> fetchCryptoPanicNews() {
        Map<String, Object> feed = new LinkedHashMap<>();
        String apiKey = System.getenv("CRYPTOPANIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return fetchFreeNewsFeed();
        }

        try {
            Map<?, ?> response = new RestTemplate().getForObject(
                    "https://cryptopanic.com/api/v1/posts/?auth_token=" + apiKey + "&currencies=BTC,ETH,SOL,BNB&filter=hot&public=true",
                    Map.class
            );
            Object resultsObj = response == null ? null : response.get("results");
            List<Map<String, Object>> items = new ArrayList<>();
            int danger = 0;
            int positive = 0;
            if (resultsObj instanceof List<?> results) {
                for (Object itemObj : results.stream().limit(8).toList()) {
                    if (!(itemObj instanceof Map<?, ?> item)) continue;
                    String title = String.valueOf(item.containsKey("title") ? item.get("title") : "");
                    String domain = item.get("domain") == null ? "CryptoPanic" : String.valueOf(item.get("domain"));
                    String sentiment = classifyNewsTitle(title);
                    if ("RISK_OFF".equals(sentiment)) danger++;
                    if ("RISK_ON".equals(sentiment)) positive++;
                    items.add(Map.of(
                            "title", title,
                            "source", domain,
                            "sentiment", sentiment,
                            "url", String.valueOf(item.containsKey("url") ? item.get("url") : "")
                    ));
                }
            }
            feed.put("status", "CONNECTED");
            feed.put("risk", danger >= 2 ? "HIGH" : danger > positive ? "MEDIUM" : "NORMAL");
            feed.put("items", items);
            feed.put("rule", "Negative macro/regulation/hack/war/liquidation news raises no-trade probability.");
            return feed;
        } catch (Exception error) {
            feed.put("status", "FETCH_FAILED");
            feed.put("risk", "UNKNOWN");
            feed.put("items", List.of(Map.of("title", "CryptoPanic fetch failed. Check API key/quota.", "source", "SYSTEM", "sentiment", "WAITING")));
            return feed;
        }
    }

    private Map<String, Object> fetchFreeNewsFeed() {
        Map<String, Object> feed = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            items.addAll(fetchGoogleNewsItems("site:coindesk.com (bitcoin OR ethereum OR solana OR bnb OR crypto) (ETF OR Fed OR CPI OR SEC OR hack OR liquidity)", "CoinDesk"));
            items.addAll(fetchGoogleNewsItems("site:theblock.co (bitcoin OR ethereum OR solana OR bnb OR crypto OR ETF)", "The Block"));
            items.addAll(fetchGoogleNewsItems("site:cointelegraph.com (bitcoin OR ethereum OR solana OR bnb OR crypto)", "Cointelegraph"));
            items.addAll(fetchGoogleNewsItems("site:decrypt.co (bitcoin OR ethereum OR solana OR bnb OR crypto)", "Decrypt"));
            items = dedupeNews(items).stream().limit(10).toList();
            int danger = 0;
            int positive = 0;
            for (Map<String, Object> item : items) {
                String sentiment = String.valueOf(item.get("sentiment"));
                if ("RISK_OFF".equals(sentiment)) danger++;
                if ("RISK_ON".equals(sentiment)) positive++;
            }
            feed.put("status", "FREE_VERIFIED_RSS_RISK_FILTER");
            feed.put("risk", danger >= 2 ? "HIGH" : danger > positive ? "MEDIUM" : "NORMAL");
            feed.put("items", items);
            feed.put("rule", "News is low-weight only: CoinDesk, The Block, Cointelegraph and Decrypt are used to block risky entries, not to force trades.");
            return feed;
        } catch (Exception error) {
            feed.put("status", "FREE_NEWS_FETCH_FAILED");
            feed.put("risk", "UNKNOWN");
            feed.put("items", List.of(Map.of(
                    "title", "Free news fetch failed. System will rely on price/futures/macro only.",
                    "source", "SYSTEM",
                    "sentiment", "WAITING"
            )));
            return feed;
        }
    }

    private List<Map<String, Object>> fetchGoogleNewsItems(String query, String source) throws Exception {
        String encoded = query.replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("|", "%7C");
        String rss = new RestTemplate().getForObject(
                "https://news.google.com/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en",
                String.class
        );
        return parseRssItems(rss, source);
    }

    private List<Map<String, Object>> dedupeNews(List<Map<String, Object>> items) {
        List<Map<String, Object>> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : items) {
            String title = String.valueOf(item.getOrDefault("title", "")).toLowerCase(Locale.ROOT);
            if (title.isBlank() || seen.contains(title)) continue;
            seen.add(title);
            unique.add(item);
        }
        return unique;
    }

    private List<Map<String, Object>> parseRssItems(String rss, String source) throws Exception {
        if (rss == null || rss.isBlank()) return List.of();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        NodeList titles = document.getElementsByTagName("title");
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i < titles.getLength() && items.size() < 8; i++) {
            String title = titles.item(i).getTextContent();
            String sentiment = classifyNewsTitle(title);
            items.add(Map.of(
                    "title", title,
                    "source", source,
                    "sentiment", sentiment
            ));
        }
        return items;
    }

    private Map<String, Object> buildWhaleFlowFeed(Map<String, Map<String, Object>> marketPrices,
                                                   Map<String, Map<String, Object>> futuresIntel) {
        boolean providerReady = hasEnv("WHALE_ALERT_API_KEY") || hasEnv("GLASSNODE_API_KEY") || hasEnv("CRYPTOQUANT_API_KEY");
        List<Map<String, Object>> items = new ArrayList<>();
        for (String symbol : SYMBOLS) {
            Map<String, Object> market = marketPrices.getOrDefault(symbol, fallbackPrice(symbol));
            Map<String, Object> futures = futuresIntel.getOrDefault(symbol, fallbackFuturesIntel(symbol));
            double volume24h = asDouble(market.get("volume24h"));
            double quoteVolume = asDouble(futures.get("quoteVolume24h"));
            double volumeSpike = asDouble(futures.get("volumeSpike"));
            double openInterest = asDouble(futures.get("openInterest"));
            Map<String, Object> largeTrades = fetchBinanceLargeTrades(symbol);
            String direction = asDouble(futures.get("longShortRatio")) >= 1.04 ? "LONG_CROWD" : asDouble(futures.get("longShortRatio")) <= 0.96 ? "SHORT_CROWD" : "BALANCED";
            String severity = volumeSpike >= 2.2 || quoteVolume >= 8_000_000_000D || asDouble(largeTrades.get("largeTrades")) >= 12 ? "HIGH" : volumeSpike >= 1.5 || asDouble(largeTrades.get("largeTrades")) >= 5 ? "MEDIUM" : "NORMAL";
            items.add(Map.of(
                    "asset", symbol.replace("USDT", ""),
                    "flow", String.valueOf(futures.get("whaleProxy")) + " | large trades " + largeTrades.get("largeTrades") + " | $" + largeTrades.get("largeNotional"),
                    "direction", direction,
                    "severity", severity,
                    "volumeSpike", volumeSpike,
                    "largeTrades", largeTrades.get("largeTrades"),
                    "largeNotional", largeTrades.get("largeNotional"),
                    "openInterest", round(openInterest),
                    "volume24h", round(volume24h)
            ));
        }

        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put("status", providerReady ? "PROVIDER_KEY_READY_PLUS_BINANCE_PROXY" : "BINANCE_PROXY_ONLY_ADD_WHALE_KEYS");
        feed.put("items", items);
        feed.put("freeSources", List.of(
                Map.of("name", "Arkham Intelligence", "use", "Manual wallet labels, entity tracking and alerts", "url", "https://intel.arkm.com/"),
                Map.of("name", "Lookonchain", "use", "Smart money public posts and wallet activity", "url", "https://lookonchain.com/"),
                Map.of("name", "Whale Alert", "use", "Large public transfers and exchange movement alerts", "url", "https://whale-alert.io/"),
                Map.of("name", "DeBank", "use", "DeFi whale portfolios and wallet holdings", "url", "https://debank.com/"),
                Map.of("name", "DexScreener", "use", "Early wallet/DEX volume checks for memecoins and alts", "url", "https://dexscreener.com/")
        ));
        feed.put("rule", providerReady
                ? "Provider keys detected; Binance proxy active. Provider-specific wallet/exchange-flow endpoints can be expanded by plan."
                : "Free mode: using Binance futures large trades, volume spike, open interest and long/short ratio. Paid on-chain whale APIs are optional.");
        return feed;
    }

    private Map<String, Object> fetchBinanceLargeTrades(String symbol) {
        double threshold = switch (symbol) {
            case "BTCUSDT" -> 250_000D;
            case "ETHUSDT" -> 120_000D;
            default -> 60_000D;
        };
        try {
            Object response = new RestTemplate().getForObject(
                    "https://fapi.binance.com/fapi/v1/aggTrades?symbol=" + symbol + "&limit=1000",
                    Object.class
            );
            int count = 0;
            double notional = 0;
            if (response instanceof List<?> trades) {
                for (Object tradeObj : trades) {
                    if (!(tradeObj instanceof Map<?, ?> trade)) continue;
                    double price = parseDouble(trade.get("p"));
                    double qty = parseDouble(trade.get("q"));
                    double value = price * qty;
                    if (value >= threshold) {
                        count++;
                        notional += value;
                    }
                }
            }
            return Map.of("largeTrades", count, "largeNotional", round(notional));
        } catch (Exception error) {
            return Map.of("largeTrades", 0, "largeNotional", 0);
        }
    }

    private Map<String, Object> fetchEtfProxyFeed() {
        Map<String, Object> feed = new LinkedHashMap<>();
        String alphaKey = System.getenv("ALPHAVANTAGE_API_KEY");
        if (alphaKey == null || alphaKey.isBlank()) {
            feed.put("status", "API_KEY_REQUIRED");
            feed.put("items", List.of(Map.of(
                    "asset", "BTC/ETH/SOL/BNB ETF/Macro",
                    "flow", "Add ALPHAVANTAGE_API_KEY for ETF/macro proxy quotes.",
                    "signal", "WAITING"
            )));
            return feed;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (String symbol : List.of("IBIT", "FBTC", "ETHA", "ETHE")) {
            try {
                Map<?, ?> quote = new RestTemplate().getForObject(
                        "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" + symbol + "&apikey=" + alphaKey,
                        Map.class
                );
                double change = parsePercent(globalQuoteValue(quote, "10. change percent"));
                String signal = change >= 1 ? "INFLOW_PROXY" : change <= -1 ? "OUTFLOW_PROXY" : "NEUTRAL";
                items.add(Map.of(
                        "asset", symbol,
                        "changePercent", round(change),
                        "signal", signal,
                        "flow", "ETF price/volume proxy; exact daily net flow needs dedicated ETF-flow provider."
                ));
            } catch (Exception ignored) {
                items.add(Map.of("asset", symbol, "signal", "FETCH_FAILED", "flow", "Check AlphaVantage quota/key."));
            }
        }
        feed.put("status", "CONNECTED_PROXY");
        feed.put("items", items);
        feed.put("rule", "ETF proxy is used as confirmation only, not standalone entry.");
        return feed;
    }

    private int calculateSmartMoneyScore(Map<String, Object> whales,
                                         Map<String, Object> etf,
                                         Map<String, Object> news,
                                         Map<String, Object> macroStatus) {
        int score = 62;
        String newsRisk = String.valueOf(news.getOrDefault("risk", "UNKNOWN"));
        String macroRisk = String.valueOf(macroStatus.getOrDefault("macroRisk", "UNKNOWN"));
        if ("HIGH".equals(newsRisk)) score -= 22;
        else if ("MEDIUM".equals(newsRisk)) score -= 10;
        if ("HIGH".equals(macroRisk)) score -= 18;
        else if ("MEDIUM".equals(macroRisk)) score -= 8;

        Object whaleItemsObj = whales.get("items");
        if (whaleItemsObj instanceof List<?> whaleItems) {
            long high = whaleItems.stream().filter(item -> item instanceof Map<?, ?> map && "HIGH".equals(map.get("severity"))).count();
            if (high > 0) score += 8;
        }

        Object etfItemsObj = etf.get("items");
        if (etfItemsObj instanceof List<?> etfItems) {
            long inflow = etfItems.stream().filter(item -> item instanceof Map<?, ?> map && "INFLOW_PROXY".equals(map.get("signal"))).count();
            long outflow = etfItems.stream().filter(item -> item instanceof Map<?, ?> map && "OUTFLOW_PROXY".equals(map.get("signal"))).count();
            score += (int) Math.min(10, inflow * 3);
            score -= (int) Math.min(10, outflow * 3);
        }
        return Math.max(0, Math.min(100, score));
    }

    public List<CryptoPaperTrade> runPaperScan() {
        List<CryptoPaperTrade> created = new ArrayList<>();
        Map<String, Map<String, Object>> marketPrices = fetchCoinMarketCapPrices();
        Map<String, Map<String, Object>> futuresIntel = fetchFuturesIntelligence();
        Map<String, Object> macroStatus = fetchMacroStatus();
        Map<String, Object> externalFeeds = fetchExternalFeeds(marketPrices, futuresIntel, macroStatus);
        List<Map<String, Object>> signals = SYMBOLS.stream().map(symbol -> buildSignal(symbol, marketPrices, futuresIntel, macroStatus, externalFeeds)).toList();
        cleanupBadFallbackTrades(marketPrices);
        evaluateRunningTrades(marketPrices, signals);

        long runningCount = paperTradeRepository.findByStatus("RUNNING").size();
        if (runningCount >= 1) return created;
        long todayTrades = paperTradeRepository.findByCreatedAtAfter(java.time.LocalDate.now().atStartOfDay()).stream()
                .filter(trade -> !"CANCELLED_BAD_DATA".equals(trade.getStatus()))
                .count();
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

    public Map<String, Object> cleanupBadFallbackTrades() {
        Map<String, Map<String, Object>> marketPrices = fetchCoinMarketCapPrices();
        int cancelled = cleanupBadFallbackTrades(marketPrices);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cancelled", cancelled);
        response.put("status", "BAD_FALLBACK_TRADES_CLEANED");
        return response;
    }

    private int cleanupBadFallbackTrades(Map<String, Map<String, Object>> marketPrices) {
        int cancelled = 0;
        List<CryptoPaperTrade> trades = paperTradeRepository.findAll();
        for (CryptoPaperTrade trade : trades) {
            if (!"RUNNING".equals(trade.getStatus()) && !"PROFIT".equals(trade.getStatus()) && !"LOSS".equals(trade.getStatus())) {
                continue;
            }
            Map<String, Object> market = marketPrices.get(trade.getSymbol());
            if (market == null || !"COINMARKETCAP".equals(market.get("source"))) continue;
            double currentPrice = asDouble(market.get("price"));
            double entry = trade.getEntryPrice() == null ? 0 : trade.getEntryPrice();
            double mismatch = currentPrice > 0 ? Math.abs(entry - currentPrice) / currentPrice : 0;
            if (mismatch <= 0.25) continue;

            trade.setExitPrice(currentPrice);
            trade.setPnl(0.0);
            trade.setStatus("CANCELLED_BAD_DATA");
            trade.setCloseReason("Cancelled stale fallback trade. Entry did not match live CoinMarketCap price.");
            trade.setClosedAt(LocalDateTime.now());
            paperTradeRepository.save(trade);
            cancelled++;
        }
        return cancelled;
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
        Map<String, Map<String, Object>> marketPrices = fetchCoinMarketCapPrices();
        List<CryptoPaperTrade> runningTrades = paperTradeRepository.findByStatus("RUNNING");
        for (CryptoPaperTrade trade : runningTrades) {
            Map<String, Object> market = marketPrices.get(trade.getSymbol());
            if (market == null || !"COINMARKETCAP".equals(market.get("source"))) {
                continue;
            }
            double exitPrice = asDouble(market.get("price"));
            double entry = trade.getEntryPrice() == null ? exitPrice : trade.getEntryPrice();
            double mismatch = exitPrice > 0 ? Math.abs(entry - exitPrice) / exitPrice : 0;
            if (mismatch > 0.25) {
                trade.setExitPrice(exitPrice);
                trade.setPnl(0.0);
                trade.setStatus("CANCELLED_BAD_DATA");
                trade.setCloseReason("Cancelled stale fallback entry during manual close.");
                trade.setClosedAt(LocalDateTime.now());
                paperTradeRepository.save(trade);
                continue;
            }
            double direction = "LONG".equals(trade.getSide()) ? 1 : -1;
            double quantity = trade.getQuantity() == null ? 0 : trade.getQuantity();
            double pnl = (exitPrice - entry) * quantity * direction;
            trade.setExitPrice(exitPrice);
            trade.setPnl(pnl);
            trade.setStatus(pnl >= 0 ? "PROFIT" : "LOSS");
            trade.setCloseReason(pnl >= 0 ? "Manual close at live CoinMarketCap price" : "Manual risk close at live CoinMarketCap price");
            trade.setClosedAt(LocalDateTime.now());
            paperTradeRepository.save(trade);
        }
        return runningTrades;
    }

    private Map<String, Object> buildSignal(String symbol,
                                            Map<String, Map<String, Object>> marketPrices,
                                            Map<String, Map<String, Object>> futuresIntel,
                                            Map<String, Object> macroStatus,
                                            Map<String, Object> externalFeeds) {
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
        String macroRisk = String.valueOf(macroStatus.getOrDefault("macroRisk", "UNKNOWN"));
        String externalRisk = String.valueOf(externalFeeds.getOrDefault("externalRisk", "UNKNOWN"));
        int smartMoneyScore = (int) asDouble(externalFeeds.get("smartMoneyScore"));
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
        String newsRisk = Math.abs(change1h) >= 3.5 || Math.abs(change24h) >= 9 || "HIGH".equals(liquidationRisk) || "HIGH".equals(macroRisk) || "HIGH".equals(externalRisk) ? "HIGH" : "NORMAL";
        int volumeScore = liveCmc
                ? (int) Math.round(Math.max(45, Math.min(92, 55 + Math.log10(Math.max(1, volume24h)) * 3.2)))
                : 50;
        Map<String, Object> categoryScores = buildCategoryScores(symbol, marketPrice, futures, indicators, macroStatus, externalFeeds,
                technicalSignal, technicalScore, futuresScore, volumeScore, smartMoneyScore, newsRisk);
        String whaleDirection = String.valueOf(categoryScores.get("whaleDirection"));
        String derivativesDirection = String.valueOf(categoryScores.get("derivativesDirection"));
        String technicalDirection = String.valueOf(categoryScores.get("technicalDirection"));
        boolean categoryAligned = !"NO_TRADE".equals(technicalDirection)
                && technicalDirection.equals(whaleDirection)
                && technicalDirection.equals(derivativesDirection);

        List<Map<String, Object>> aiVotes = liveCmc
                ? AI_ENGINES.stream().map(ai -> aiVote(ai, symbol, technicalSignal, technicalScore)).toList()
                : AI_ENGINES.stream().map(ai -> noTradeAiVote(ai)).toList();
        long longVotes = aiVotes.stream().filter(vote -> "LONG".equals(vote.get("signal"))).count();
        long shortVotes = aiVotes.stream().filter(vote -> "SHORT".equals(vote.get("signal"))).count();
        String finalSignal = !liveCmc || longVotes == shortVotes ? "NO_TRADE" : longVotes > shortVotes ? "LONG" : "SHORT";
        long alignedFrames = countMatchingSignals(timeframes, finalSignal);
        double confidence = averageVoteConfidence(aiVotes, finalSignal);
        Map<String, Object> aiDecision = buildAiDecision(symbol, marketPrice, futures, timeframes, technicalSignal,
                technicalScore, futuresScore, volumeScore, newsRisk, liquidationRisk, macroStatus, externalFeeds, categoryScores);
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
        double macroScore = "HIGH".equals(macroRisk) ? 25 : "MEDIUM".equals(macroRisk) ? 58 : "NORMAL".equals(macroRisk) ? 85 : 65;
        double whaleScore = asDouble(categoryScores.get("whaleScore"));
        double onchainScore = asDouble(categoryScores.get("onchainScore"));
        double derivativesScore = asDouble(categoryScores.get("derivativesScore"));
        double weightedTechnicalScore = asDouble(categoryScores.get("technicalScore"));
        double sentimentScore = asDouble(categoryScores.get("sentimentScore"));
        double finalScore = Math.round(whaleScore * 0.30
                + onchainScore * 0.25
                + derivativesScore * 0.25
                + weightedTechnicalScore * 0.15
                + sentimentScore * 0.05);
        String signalGrade = finalScore >= 80 ? "STRONG"
                : finalScore >= 70 ? "WATCHLIST"
                : finalScore >= 60 ? "WEAK"
                : "NO_TRADE";
        boolean allowed = liveCmc
                && liveFutures
                && finalScore >= 80
                && confidence >= 75
                && alignedFrames >= 2
                && categoryAligned
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
        signal.put("signalGrade", signalGrade);
        signal.put("categoryScores", categoryScores);
        signal.put("categoryAligned", categoryAligned);
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
        signal.put("macroStatus", macroStatus);
        signal.put("externalFeeds", externalFeeds);
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
        signal.put("scoreFormula", "Whale 30% + On-chain 25% + Derivatives 25% + Technical 15% + Macro/News 5%");
        signal.put("technicalSummary", technicalSignal + " | RSI=" + indicators.get("rsi14") + " | MA trend=" + indicators.get("maTrend") + " | MACD=" + indicators.get("macdSignal") + " | Whale=" + Math.round(whaleScore) + " " + whaleDirection + " | On-chain=" + Math.round(onchainScore) + " | Derivatives=" + Math.round(derivativesScore) + " " + derivativesDirection + " | Technical=" + Math.round(weightedTechnicalScore) + " " + technicalDirection + " | Sentiment=" + Math.round(sentimentScore) + " | Grade=" + signalGrade + " | RR=" + riskReward);
        signal.put("newsRisk", newsRisk);
        signal.put("blockReason", allowed ? "" : blockReason(liveCmc, confidence, alignedFrames, newsRisk, finalScore, finalSignal, categoryAligned));
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
                    "https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=BTC,ETH,SOL,BNB&convert=USD",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Object dataObj = response.getBody() == null ? null : response.getBody().get("data");
            if (!(dataObj instanceof Map<?, ?> data)) return prices;

            for (String coin : List.of("BTC", "ETH", "SOL", "BNB")) {
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

    private Map<String, Object> buildCategoryScores(String symbol,
                                                    Map<String, Object> marketPrice,
                                                    Map<String, Object> futures,
                                                    Map<String, Object> indicators,
                                                    Map<String, Object> macroStatus,
                                                    Map<String, Object> externalFeeds,
                                                    String technicalSignal,
                                                    int technicalScore,
                                                    int futuresScore,
                                                    int volumeScore,
                                                    int smartMoneyScore,
                                                    String newsRisk) {
        double change1h = asDouble(marketPrice.get("percentChange1h"));
        double change24h = asDouble(marketPrice.get("percentChange24h"));
        double change7d = asDouble(marketPrice.get("percentChange7d"));
        double fundingRate = asDouble(futures.get("fundingRate"));
        double longShortRatio = asDouble(futures.get("longShortRatio"));
        double volumeSpike = asDouble(futures.get("volumeSpike"));
        double openInterest = asDouble(futures.get("openInterest"));
        String macroRisk = String.valueOf(macroStatus.getOrDefault("macroRisk", "UNKNOWN"));
        String externalRisk = String.valueOf(externalFeeds.getOrDefault("externalRisk", "UNKNOWN"));

        double whaleScore = clampScore(48
                + smartMoneyScore * 0.28
                + (volumeSpike >= 2 ? 12 : volumeSpike >= 1.4 ? 6 : 0)
                + (openInterest > 0 ? 4 : 0)
                + (longShortRatio >= 1.05 ? 5 : longShortRatio <= 0.95 ? -5 : 0));
        double onchainScore = clampScore(50
                + change7d * 1.1
                + change24h * 1.6
                + (volumeScore - 50) * 0.35
                + ("HIGH".equals(externalRisk) ? -12 : "MEDIUM".equals(externalRisk) ? -5 : 4));
        double derivativesScore = clampScore(futuresScore
                + (Math.abs(fundingRate) < 0.0004 ? 5 : -6)
                + (volumeSpike >= 1.5 ? 5 : 0)
                + (longShortRatio >= 1.08 ? 4 : longShortRatio <= 0.92 ? -4 : 0));
        double scoreFromIndicators = asDouble(indicators.get("score"));
        double weightedTechnicalScore = clampScore(technicalScore * 0.55 + scoreFromIndicators * 0.45);
        double sentimentScore = clampScore(62
                + ("HIGH".equals(newsRisk) ? -24 : "NORMAL".equals(newsRisk) ? 7 : 0)
                + ("HIGH".equals(macroRisk) ? -20 : "MEDIUM".equals(macroRisk) ? -8 : "NORMAL".equals(macroRisk) ? 8 : 0));

        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("formula", "Whale 30% + On-chain 25% + Derivatives 25% + Technical 15% + Macro/News 5%");
        scores.put("whaleScore", round(whaleScore));
        scores.put("onchainScore", round(onchainScore));
        scores.put("derivativesScore", round(derivativesScore));
        scores.put("technicalScore", round(weightedTechnicalScore));
        scores.put("sentimentScore", round(sentimentScore));
        scores.put("whaleDirection", directionFromScore(whaleScore, change24h, longShortRatio));
        scores.put("onchainDirection", directionFromScore(onchainScore, change7d, 1));
        scores.put("derivativesDirection", directionFromScore(derivativesScore, change1h, longShortRatio));
        scores.put("technicalDirection", "LONG".equals(technicalSignal) || "SHORT".equals(technicalSignal) ? technicalSignal : directionFromScore(weightedTechnicalScore, change24h, 1));
        scores.put("sentimentDirection", sentimentScore >= 60 ? "LONG" : sentimentScore <= 42 ? "SHORT" : "NO_TRADE");
        scores.put("indicators", groupedIndicatorNames());
        scores.put("sources", Map.of(
                "whale", "Binance large trades + volume spike now; Arkham/Lookonchain/Whale Alert/DeBank/DexScreener manual watch; Glassnode/CryptoQuant keys improve it",
                "onchain", "Exchange reserve, netflow, active addresses, MVRV, SOPR, NUPL, realized price and miner reserve need Glassnode/CryptoQuant keys",
                "derivatives", "Binance funding, open interest, long/short, liquidation proxy, taker flow proxy, futures premium and basis",
                "technical", "RSI, MACD, EMA 20/50/200, VWAP, Bollinger Bands, ATR, ADX, Supertrend",
                "sentiment", "BTC dominance, Fear & Greed, DXY, yields, ETF flow, stablecoin market cap; news stays low-weight risk filter"
        ));
        return scores;
    }

    private double clampScore(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private String directionFromScore(double score, double momentum, double ratio) {
        if (score >= 62 && momentum >= -0.15 && ratio >= 0.97) return "LONG";
        if (score <= 42 || (momentum <= -0.25 && ratio <= 1.03)) return "SHORT";
        return "NO_TRADE";
    }

    private Map<String, List<String>> groupedIndicatorNames() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("whale", List.of("Whale buy", "Whale sell", "Exchange deposit", "Exchange withdrawal", "Stablecoin transfer", "Smart money accumulation", "Whale wallet balance change", "Dormant wallet movement", "Fund/ETF movement", "Top holder concentration"));
        groups.put("onchain", List.of("Exchange reserve", "Netflow", "Active addresses", "New addresses", "Transaction count", "MVRV", "SOPR", "NUPL", "Realized price", "Miner reserve"));
        groups.put("derivatives", List.of("Open interest", "OI change", "Funding rate", "Long/short ratio", "Liquidation long", "Liquidation short", "Taker buy/sell ratio", "Futures premium", "Basis", "Options put/call ratio"));
        groups.put("technical", List.of("RSI", "MACD", "EMA 20", "EMA 50", "EMA 200", "VWAP", "Bollinger Bands", "ATR", "ADX", "Supertrend"));
        groups.put("sentiment", List.of("BTC dominance", "Fear & Greed", "Volume spike", "News sentiment", "Social dominance", "DXY", "US yields", "ETF inflow/outflow", "Stablecoin market cap", "Total crypto market cap trend"));
        return groups;
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

    private Object globalQuoteValue(Map<?, ?> source, String key) {
        if (source == null) return null;
        Object quoteObj = source.get("Global Quote");
        if (quoteObj instanceof Map<?, ?> quote) {
            return quote.get(key);
        }
        return null;
    }

    private Object nestedValue(Map<String, Object> source, String first, String second) {
        Object firstValue = source.get(first);
        if (firstValue instanceof Map<?, ?> nested) {
            return nested.get(second);
        }
        return "";
    }

    private String classifyNewsTitle(String title) {
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (lower.contains("hack")
                || lower.contains("exploit")
                || lower.contains("lawsuit")
                || lower.contains("sec")
                || lower.contains("ban")
                || lower.contains("war")
                || lower.contains("iran")
                || lower.contains("crash")
                || lower.contains("liquidation")
                || lower.contains("outflow")
                || lower.contains("sell-off")
                || lower.contains("regulation")) {
            return "RISK_OFF";
        }
        if (lower.contains("inflow")
                || lower.contains("etf")
                || lower.contains("accumulation"
                )
                || lower.contains("buy")
                || lower.contains("fed")
                || lower.contains("liquidity")
                || lower.contains("reserve")
                || lower.contains("approval")) {
            return "RISK_ON";
        }
        return "NEUTRAL";
    }

    private double parsePercent(Object value) {
        if (value == null) return 0;
        String text = String.valueOf(value).replace("%", "").trim();
        return parseDouble(text);
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
        double ema200 = ema(closes, 200);
        double rsi = rsi(closes, 14);
        double std20 = std(closes, 20);
        double bbMiddle = sma(closes, 20);
        double bbUpper = bbMiddle + std20 * 2;
        double bbLower = bbMiddle - std20 * 2;
        double ema12 = ema(closes, 12);
        double ema26 = ema(closes, 26);
        double macd = ema12 - ema26;
        double atr = atr(highs, lows, closes, 14);
        double adx = adx(highs, lows, closes, 14);
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
        String supertrend = close > ema20 && close > vwap && macd > 0 ? "BULLISH"
                : close < ema20 && close < vwap && macd < 0 ? "BEARISH"
                : "NEUTRAL";

        double score = 50;
        score += "BULLISH".equals(maTrend) ? 15 : "BEARISH".equals(maTrend) ? -15 : 0;
        score += rsi >= 55 && rsi <= 70 ? 8 : rsi > 75 ? -8 : rsi < 30 ? 5 : rsi < 45 ? -5 : 0;
        score += "BULLISH".equals(macdSignal) ? 8 : "BEARISH".equals(macdSignal) ? -8 : 0;
        score += close > ema20 && ema20 > ema50 && ema50 > ema200 ? 10 : close < ema20 && ema20 < ema50 && ema50 < ema200 ? -10 : 0;
        score += close > vwap ? 5 : -5;
        score += "ABOVE_UPPER".equals(bollingerPosition) ? -3 : "BELOW_LOWER".equals(bollingerPosition) ? 3 : 0;
        score += adx >= 25 && "BULLISH".equals(supertrend) ? 6 : adx >= 25 && "BEARISH".equals(supertrend) ? -6 : 0;
        score = Math.max(5, Math.min(95, score));

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("score", round(score));
        value.put("close", round(close));
        value.put("sma50", round(sma50));
        value.put("sma100", round(sma100));
        value.put("sma200", round(sma200));
        value.put("ema20", round(ema20));
        value.put("ema50", round(ema50));
        value.put("ema200", round(ema200));
        value.put("rsi14", round(rsi));
        value.put("bollingerUpper", round(bbUpper));
        value.put("bollingerMiddle", round(bbMiddle));
        value.put("bollingerLower", round(bbLower));
        value.put("bollingerPosition", bollingerPosition);
        value.put("macd", round(macd));
        value.put("macdSignal", macdSignal);
        value.put("atr14", round(atr));
        value.put("adx14", round(adx));
        value.put("supertrend", supertrend);
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
        value.put("adx14", 0);
        value.put("supertrend", "WAITING");
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

    private double adx(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (closes.size() <= period + 1) return 0;
        List<Double> dxValues = new ArrayList<>();
        for (int i = Math.max(1, closes.size() - period); i < closes.size(); i++) {
            double upMove = highs.get(i) - highs.get(i - 1);
            double downMove = lows.get(i - 1) - lows.get(i);
            double plusDm = upMove > downMove && upMove > 0 ? upMove : 0;
            double minusDm = downMove > upMove && downMove > 0 ? downMove : 0;
            double trueRange = Math.max(highs.get(i) - lows.get(i),
                    Math.max(Math.abs(highs.get(i) - closes.get(i - 1)), Math.abs(lows.get(i) - closes.get(i - 1))));
            if (trueRange <= 0) continue;
            double plusDi = 100 * plusDm / trueRange;
            double minusDi = 100 * minusDm / trueRange;
            double denominator = plusDi + minusDi;
            if (denominator > 0) dxValues.add(100 * Math.abs(plusDi - minusDi) / denominator);
        }
        return dxValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
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
                                                String liquidationRisk,
                                                Map<String, Object> macroStatus,
                                                Map<String, Object> externalFeeds,
                                                Map<String, Object> categoryScores) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("market", marketPrice);
        payload.put("futures", futures);
        payload.put("macro", macroStatus);
        payload.put("externalFeeds", externalFeeds);
        payload.put("categoryScores", categoryScores);
        payload.put("timeframes", timeframes);
        payload.put("technicalSignal", technicalSignal);
        payload.put("technicalScore", technicalScore);
        payload.put("futuresScore", futuresScore);
        payload.put("volumeScore", volumeScore);
        payload.put("newsRisk", newsRisk);
        payload.put("liquidationRisk", liquidationRisk);
        payload.put("finalScoreFormula", "Whale 30% + On-chain 25% + Derivatives 25% + Technical 15% + Macro/News 5%");
        payload.put("rules", List.of(
                "Return only LONG, SHORT, or NO_TRADE.",
                "News is low-weight risk filter only; do not force trades from headlines.",
                "Prefer NO_TRADE when whale/on-chain, derivatives or technicals conflict.",
                "Auto trade only when whale/on-chain, derivatives and technical direction agree.",
                "80+ score means strong, 70-79 watchlist, 60-69 weak, below 60 no trade.",
                "For LONG, S&P500/SPY must not be risk-off and dollar/DXY proxy must not be strongly up.",
                "Use ETF proxy, stablecoin/macro proxy and whale/exchange-flow context before giving trade probability.",
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
        Map<?, ?> macro = payload.get("macro") instanceof Map<?, ?> x ? x : Map.of();
        Map<?, ?> external = payload.get("externalFeeds") instanceof Map<?, ?> e ? e : Map.of();
        Map<?, ?> category = payload.get("categoryScores") instanceof Map<?, ?> c ? c : Map.of();

        double change1h = asDouble(market.get("percentChange1h"));
        double change24h = asDouble(market.get("percentChange24h"));
        double change7d = asDouble(market.get("percentChange7d"));
        double fundingRate = asDouble(futures.get("fundingRate"));
        double longShortRatio = asDouble(futures.get("longShortRatio"));
        double volumeSpike = asDouble(futures.get("volumeSpike"));
        String macroRisk = String.valueOf(macro.containsKey("macroRisk") ? macro.get("macroRisk") : "UNKNOWN");
        double sp500Change = asDouble(macro.get("sp500ChangePercent"));
        double dxyChange = asDouble(macro.get("dxyChangePercent"));
        String externalRisk = String.valueOf(external.containsKey("externalRisk") ? external.get("externalRisk") : "UNKNOWN");
        double smartMoneyScore = asDouble(external.get("smartMoneyScore"));
        double whaleScore = asDouble(category.get("whaleScore"));
        double onchainScore = asDouble(category.get("onchainScore"));
        double derivativesWeightedScore = asDouble(category.get("derivativesScore"));
        double categoryTechnicalScore = asDouble(category.get("technicalScore"));
        double sentimentWeightedScore = asDouble(category.get("sentimentScore"));
        String whaleDirection = String.valueOf(category.containsKey("whaleDirection") ? category.get("whaleDirection") : "NO_TRADE");
        String derivativesDirection = String.valueOf(category.containsKey("derivativesDirection") ? category.get("derivativesDirection") : "NO_TRADE");
        String categoryTechnicalDirection = String.valueOf(category.containsKey("technicalDirection") ? category.get("technicalDirection") : "NO_TRADE");
        boolean categoryAligned = !"NO_TRADE".equals(categoryTechnicalDirection)
                && categoryTechnicalDirection.equals(whaleDirection)
                && categoryTechnicalDirection.equals(derivativesDirection);
        double formulaScore = whaleScore * 0.30 + onchainScore * 0.25 + derivativesWeightedScore * 0.25 + categoryTechnicalScore * 0.15 + sentimentWeightedScore * 0.05;

        double longScore = 25 + Math.max(0, change1h * 7) + Math.max(0, change24h * 3) + Math.max(0, change7d)
                + (technicalScore - 50) * 0.45 + (futuresScore - 50) * 0.35 + (volumeScore - 50) * 0.2
                + (longShortRatio > 1.03 ? 5 : 0) + (volumeSpike > 1.5 ? 4 : 0)
                + (sp500Change > 0.35 ? 5 : 0) - (dxyChange > 0.35 ? 6 : 0)
                + Math.max(0, smartMoneyScore - 60) * 0.25
                + ("LONG".equals(whaleDirection) ? 6 : 0)
                + ("LONG".equals(derivativesDirection) ? 6 : 0)
                + ("LONG".equals(categoryTechnicalDirection) ? 6 : 0)
                + Math.max(0, formulaScore - 70) * 0.25;
        double shortScore = 25 + Math.max(0, -change1h * 7) + Math.max(0, -change24h * 3) + Math.max(0, -change7d)
                + ("SHORT".equals(technicalSignal) ? 12 : 0)
                + (longShortRatio < 0.97 ? 5 : 0) + (fundingRate > 0.0006 ? 4 : 0) + (volumeSpike > 1.8 ? 3 : 0)
                + (sp500Change < -0.35 ? 5 : 0) + (dxyChange > 0.35 ? 4 : 0)
                + ("SHORT".equals(whaleDirection) ? 6 : 0)
                + ("SHORT".equals(derivativesDirection) ? 6 : 0)
                + ("SHORT".equals(categoryTechnicalDirection) ? 6 : 0);
        double noTradeScore = 30 + ("HIGH".equals(newsRisk) ? 45 : 0) + ("HIGH".equals(liquidationRisk) ? 40 : 0)
                + ("HIGH".equals(macroRisk) ? 32 : "MEDIUM".equals(macroRisk) ? 12 : 0)
                + ("HIGH".equals(externalRisk) ? 35 : "MEDIUM".equals(externalRisk) ? 12 : 0)
                + (smartMoneyScore < 45 ? 14 : 0)
                + (!categoryAligned ? 24 : 0)
                + (formulaScore < 70 ? 14 : 0)
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
        decision.put("reason", "Local AI scorer: 50-indicator formula + category alignment + CMC momentum + Binance futures + verified/free news + ETF/macro + whale-flow risk.");
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
        long cancelledBadData = trades.stream().filter(t -> "CANCELLED_BAD_DATA".equals(t.getStatus())).count();
        List<CryptoPaperTrade> validTrades = trades.stream()
                .filter(t -> !"CANCELLED_BAD_DATA".equals(t.getStatus()))
                .toList();
        long total = validTrades.size();
        long wins = validTrades.stream().filter(t -> "PROFIT".equals(t.getStatus())).count();
        long losses = validTrades.stream().filter(t -> "LOSS".equals(t.getStatus())).count();
        long running = validTrades.stream().filter(t -> "RUNNING".equals(t.getStatus())).count();
        double pnl = validTrades.stream().mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        long todayTrades = validTrades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart)).count();
        long todayProfitable = validTrades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart) && "PROFIT".equals(t.getStatus())).count();
        double todayPnl = validTrades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(todayStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        double weekPnl = validTrades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(weekStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        double monthPnl = validTrades.stream().filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(monthStart))
                .mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).sum();
        double maxLoss = validTrades.stream().mapToDouble(t -> t.getPnl() == null ? 0 : t.getPnl()).min().orElse(0);
        Map<String, Long> aiWins = new LinkedHashMap<>();
        for (CryptoPaperTrade trade : validTrades) {
            if ("PROFIT".equals(trade.getStatus())) {
                aiWins.put(trade.getBestAi(), aiWins.getOrDefault(trade.getBestAi(), 0L) + 1);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalTrades", total);
        report.put("cancelledBadDataTrades", cancelledBadData);
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
        return blockReason(true, confidence, longFrames, newsRisk, finalScore, "", true);
    }

    private String blockReason(boolean liveCmc, double confidence, long alignedFrames, String newsRisk, double finalScore, String finalSignal, boolean categoryAligned) {
        if (!liveCmc) return "CoinMarketCap live API key missing";
        if (confidence < 75) return "AI confidence below 75";
        if ("NO_TRADE".equals(finalSignal)) return "CMC momentum is neutral";
        if (alignedFrames < 2) return "Multi-timeframe not aligned";
        if (!categoryAligned) return "Whale + derivatives + technical direction not aligned";
        if ("HIGH".equals(newsRisk)) return "High news risk";
        if (finalScore < 80) return "Final 50-indicator score below 80";
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
