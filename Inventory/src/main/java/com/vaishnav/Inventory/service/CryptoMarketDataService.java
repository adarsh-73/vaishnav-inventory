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

    public static class Candle {
        public long openTime;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
    }
}