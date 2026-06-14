package com.vaishnav.Inventory.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class CryptoMarketDataService {

    private final RestTemplate restTemplate = new RestTemplate();

    public List<Candle> getCandles(String symbol, String interval, int limit) {
        String url = "https://api.binance.com/api/v3/klines?symbol="
                + symbol + "&interval=" + interval + "&limit=" + limit;

        Object[] response = restTemplate.getForObject(url, Object[].class);
        List<Candle> candles = new ArrayList<>();

        if (response == null) return candles;

        for (Object item : response) {
            List<?> row = (List<?>) item;

            Candle candle = new Candle();
            candle.openTime = Long.parseLong(String.valueOf(row.get(0)));
            candle.open = Double.parseDouble(String.valueOf(row.get(1)));
            candle.high = Double.parseDouble(String.valueOf(row.get(2)));
            candle.low = Double.parseDouble(String.valueOf(row.get(3)));
            candle.close = Double.parseDouble(String.valueOf(row.get(4)));
            candle.volume = Double.parseDouble(String.valueOf(row.get(5)));

            candles.add(candle);
        }

        return candles;
    }

    public double getLivePrice(String symbol) {
        String url = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;
        Map response = restTemplate.getForObject(url, Map.class);

        if (response == null || response.get("price") == null) {
            throw new RuntimeException("Live price not found for " + symbol);
        }

        return Double.parseDouble(String.valueOf(response.get("price")));
    }

    public FuturesStats getFuturesStats(String symbol) {
        FuturesStats stats = new FuturesStats();
        stats.symbol = symbol;

        Map openInterest = restTemplate.getForObject(
                "https://fapi.binance.com/fapi/v1/openInterest?symbol=" + symbol,
                Map.class
        );
        Map premium = restTemplate.getForObject(
                "https://fapi.binance.com/fapi/v1/premiumIndex?symbol=" + symbol,
                Map.class
        );
        Map ticker = restTemplate.getForObject(
                "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=" + symbol,
                Map.class
        );

        stats.openInterest = parseDouble(openInterest == null ? null : openInterest.get("openInterest"));
        stats.fundingRate = parseDouble(premium == null ? null : premium.get("lastFundingRate"));
        stats.markPrice = parseDouble(premium == null ? null : premium.get("markPrice"));
        stats.indexPrice = parseDouble(premium == null ? null : premium.get("indexPrice"));
        stats.priceChangePercent24h = parseDouble(ticker == null ? null : ticker.get("priceChangePercent"));
        stats.quoteVolume24h = parseDouble(ticker == null ? null : ticker.get("quoteVolume"));
        return stats;
    }

    private double parseDouble(Object value) {
        if (value == null) return 0;
        return Double.parseDouble(String.valueOf(value));
    }

    public static class Candle {
        public long openTime;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
    }

    public static class FuturesStats {
        public String symbol;
        public double openInterest;
        public double fundingRate;
        public double markPrice;
        public double indexPrice;
        public double priceChangePercent24h;
        public double quoteVolume24h;
    }
}
