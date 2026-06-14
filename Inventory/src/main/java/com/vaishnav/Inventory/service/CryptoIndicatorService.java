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
        double ema20 = ema(closes, 20);
        double ema50 = ema(closes, 50);
        double ema200 = ema(closes, 200);
        double rsi14 = rsi(closes, 14);
        double macd = ema(closes, 12) - ema(closes, 26);
        double macdSignal = macdSignal(closes);
        double atr14 = atr(candles, 14);
        double vwap = vwap(candles, 48);
        double bollingerMiddle = sma20;
        double bollingerStd = stddev(closes, 20);
        double bollingerUpper = bollingerMiddle + bollingerStd * 2;
        double bollingerLower = bollingerMiddle - bollingerStd * 2;

        int bullish = 0;
        int bearish = 0;

        if (lastClose > sma20) bullish++; else bearish++;
        if (lastClose > sma50) bullish++; else bearish++;
        if (lastClose > sma200) bullish++; else bearish++;
        if (lastClose > ema20) bullish++; else bearish++;
        if (ema20 > ema50) bullish++; else bearish++;
        if (ema50 > ema200) bullish++; else bearish++;
        if (sma20 > sma50) bullish++; else bearish++;
        if (sma50 > sma200) bullish++; else bearish++;
        if (rsi14 >= 50 && rsi14 <= 70) bullish++; else bearish++;
        if (macd > macdSignal) bullish++; else bearish++;
        if (lastClose > vwap) bullish++; else bearish++;
        if (lastClose > bollingerMiddle && lastClose < bollingerUpper) bullish++; else bearish++;

        String signal = bullish >= bearish ? "LONG" : "SHORT";
        int score = (int) Math.round((bullish * 100.0) / (bullish + bearish));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", signal);
        result.put("score", score);
        result.put("lastClose", lastClose);
        result.put("sma20", sma20);
        result.put("sma50", sma50);
        result.put("sma200", sma200);
        result.put("ema20", ema20);
        result.put("ema50", ema50);
        result.put("ema200", ema200);
        result.put("rsi14", rsi14);
        result.put("macd", macd);
        result.put("macdSignal", macdSignal);
        result.put("macdTrend", macd > macdSignal ? "BULLISH" : "BEARISH");
        result.put("atr14", atr14);
        result.put("vwap", vwap);
        result.put("bollingerUpper", bollingerUpper);
        result.put("bollingerMiddle", bollingerMiddle);
        result.put("bollingerLower", bollingerLower);
        result.put("bollingerPosition", lastClose > bollingerUpper ? "ABOVE_UPPER" : lastClose < bollingerLower ? "BELOW_LOWER" : "INSIDE_BAND");
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

    private double ema(List<Double> values, int period) {
        if (values.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = values.subList(0, period).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        for (int i = period; i < values.size(); i++) {
            ema = (values.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    private double macdSignal(List<Double> closes) {
        if (closes.size() < 35) return 0;
        List<Double> macdValues = new ArrayList<>();
        for (int end = 35; end <= closes.size(); end++) {
            List<Double> slice = closes.subList(0, end);
            macdValues.add(ema(slice, 12) - ema(slice, 26));
        }
        return ema(macdValues, Math.min(9, macdValues.size()));
    }

    private double atr(List<CryptoMarketDataService.Candle> candles, int period) {
        if (candles.size() <= period) return 0;
        List<Double> ranges = new ArrayList<>();
        for (int i = candles.size() - period; i < candles.size(); i++) {
            CryptoMarketDataService.Candle current = candles.get(i);
            CryptoMarketDataService.Candle previous = candles.get(i - 1);
            double trueRange = Math.max(current.high - current.low,
                    Math.max(Math.abs(current.high - previous.close), Math.abs(current.low - previous.close)));
            ranges.add(trueRange);
        }
        return ranges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double vwap(List<CryptoMarketDataService.Candle> candles, int period) {
        int start = Math.max(0, candles.size() - period);
        double typicalVolume = 0;
        double volume = 0;
        for (int i = start; i < candles.size(); i++) {
            CryptoMarketDataService.Candle candle = candles.get(i);
            double typical = (candle.high + candle.low + candle.close) / 3;
            typicalVolume += typical * candle.volume;
            volume += candle.volume;
        }
        return volume == 0 ? 0 : typicalVolume / volume;
    }

    private double stddev(List<Double> values, int period) {
        List<Double> slice = values.subList(values.size() - period, values.size());
        double avg = slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = slice.stream().mapToDouble(value -> Math.pow(value - avg, 2)).average().orElse(0);
        return Math.sqrt(variance);
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
