package com.vaishnav.Inventory.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CryptoIndicatorService {

    public Map<String, Object> analyze(List<CryptoMarketDataService.Candle> candles) {
        if (candles == null || candles.size() < 200) {
            throw new RuntimeException("Minimum 200 candles required");
        }

        List<Double> closes = candles.stream().map(c -> c.close).toList();

        double lastClose = closes.get(closes.size() - 1);
        double sma20 = sma(closes, 20);
        double sma50 = sma(closes, 50);
        double sma200 = sma(closes, 200);
        double rsi14 = rsi(closes, 14);

        int bullish = 0;
        int bearish = 0;

        if (lastClose > sma20) bullish++; else bearish++;
        if (lastClose > sma50) bullish++; else bearish++;
        if (lastClose > sma200) bullish++; else bearish++;
        if (sma20 > sma50) bullish++; else bearish++;
        if (sma50 > sma200) bullish++; else bearish++;
        if (rsi14 >= 50 && rsi14 <= 70) bullish++; else bearish++;

        String signal = bullish >= bearish ? "LONG" : "SHORT";
        int score = (int) Math.round((bullish * 100.0) / (bullish + bearish));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", signal);
        result.put("score", score);
        result.put("lastClose", lastClose);
        result.put("sma20", sma20);
        result.put("sma50", sma50);
        result.put("sma200", sma200);
        result.put("rsi14", rsi14);
        result.put("bullish", bullish);
        result.put("bearish", bearish);

        return result;
    }

    private double sma(List<Double> values, int period) {
        return values.subList(values.size() - period, values.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
    }

    private double rsi(List<Double> closes, int period) {
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
}