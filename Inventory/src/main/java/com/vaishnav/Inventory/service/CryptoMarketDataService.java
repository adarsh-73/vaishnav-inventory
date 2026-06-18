package com.vaishnav.Inventory.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class CryptoMarketDataService {

    private final RestTemplate restTemplate;

    public CryptoMarketDataService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(6_000);
        factory.setReadTimeout(8_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public List<Candle> getCandles(String symbol, String interval, int limit) {
        String url = "https://api.binance.com/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
        Object[] response = restTemplate.getForObject(url, Object[].class);
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
        Map<?, ?> response = restTemplate.getForObject("https://api.binance.com/api/v3/ticker/price?symbol=" + symbol, Map.class);
        if (response == null || response.get("price") == null) throw new RuntimeException("Live price not found for " + symbol);
        return parseDouble(response.get("price"));
    }

    public FuturesStats getFuturesStats(String symbol) {
        FuturesStats stats = new FuturesStats();
        stats.symbol = symbol;
        Map<?, ?> openInterest = safeMap("https://fapi.binance.com/fapi/v1/openInterest?symbol=" + symbol);
        Map<?, ?> premium = safeMap("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=" + symbol);
        Map<?, ?> ticker = safeMap("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=" + symbol);

        stats.openInterest = parseDouble(openInterest.get("openInterest"));
        stats.fundingRate = parseDouble(premium.get("lastFundingRate"));
        stats.nextFundingTime = parseLong(premium.get("nextFundingTime"));
        stats.markPrice = parseDouble(premium.get("markPrice"));
        stats.indexPrice = parseDouble(premium.get("indexPrice"));
        stats.priceChangePercent24h = parseDouble(ticker.get("priceChangePercent"));
        stats.quoteVolume24h = parseDouble(ticker.get("quoteVolume"));

        List<Map<String, Object>> oiHistory = safeList("https://fapi.binance.com/futures/data/openInterestHist?symbol=" + symbol + "&period=5m&limit=30");
        if (oiHistory.size() >= 2) {
            double first = parseDouble(oiHistory.get(0).get("sumOpenInterest"));
            double latest = parseDouble(oiHistory.get(oiHistory.size() - 1).get("sumOpenInterest"));
            stats.openInterestChangePercent = first == 0 ? 0 : (latest - first) * 100 / first;
            stats.openInterestValue = parseDouble(oiHistory.get(oiHistory.size() - 1).get("sumOpenInterestValue"));
        }

        List<Map<String, Object>> ratios = safeList("https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol=" + symbol + "&period=5m&limit=1");
        if (!ratios.isEmpty()) {
            Map<String, Object> latest = ratios.get(ratios.size() - 1);
            stats.longShortRatio = parseDouble(latest.get("longShortRatio"));
            stats.longAccount = parseDouble(latest.get("longAccount"));
            stats.shortAccount = parseDouble(latest.get("shortAccount"));
        }

        List<Map<String, Object>> taker = safeList("https://fapi.binance.com/futures/data/takerlongshortRatio?symbol=" + symbol + "&period=5m&limit=1");
        if (!taker.isEmpty()) stats.takerBuySellRatio = parseDouble(taker.get(taker.size() - 1).get("buySellRatio"));

        populateLargeTradeFlow(stats, symbol);
        stats.fetchedAt = System.currentTimeMillis();
        return stats;
    }

    public Map<String, Object> getFearGreed() {
        Map<?, ?> response = safeMap("https://api.alternative.me/fng/?limit=1");
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

    private void populateLargeTradeFlow(FuturesStats stats, String symbol) {
        List<Map<String, Object>> trades = safeList("https://fapi.binance.com/fapi/v1/aggTrades?symbol=" + symbol + "&limit=1000");
        double threshold = Math.max(50_000, stats.quoteVolume24h * 0.00001);
        stats.largeTradeThreshold = threshold;
        for (Map<String, Object> trade : trades) {
            double notional = parseDouble(trade.get("p")) * parseDouble(trade.get("q"));
            if (notional < threshold) continue;
            boolean buyerWasMaker = Boolean.parseBoolean(String.valueOf(trade.get("m")));
            if (buyerWasMaker) stats.largeSellNotional += notional;
            else stats.largeBuyNotional += notional;
            stats.largeTradeCount++;
        }
        stats.largeTradeBias = stats.largeBuyNotional > stats.largeSellNotional ? "BUY" : stats.largeSellNotional > stats.largeBuyNotional ? "SELL" : "NEUTRAL";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeList(String url) {
        try {
            List<?> response = restTemplate.getForObject(url, List.class);
            if (response == null) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : response) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<?, ?> safeMap(String url) {
        try {
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            return response == null ? Map.of() : response;
        } catch (Exception ignored) {
            return Map.of();
        }
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
        public double longShortRatio;
        public double longAccount;
        public double shortAccount;
        public double takerBuySellRatio;
        public double largeBuyNotional;
        public double largeSellNotional;
        public int largeTradeCount;
        public double largeTradeThreshold;
        public String largeTradeBias = "NEUTRAL";
        public long fetchedAt;
    }
}
