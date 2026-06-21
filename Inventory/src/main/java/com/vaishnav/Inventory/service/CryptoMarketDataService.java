package com.vaishnav.Inventory.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class CryptoMarketDataService {

    private static final List<String> SPOT_BASES = List.of(
            "https://data-api.binance.vision",
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api2.binance.com"
    );
    private static final List<String> FUTURES_BASES = List.of(
            "https://fapi.binance.com",
            "https://fapi1.binance.com",
            "https://fapi2.binance.com"
    );
    private static final List<String> BYBIT_BASES = List.of(
            "https://api.bybit.com",
            "https://api.bytick.com",
            "https://api.bybitglobal.com"
    );

    private final RestTemplate restTemplate;
    private volatile String lastSpotSource = "BINANCE_NOT_FETCHED";
    private volatile String lastFuturesSource = "BINANCE_FUTURES_NOT_FETCHED";
    private volatile List<?> hyperliquidContexts;
    private volatile long hyperliquidContextsAt;

    public CryptoMarketDataService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(6_000);
        factory.setReadTimeout(8_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public List<Candle> getCandles(String symbol, String interval, int limit) {
        String path = "/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
        Object[] response = getSpot(path, Object[].class);
        return parseCandles(response);
    }

    public List<Candle> getCandlesSince(String symbol, String interval, long startTime, int maxCandles) {
        List<Candle> result = new ArrayList<>();
        long cursor = startTime;
        while (result.size() < maxCandles) {
            int limit = Math.min(1000, maxCandles - result.size());
            String path = "/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&startTime=" + cursor + "&limit=" + limit;
            List<Candle> page = parseCandles(getSpot(path, Object[].class));
            if (page.isEmpty()) break;
            result.addAll(page);
            long next = page.get(page.size() - 1).closeTime + 1;
            if (next <= cursor || page.size() < limit) break;
            cursor = next;
        }
        return result;
    }

    private List<Candle> parseCandles(Object[] response) {
        List<Candle> candles = new ArrayList<>();
        if (response == null) return candles;

        for (Object item : response) {
            List<?> row = (List<?>) item;
            Candle candle = new Candle();
            candle.openTime = parseLong(row.get(0));
            candle.open = parseDouble(row.get(1));
            candle.high = parseDouble(row.get(2));
            candle.low = parseDouble(row.get(3));
            candle.close = parseDouble(row.get(4));
            candle.volume = parseDouble(row.get(5));
            candle.closeTime = row.size() > 6 ? parseLong(row.get(6)) : 0;
            candle.quoteVolume = row.size() > 7 ? parseDouble(row.get(7)) : 0;
            candle.tradeCount = row.size() > 8 ? (int) parseLong(row.get(8)) : 0;
            candle.takerBuyBaseVolume = row.size() > 9 ? parseDouble(row.get(9)) : 0;
            candles.add(candle);
        }
        return candles;
    }

    public double getLivePrice(String symbol) {
        Map<?, ?> response = getSpot("/api/v3/ticker/price?symbol=" + symbol, Map.class);
        if (response == null || response.get("price") == null) throw new RuntimeException("Live price not found for " + symbol);
        return parseDouble(response.get("price"));
    }

    public FuturesStats getFuturesStats(String symbol, double spotPrice) {
        FuturesStats stats = new FuturesStats();
        stats.symbol = symbol;
        Map<?, ?> premium = safeFuturesMap("/fapi/v1/premiumIndex?symbol=" + symbol);
        if (premium.isEmpty()) {
            FuturesStats bybit = getBybitFuturesStats(symbol, spotPrice);
            return bybit.markPrice > 0 ? bybit : getHyperliquidFuturesStats(symbol, spotPrice);
        }
        Map<?, ?> openInterest = safeFuturesMap("/fapi/v1/openInterest?symbol=" + symbol);
        Map<?, ?> ticker = safeFuturesMap("/fapi/v1/ticker/24hr?symbol=" + symbol);

        stats.openInterest = parseDouble(openInterest.get("openInterest"));
        stats.fundingRate = parseDouble(premium.get("lastFundingRate"));
        stats.nextFundingTime = parseLong(premium.get("nextFundingTime"));
        stats.markPrice = parseDouble(premium.get("markPrice"));
        stats.indexPrice = parseDouble(premium.get("indexPrice"));
        stats.priceChangePercent24h = parseDouble(ticker.get("priceChangePercent"));
        stats.quoteVolume24h = parseDouble(ticker.get("quoteVolume"));
        stats.spotPrice = spotPrice;
        stats.spotFuturesBasisPercent = spotPrice == 0 ? 0 : (stats.markPrice - spotPrice) * 100 / spotPrice;

        List<Map<String, Object>> oiHistory = safeFuturesList("/futures/data/openInterestHist?symbol=" + symbol + "&period=5m&limit=30");
        if (oiHistory.size() >= 2) {
            double first = parseDouble(oiHistory.get(0).get("sumOpenInterest"));
            double latest = parseDouble(oiHistory.get(oiHistory.size() - 1).get("sumOpenInterest"));
            stats.openInterestChangePercent = first == 0 ? 0 : (latest - first) * 100 / first;
            stats.openInterestValue = parseDouble(oiHistory.get(oiHistory.size() - 1).get("sumOpenInterestValue"));
        }

        List<Map<String, Object>> ratios = safeFuturesList("/futures/data/globalLongShortAccountRatio?symbol=" + symbol + "&period=5m&limit=1");
        if (!ratios.isEmpty()) {
            Map<String, Object> latest = ratios.get(ratios.size() - 1);
            stats.longShortRatio = parseDouble(latest.get("longShortRatio"));
            stats.longAccount = parseDouble(latest.get("longAccount"));
            stats.shortAccount = parseDouble(latest.get("shortAccount"));
        }

        List<Map<String, Object>> taker = safeFuturesList("/futures/data/takerlongshortRatio?symbol=" + symbol + "&period=5m&limit=1");
        if (!taker.isEmpty()) stats.takerBuySellRatio = parseDouble(taker.get(taker.size() - 1).get("buySellRatio"));

        populateOrderBook(stats, symbol);
        populateTradeFlow(stats, symbol);
        stats.fetchedAt = System.currentTimeMillis();
        return stats;
    }

    private FuturesStats getBybitFuturesStats(String symbol, double spotPrice) {
        FuturesStats stats = new FuturesStats();
        stats.symbol = symbol;
        stats.spotPrice = spotPrice;
        try {
            Map<?, ?> tickerRoot = safeBybitRoot("/v5/market/tickers?category=linear&symbol=" + symbol);
            Map<?, ?> ticker = firstBybitResult(tickerRoot);
            if (ticker.isEmpty()) return stats;
            stats.openInterest = parseDouble(ticker.get("openInterest"));
            stats.openInterestValue = parseDouble(ticker.get("openInterestValue"));
            stats.fundingRate = parseDouble(ticker.get("fundingRate"));
            stats.nextFundingTime = parseLong(ticker.get("nextFundingTime"));
            stats.markPrice = parseDouble(ticker.get("markPrice"));
            stats.indexPrice = parseDouble(ticker.get("indexPrice"));
            stats.priceChangePercent24h = parseDouble(ticker.get("price24hPcnt")) * 100;
            stats.quoteVolume24h = parseDouble(ticker.get("turnover24h"));
            stats.spotFuturesBasisPercent = spotPrice == 0 ? 0 : (stats.markPrice - spotPrice) * 100 / spotPrice;

            Map<?, ?> depthRoot = safeBybitRoot("/v5/market/orderbook?category=linear&symbol=" + symbol + "&limit=50");
            Map<?, ?> depth = bybitResult(depthRoot);
            stats.bidDepthNotional = depthNotional(depth.get("b"));
            stats.askDepthNotional = depthNotional(depth.get("a"));
            double depthTotal = stats.bidDepthNotional + stats.askDepthNotional;
            stats.orderBookImbalancePercent = depthTotal == 0 ? 0 :
                    (stats.bidDepthNotional - stats.askDepthNotional) * 100 / depthTotal;

            Map<?, ?> oiRoot = safeBybitRoot("/v5/market/open-interest?category=linear&symbol=" + symbol + "&intervalTime=5min&limit=30");
            List<Map<String, Object>> oiRows = bybitResultList(oiRoot);
            if (oiRows.size() >= 2) {
                double latest = parseDouble(oiRows.get(0).get("openInterest"));
                double oldest = parseDouble(oiRows.get(oiRows.size() - 1).get("openInterest"));
                stats.openInterestChangePercent = oldest == 0 ? 0 : (latest - oldest) * 100 / oldest;
            }

            Map<?, ?> ratioRoot = safeBybitRoot("/v5/market/account-ratio?category=linear&symbol=" + symbol + "&period=5min&limit=1");
            List<Map<String, Object>> ratioRows = bybitResultList(ratioRoot);
            if (!ratioRows.isEmpty()) {
                stats.longAccount = parseDouble(ratioRows.get(0).get("buyRatio"));
                stats.shortAccount = parseDouble(ratioRows.get(0).get("sellRatio"));
                stats.longShortRatio = stats.shortAccount == 0 ? 0 : stats.longAccount / stats.shortAccount;
            }

            Map<?, ?> tradesRoot = safeBybitRoot("/v5/market/recent-trade?category=linear&symbol=" + symbol + "&limit=1000");
            populateBybitTradeFlow(stats, bybitResultList(tradesRoot));
            stats.fetchedAt = System.currentTimeMillis();
            if (!lastFuturesSource.startsWith("BYBIT_PUBLIC_LINEAR")) lastFuturesSource = "BYBIT_PUBLIC_LINEAR_FALLBACK";
        } catch (Exception ignored) {
            lastFuturesSource = "FUTURES_UNAVAILABLE_BINANCE_AND_BYBIT";
        }
        return stats;
    }

    @SuppressWarnings("unchecked")
    private FuturesStats getHyperliquidFuturesStats(String symbol, double spotPrice) {
        FuturesStats stats = new FuturesStats();
        stats.symbol = symbol;
        stats.spotPrice = spotPrice;
        String coin = symbol.replace("USDT", "");
        try {
            List<?> root = hyperliquidMetaAndContexts();
            if (root.size() < 2 || !(root.get(0) instanceof Map<?, ?> meta)
                    || !(meta.get("universe") instanceof List<?> universe)
                    || !(root.get(1) instanceof List<?> contexts)) return stats;
            int index = -1;
            for (int i = 0; i < universe.size(); i++) {
                if (universe.get(i) instanceof Map<?, ?> asset && coin.equals(String.valueOf(asset.get("name")))) {
                    index = i;
                    break;
                }
            }
            if (index < 0 || index >= contexts.size() || !(contexts.get(index) instanceof Map<?, ?> context)) return stats;
            stats.openInterest = parseDouble(context.get("openInterest"));
            stats.markPrice = parseDouble(context.get("markPx"));
            stats.indexPrice = parseDouble(context.get("oraclePx"));
            stats.fundingRate = parseDouble(context.get("funding"));
            stats.quoteVolume24h = parseDouble(context.get("dayNtlVlm"));
            double previous = parseDouble(context.get("prevDayPx"));
            stats.priceChangePercent24h = previous == 0 ? 0 : (stats.markPrice - previous) * 100 / previous;
            stats.openInterestValue = stats.openInterest * stats.markPrice;
            stats.spotFuturesBasisPercent = spotPrice == 0 ? 0 : (stats.markPrice - spotPrice) * 100 / spotPrice;

            Map<String, Object> bookRequest = Map.of("type", "l2Book", "coin", coin);
            Map<String, Object> book = restTemplate.postForObject(
                    "https://api.hyperliquid.xyz/info", new HttpEntity<>(bookRequest), Map.class);
            if (book != null && book.get("levels") instanceof List<?> levels && levels.size() >= 2) {
                stats.bidDepthNotional = hyperliquidDepthNotional(levels.get(0));
                stats.askDepthNotional = hyperliquidDepthNotional(levels.get(1));
                double total = stats.bidDepthNotional + stats.askDepthNotional;
                stats.orderBookImbalancePercent = total == 0 ? 0 :
                        (stats.bidDepthNotional - stats.askDepthNotional) * 100 / total;
            }
            stats.fetchedAt = System.currentTimeMillis();
            if (stats.takerBuyNotional <= 0 && stats.takerSellNotional <= 0) populateSpotTradeFlow(stats, symbol);
            lastFuturesSource = "HYPERLIQUID_PUBLIC_PERPETUAL_FALLBACK";
        } catch (Exception ignored) {
            lastFuturesSource = "FUTURES_UNAVAILABLE_BINANCE_BYBIT_HYPERLIQUID";
        }
        return stats;
    }

    private synchronized List<?> hyperliquidMetaAndContexts() {
        if (hyperliquidContexts != null && System.currentTimeMillis() - hyperliquidContextsAt < 15_000) {
            return hyperliquidContexts;
        }
        List<?> response = restTemplate.postForObject(
                "https://api.hyperliquid.xyz/info",
                new HttpEntity<>(Map.of("type", "metaAndAssetCtxs")), List.class);
        hyperliquidContexts = response == null ? List.of() : response;
        hyperliquidContextsAt = System.currentTimeMillis();
        return hyperliquidContexts;
    }

    private double hyperliquidDepthNotional(Object raw) {
        if (!(raw instanceof List<?> rows)) return 0;
        double total = 0;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) continue;
            total += parseDouble(row.get("px")) * parseDouble(row.get("sz"));
        }
        return total;
    }

    private Map<?, ?> safeBybitRoot(String path) {
        for (String base : BYBIT_BASES) {
            try {
                Map<?, ?> response = restTemplate.getForObject(base + path, Map.class);
                if (response == null || parseLong(response.get("retCode")) != 0) continue;
                lastFuturesSource = "BYBIT_PUBLIC_LINEAR:" + base;
                return response;
            } catch (Exception ignored) {
                // Try the next official/public Bybit host.
            }
        }
        return Map.of();
    }

    private Map<?, ?> bybitResult(Map<?, ?> root) {
        if (root == null || parseLong(root.get("retCode")) != 0 || !(root.get("result") instanceof Map<?, ?> result)) return Map.of();
        return result;
    }

    private Map<?, ?> firstBybitResult(Map<?, ?> root) {
        Map<?, ?> result = bybitResult(root);
        Object raw = result.get("list");
        if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> row) return row;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bybitResultList(Map<?, ?> root) {
        Object raw = bybitResult(root).get("list");
        if (!(raw instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    private void populateBybitTradeFlow(FuturesStats stats, List<Map<String, Object>> trades) {
        double threshold = Math.max(50_000, stats.quoteVolume24h * 0.00001);
        stats.largeTradeThreshold = threshold;
        for (Map<String, Object> trade : trades) {
            double notional = parseDouble(trade.get("price")) * parseDouble(trade.get("size"));
            boolean buy = "Buy".equalsIgnoreCase(String.valueOf(trade.get("side")));
            if (buy) { stats.takerBuyNotional += notional; stats.cvd += notional; }
            else { stats.takerSellNotional += notional; stats.cvd -= notional; }
            if (notional < threshold) continue;
            if (buy) stats.largeBuyNotional += notional; else stats.largeSellNotional += notional;
            stats.largeTradeCount++;
        }
        stats.takerBuySellRatio = stats.takerSellNotional == 0 ? 0 : stats.takerBuyNotional / stats.takerSellNotional;
        stats.largeTradeBias = stats.largeBuyNotional > stats.largeSellNotional ? "BUY" :
                stats.largeSellNotional > stats.largeBuyNotional ? "SELL" : "NEUTRAL";
        stats.largeTradeSource = "BYBIT_PUBLIC_RECENT_TRADES";
    }

    private void populateSpotTradeFlow(FuturesStats stats, String symbol) {
        try {
            Object[] rawTrades = getSpot("/api/v3/aggTrades?symbol=" + symbol + "&limit=1000", Object[].class);
            double threshold = Math.max(25_000, stats.quoteVolume24h * 0.000005);
            stats.largeTradeThreshold = threshold;
            for (Object raw : rawTrades) {
                if (!(raw instanceof Map<?, ?> trade)) continue;
                double notional = parseDouble(trade.get("p")) * parseDouble(trade.get("q"));
                boolean buyerWasMaker = Boolean.parseBoolean(String.valueOf(trade.get("m")));
                if (buyerWasMaker) { stats.takerSellNotional += notional; stats.cvd -= notional; }
                else { stats.takerBuyNotional += notional; stats.cvd += notional; }
                if (notional < threshold) continue;
                if (buyerWasMaker) stats.largeSellNotional += notional; else stats.largeBuyNotional += notional;
                stats.largeTradeCount++;
            }
            stats.takerBuySellRatio = stats.takerSellNotional == 0 ? 0 : stats.takerBuyNotional / stats.takerSellNotional;
            stats.largeTradeBias = stats.largeBuyNotional > stats.largeSellNotional ? "BUY" :
                    stats.largeSellNotional > stats.largeBuyNotional ? "SELL" : "NEUTRAL";
            stats.largeTradeSource = "BINANCE_SPOT_AGG_TRADES_REAL";
        } catch (Exception ignored) {
            stats.largeTradeSource = "UNAVAILABLE";
        }
    }

    public Map<String, Object> getFearGreed() {
        Map<?, ?> response = safeExternalMap("https://api.alternative.me/fng/?limit=1");
        Object rawData = response.get("data");
        if (!(rawData instanceof List<?> data) || data.isEmpty() || !(data.get(0) instanceof Map<?, ?> latest)) {
            return Map.of("status", "UNAVAILABLE", "source", "ALTERNATIVE_ME");
        }
        return Map.of(
                "status", "LIVE",
                "value", parseDouble(latest.get("value")),
                "classification", String.valueOf(latest.get("value_classification")),
                "timestamp", String.valueOf(latest.get("timestamp")),
                "source", "ALTERNATIVE_ME_REAL"
        );
    }

    private void populateOrderBook(FuturesStats stats, String symbol) {
        Map<?, ?> depth = safeFuturesMap("/fapi/v1/depth?symbol=" + symbol + "&limit=20");
        stats.bidDepthNotional = depthNotional(depth.get("bids"));
        stats.askDepthNotional = depthNotional(depth.get("asks"));
        double total = stats.bidDepthNotional + stats.askDepthNotional;
        stats.orderBookImbalancePercent = total == 0 ? 0 : (stats.bidDepthNotional - stats.askDepthNotional) * 100 / total;
    }

    private double depthNotional(Object raw) {
        if (!(raw instanceof List<?> rows)) return 0;
        double total = 0;
        for (Object item : rows) {
            if (!(item instanceof List<?> row) || row.size() < 2) continue;
            total += parseDouble(row.get(0)) * parseDouble(row.get(1));
        }
        return total;
    }

    private void populateTradeFlow(FuturesStats stats, String symbol) {
        List<Map<String, Object>> trades = safeFuturesList("/fapi/v1/aggTrades?symbol=" + symbol + "&limit=1000");
        double threshold = Math.max(50_000, stats.quoteVolume24h * 0.00001);
        stats.largeTradeThreshold = threshold;
        for (Map<String, Object> trade : trades) {
            double notional = parseDouble(trade.get("p")) * parseDouble(trade.get("q"));
            boolean buyerWasMaker = Boolean.parseBoolean(String.valueOf(trade.get("m")));
            if (buyerWasMaker) {
                stats.takerSellNotional += notional;
                stats.cvd -= notional;
            } else {
                stats.takerBuyNotional += notional;
                stats.cvd += notional;
            }
            if (notional < threshold) continue;
            if (buyerWasMaker) stats.largeSellNotional += notional; else stats.largeBuyNotional += notional;
            stats.largeTradeCount++;
        }
        stats.largeTradeBias = stats.largeBuyNotional > stats.largeSellNotional ? "BUY" : stats.largeSellNotional > stats.largeBuyNotional ? "SELL" : "NEUTRAL";
        stats.largeTradeSource = "BINANCE_FUTURES_AGG_TRADES_REAL";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeFuturesList(String path) {
        for (String base : FUTURES_BASES) {
            try {
                List<?> response = restTemplate.getForObject(base + path, List.class);
                if (response == null) continue;
                lastFuturesSource = base;
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : response) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
                return result;
            } catch (Exception ignored) {
                // Try the next official Binance futures host.
            }
        }
        return List.of();
    }

    private Map<?, ?> safeFuturesMap(String path) {
        for (String base : FUTURES_BASES) {
            try {
                Map<?, ?> response = restTemplate.getForObject(base + path, Map.class);
                if (response == null) continue;
                lastFuturesSource = base;
                return response;
            } catch (Exception ignored) {
                // Try the next official Binance futures host.
            }
        }
        return Map.of();
    }

    private Map<?, ?> safeExternalMap(String url) {
        try {
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            return response == null ? Map.of() : response;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private <T> T getSpot(String path, Class<T> type) {
        Exception lastError = null;
        for (String base : SPOT_BASES) {
            try {
                T response = restTemplate.getForObject(base + path, type);
                if (response != null) {
                    lastSpotSource = base;
                    return response;
                }
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw new RuntimeException("All official Binance public market-data hosts failed" + (lastError == null ? "" : ": " + lastError.getMessage()));
    }

    public String getSpotSource() {
        return lastSpotSource;
    }

    public String getFuturesSource() {
        return lastFuturesSource;
    }

    private double parseDouble(Object value) {
        if (value == null || "null".equals(String.valueOf(value))) return 0;
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    private long parseLong(Object value) {
        if (value == null) return 0;
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    public static class Candle {
        public long openTime;
        public long closeTime;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
        public double quoteVolume;
        public int tradeCount;
        public double takerBuyBaseVolume;
    }

    public static class FuturesStats {
        public String symbol;
        public double openInterest;
        public double openInterestValue;
        public double openInterestChangePercent;
        public double fundingRate;
        public long nextFundingTime;
        public double markPrice;
        public double indexPrice;
        public double priceChangePercent24h;
        public double quoteVolume24h;
        public double spotPrice;
        public double spotFuturesBasisPercent;
        public double bidDepthNotional;
        public double askDepthNotional;
        public double orderBookImbalancePercent;
        public double takerBuyNotional;
        public double takerSellNotional;
        public double cvd;
        public double longShortRatio;
        public double longAccount;
        public double shortAccount;
        public double takerBuySellRatio;
        public double largeBuyNotional;
        public double largeSellNotional;
        public int largeTradeCount;
        public double largeTradeThreshold;
        public String largeTradeBias = "NEUTRAL";
        public String largeTradeSource = "UNAVAILABLE";
        public long fetchedAt;
    }
}
