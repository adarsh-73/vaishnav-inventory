package com.vaishnav.Inventory.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CryptoIndicatorService {

    public Map<String, Object> analyze(List<CryptoMarketDataService.Candle> candles) {
        if (candles == null || candles.size() < 200) {
            throw new RuntimeException("Minimum 200 real candles required");
        }

        List<Double> closes = candles.stream().map(c -> c.close).toList();
        List<Double> highs = candles.stream().map(c -> c.high).toList();
        List<Double> lows = candles.stream().map(c -> c.low).toList();
        List<Double> volumes = candles.stream().map(c -> c.volume).toList();
        double lastClose = last(closes);
        Map<String, Double> values = new LinkedHashMap<>();

        for (int period : new int[]{5, 10, 20, 50, 100, 200}) values.put("sma" + period, sma(closes, period));
        for (int period : new int[]{5, 9, 12, 20, 21, 26, 50, 100, 200}) values.put("ema" + period, ema(closes, period));
        for (int period : new int[]{7, 14, 21}) values.put("rsi" + period, rsi(closes, period));
        for (int period : new int[]{5, 10, 20}) values.put("roc" + period, roc(closes, period));
        for (int period : new int[]{5, 10, 20}) values.put("momentum" + period, momentum(closes, period));
        for (int period : new int[]{10, 20, 50}) values.put("stdDev" + period, stddev(closes, period));
        for (int period : new int[]{7, 14, 21}) values.put("atr" + period, atr(candles, period));
        for (int period : new int[]{20, 48, 100}) values.put("vwap" + period, vwap(candles, period));
        for (int period : new int[]{20, 55}) {
            double high = highest(highs, period);
            double low = lowest(lows, period);
            values.put("donchianHigh" + period, high);
            values.put("donchianLow" + period, low);
            values.put("donchianMid" + period, (high + low) / 2.0);
        }
        for (int period : new int[]{14, 28}) values.put("williamsR" + period, williamsR(closes, highs, lows, period));
        for (int period : new int[]{14, 20}) values.put("cci" + period, cci(candles, period));
        for (int period : new int[]{14, 21}) values.put("mfi" + period, mfi(candles, period));
        values.put("obv", obv(candles));
        values.put("cmf20", cmf(candles, 20));
        values.put("stochasticK14", stochasticK(closes, highs, lows, 14));
        values.put("stochasticD3", stochasticD(closes, highs, lows, 14, 3));
        values.put("awesomeOscillator", sma(medianPrices(candles), 5) - sma(medianPrices(candles), 34));
        values.put("trueRange", trueRange(candles.get(candles.size() - 1), candles.get(candles.size() - 2)));
        values.put("volumeSma20", sma(volumes, 20));
        values.put("volumeSma50", sma(volumes, 50));
        values.put("volumeRatio20", safeDivide(last(volumes), values.get("volumeSma20")));
        values.put("priceVsVwapPercent", percentChange(lastClose, values.get("vwap48")));

        double macd = values.get("ema12") - values.get("ema26");
        double macdSignal = macdSignal(closes);
        values.put("macd", macd);
        values.put("macdSignal", macdSignal);
        values.put("macdHistogram", macd - macdSignal);

        double bbMiddle = values.get("sma20");
        double bbStd = values.get("stdDev20");
        values.put("bollingerUpper", bbMiddle + 2 * bbStd);
        values.put("bollingerMiddle", bbMiddle);
        values.put("bollingerLower", bbMiddle - 2 * bbStd);
        values.put("bollingerBandwidth", safeDivide(4 * bbStd, bbMiddle) * 100);

        Map<String, Double> dmi = dmi(candles, 14);
        values.putAll(dmi);

        List<Boolean> checks = new ArrayList<>();
        checks.add(lastClose > values.get("sma20"));
        checks.add(lastClose > values.get("sma50"));
        checks.add(lastClose > values.get("sma200"));
        checks.add(lastClose > values.get("ema20"));
        checks.add(values.get("ema20") > values.get("ema50"));
        checks.add(values.get("ema50") > values.get("ema200"));
        checks.add(values.get("sma20") > values.get("sma50"));
        checks.add(values.get("sma50") > values.get("sma200"));
        checks.add(values.get("rsi14") >= 50 && values.get("rsi14") <= 70);
        checks.add(macd > macdSignal);
        checks.add(lastClose > values.get("vwap48"));
        checks.add(lastClose > bbMiddle);
        checks.add(values.get("roc10") > 0);
        checks.add(values.get("momentum10") > 0);
        checks.add(values.get("stochasticK14") > values.get("stochasticD3"));
        checks.add(values.get("williamsR14") > -50);
        checks.add(values.get("cci20") > 0);
        checks.add(values.get("mfi14") > 50 && values.get("mfi14") < 80);
        checks.add(values.get("cmf20") > 0);
        checks.add(values.get("plusDI14") > values.get("minusDI14"));
        checks.add(values.get("awesomeOscillator") > 0);
        checks.add(lastClose > values.get("donchianMid20"));

        int bullish = (int) checks.stream().filter(Boolean::booleanValue).count();
        int bearish = checks.size() - bullish;
        String signal = bullish >= bearish ? "LONG" : "SHORT";
        int score = (int) Math.round(bullish * 100.0 / checks.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", signal);
        result.put("score", score);
        result.put("lastClose", lastClose);
        result.putAll(values);
        result.put("macdTrend", macd > macdSignal ? "BULLISH" : "BEARISH");
        result.put("bollingerPosition", lastClose > values.get("bollingerUpper") ? "ABOVE_UPPER" : lastClose < values.get("bollingerLower") ? "BELOW_LOWER" : "INSIDE_BAND");
        result.put("bullish", bullish);
        result.put("bearish", bearish);
        result.put("indicatorCount", values.size());
        result.put("indicators", values);
        result.put("source", "CALCULATED_FROM_BINANCE_OHLCV");
        return result;
    }

    private double sma(List<Double> values, int period) {
        int start = Math.max(0, values.size() - period);
        return values.subList(start, values.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double ema(List<Double> values, int period) {
        if (values.isEmpty()) return 0;
        int seed = Math.min(period, values.size());
        double multiplier = 2.0 / (period + 1);
        double value = values.subList(0, seed).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        for (int i = seed; i < values.size(); i++) value = (values.get(i) - value) * multiplier + value;
        return value;
    }

    private double macdSignal(List<Double> closes) {
        List<Double> series = new ArrayList<>();
        for (int end = 26; end <= closes.size(); end++) {
            List<Double> slice = closes.subList(0, end);
            series.add(ema(slice, 12) - ema(slice, 26));
        }
        return ema(series, 9);
    }

    private double rsi(List<Double> closes, int period) {
        double gain = 0, loss = 0;
        int start = Math.max(1, closes.size() - period);
        for (int i = start; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change >= 0) gain += change; else loss -= change;
        }
        if (loss == 0) return 100;
        return 100 - (100 / (1 + gain / loss));
    }

    private double atr(List<CryptoMarketDataService.Candle> candles, int period) {
        int start = Math.max(1, candles.size() - period);
        double total = 0;
        for (int i = start; i < candles.size(); i++) total += trueRange(candles.get(i), candles.get(i - 1));
        return total / Math.max(1, candles.size() - start);
    }

    private double trueRange(CryptoMarketDataService.Candle current, CryptoMarketDataService.Candle previous) {
        return Math.max(current.high - current.low, Math.max(Math.abs(current.high - previous.close), Math.abs(current.low - previous.close)));
    }

    private double vwap(List<CryptoMarketDataService.Candle> candles, int period) {
        int start = Math.max(0, candles.size() - period);
        double weighted = 0, volume = 0;
        for (int i = start; i < candles.size(); i++) {
            CryptoMarketDataService.Candle c = candles.get(i);
            weighted += ((c.high + c.low + c.close) / 3.0) * c.volume;
            volume += c.volume;
        }
        return volume == 0 ? 0 : weighted / volume;
    }

    private double stddev(List<Double> values, int period) {
        List<Double> slice = values.subList(Math.max(0, values.size() - period), values.size());
        double avg = slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(slice.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));
    }

    private double roc(List<Double> values, int period) {
        double previous = values.get(Math.max(0, values.size() - 1 - period));
        return percentChange(last(values), previous);
    }

    private double momentum(List<Double> values, int period) {
        return last(values) - values.get(Math.max(0, values.size() - 1 - period));
    }

    private double highest(List<Double> values, int period) {
        return values.subList(Math.max(0, values.size() - period), values.size()).stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private double lowest(List<Double> values, int period) {
        return values.subList(Math.max(0, values.size() - period), values.size()).stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    private double williamsR(List<Double> closes, List<Double> highs, List<Double> lows, int period) {
        double high = highest(highs, period), low = lowest(lows, period);
        return high == low ? 0 : -100 * (high - last(closes)) / (high - low);
    }

    private double stochasticK(List<Double> closes, List<Double> highs, List<Double> lows, int period) {
        double high = highest(highs, period), low = lowest(lows, period);
        return high == low ? 50 : 100 * (last(closes) - low) / (high - low);
    }

    private double stochasticD(List<Double> closes, List<Double> highs, List<Double> lows, int period, int smoothing) {
        List<Double> kValues = new ArrayList<>();
        for (int offset = smoothing - 1; offset >= 0; offset--) {
            int end = closes.size() - offset;
            kValues.add(stochasticK(closes.subList(0, end), highs.subList(0, end), lows.subList(0, end), period));
        }
        return sma(kValues, smoothing);
    }

    private double cci(List<CryptoMarketDataService.Candle> candles, int period) {
        List<Double> typical = candles.stream().map(c -> (c.high + c.low + c.close) / 3.0).toList();
        List<Double> slice = typical.subList(Math.max(0, typical.size() - period), typical.size());
        double mean = slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double deviation = slice.stream().mapToDouble(v -> Math.abs(v - mean)).average().orElse(0);
        return deviation == 0 ? 0 : (last(typical) - mean) / (0.015 * deviation);
    }

    private double mfi(List<CryptoMarketDataService.Candle> candles, int period) {
        int start = Math.max(1, candles.size() - period);
        double positive = 0, negative = 0;
        for (int i = start; i < candles.size(); i++) {
            CryptoMarketDataService.Candle c = candles.get(i), p = candles.get(i - 1);
            double typical = (c.high + c.low + c.close) / 3.0;
            double previous = (p.high + p.low + p.close) / 3.0;
            double flow = typical * c.volume;
            if (typical >= previous) positive += flow; else negative += flow;
        }
        return negative == 0 ? 100 : 100 - 100 / (1 + positive / negative);
    }

    private double obv(List<CryptoMarketDataService.Candle> candles) {
        double value = 0;
        for (int i = 1; i < candles.size(); i++) {
            if (candles.get(i).close > candles.get(i - 1).close) value += candles.get(i).volume;
            else if (candles.get(i).close < candles.get(i - 1).close) value -= candles.get(i).volume;
        }
        return value;
    }

    private double cmf(List<CryptoMarketDataService.Candle> candles, int period) {
        int start = Math.max(0, candles.size() - period);
        double flow = 0, volume = 0;
        for (int i = start; i < candles.size(); i++) {
            CryptoMarketDataService.Candle c = candles.get(i);
            double range = c.high - c.low;
            double multiplier = range == 0 ? 0 : ((c.close - c.low) - (c.high - c.close)) / range;
            flow += multiplier * c.volume;
            volume += c.volume;
        }
        return safeDivide(flow, volume);
    }

    private Map<String, Double> dmi(List<CryptoMarketDataService.Candle> candles, int period) {
        int start = Math.max(1, candles.size() - period);
        double plusDm = 0, minusDm = 0, tr = 0;
        for (int i = start; i < candles.size(); i++) {
            CryptoMarketDataService.Candle c = candles.get(i), p = candles.get(i - 1);
            double up = c.high - p.high, down = p.low - c.low;
            if (up > down && up > 0) plusDm += up;
            if (down > up && down > 0) minusDm += down;
            tr += trueRange(c, p);
        }
        double plus = safeDivide(plusDm * 100, tr), minus = safeDivide(minusDm * 100, tr);
        double adx = safeDivide(Math.abs(plus - minus) * 100, plus + minus);
        return Map.of("plusDI14", plus, "minusDI14", minus, "adx14", adx);
    }

    private List<Double> medianPrices(List<CryptoMarketDataService.Candle> candles) {
        return candles.stream().map(c -> (c.high + c.low) / 2.0).toList();
    }

    private double percentChange(double current, double previous) { return previous == 0 ? 0 : (current - previous) * 100 / previous; }
    private double safeDivide(double value, double divisor) { return divisor == 0 ? 0 : value / divisor; }
    private double last(List<Double> values) { return values.get(values.size() - 1); }
}
