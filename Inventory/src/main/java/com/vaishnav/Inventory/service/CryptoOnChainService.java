package com.vaishnav.Inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CryptoOnChainService {
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private final RestTemplate restTemplate;
    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    @Value("${CRYPTOQUANT_API_KEY:}") private String cryptoQuantKey;
    @Value("${GLASSNODE_API_KEY:}") private String glassnodeKey;

    public CryptoOnChainService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(6_000);
        factory.setReadTimeout(10_000);
        restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> snapshot(String tradingSymbol) {
        String asset = tradingSymbol.replace("USDT", "").toUpperCase(Locale.ROOT);
        CachedSnapshot existing = cache.get(asset);
        if (existing != null && System.currentTimeMillis() - existing.createdAt < CACHE_MS) return existing.value;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset);
        result.put("providerStatus", Map.of(
                "CoinMetricsCommunity", "FREE_NO_KEY",
                "DefiLlama", "FREE_NO_KEY",
                "MempoolSpace", "BTC_FREE_NO_KEY",
                "CryptoQuant", cryptoQuantKey == null || cryptoQuantKey.isBlank() ? "OPTIONAL_NOT_CONFIGURED" : "CONFIGURED",
                "Glassnode", glassnodeKey == null || glassnodeKey.isBlank() ? "OPTIONAL_NOT_CONFIGURED" : "CONFIGURED"
        ));

        Map<String, Object> metrics = new LinkedHashMap<>();
        List<String> liveProviders = new ArrayList<>();
        populateCoinMetrics(asset, metrics);
        if (!metrics.isEmpty()) liveProviders.add("COIN_METRICS_COMMUNITY");

        int beforeDefiLlama = metrics.size();
        populateDefiLlama(asset, metrics);
        if (metrics.size() > beforeDefiLlama) liveProviders.add("DEFILLAMA");

        if ("BTC".equals(asset)) {
            int beforeMempool = metrics.size();
            populateMempoolSpace(metrics);
            if (metrics.size() > beforeMempool) liveProviders.add("MEMPOOL_SPACE");
        }

        if (cryptoQuantKey != null && !cryptoQuantKey.isBlank()) {
            int before = metrics.size();
            populateCryptoQuant(asset, metrics);
            if (metrics.size() > before) liveProviders.add("CRYPTOQUANT");
        }
        if (glassnodeKey != null && !glassnodeKey.isBlank()) {
            int before = metrics.size();
            populateGlassnode(asset, metrics);
            if (metrics.size() > before) liveProviders.add("GLASSNODE");
        }

        double netflow = value(metrics.get("exchangeNetflow"));
        double inflow = value(metrics.get("exchangeInflow"));
        double outflow = value(metrics.get("exchangeOutflow"));
        double minerOutflow = value(metrics.get("minerOutflow"));
        double mvrv = value(metrics.get("mvrv"));
        double sopr = value(metrics.get("sopr"));
        double tvlChange = value(metrics.get("chainTvl7dChangePercent"));
        double activeAddressChange = value(metrics.get("activeAddressesChangePercent"));
        String bias = netflow > 0 || inflow > outflow * 1.15 || minerOutflow > 0 ? "BEARISH" :
                netflow < 0 || outflow > inflow * 1.15 ? "BULLISH" : "NEUTRAL";
        if (mvrv > 3.5) bias = "BEARISH";
        else if (tvlChange > 3 || activeAddressChange > 5 || (sopr > 0 && sopr < 0.98)) bias = "BULLISH";
        else if (tvlChange < -3 || activeAddressChange < -5 || sopr > 1.08) bias = "BEARISH";

        boolean freeLive = liveProviders.stream().anyMatch(provider ->
                Set.of("COIN_METRICS_COMMUNITY", "DEFILLAMA", "MEMPOOL_SPACE").contains(provider));
        result.put("status", liveProviders.isEmpty() ? "UNAVAILABLE" : freeLive ? "LIVE_FREE" : "LIVE");
        result.put("liveProviders", liveProviders);
        result.put("metrics", metrics);
        result.put("bias", bias);
        result.put("fetchedAt", System.currentTimeMillis());
        result.put("dataPolicy", "Keyless community data is real but rate-limited. Exchange flows remain absent unless a licensed provider supplies them; no values are fabricated.");
        return cache(asset, result);
    }

    @SuppressWarnings("unchecked")
    private void populateCoinMetrics(String asset, Map<String, Object> metrics) {
        String coinMetricsAsset = asset.toLowerCase(Locale.ROOT);
        String requested = "SOPR,CapMVRVCur,NUPL,AdrActCnt,TxCnt,TxTfrValAdjUSD,TxTfrValAbUSD1MUSD,HashRate,FeeTotUSD";
        String startTime = Instant.now().minus(5, ChronoUnit.DAYS).toString();
        String url = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
                + "?assets=" + coinMetricsAsset + "&metrics=" + requested
                + "&frequency=1d&start_time=" + startTime + "&page_size=20"
                + "&ignore_unsupported_errors=true&ignore_forbidden_errors=true";
        try {
            Map<String, Object> root = restTemplate.getForObject(url, Map.class);
            if (root == null || !(root.get("data") instanceof List<?> rows) || rows.isEmpty()) return;
            if (!(rows.get(rows.size() - 1) instanceof Map<?, ?> latest)) return;
            putIfPresent(metrics, "sopr", nullableDouble(latest.get("SOPR")));
            putIfPresent(metrics, "mvrv", nullableDouble(latest.get("CapMVRVCur")));
            putIfPresent(metrics, "nupl", nullableDouble(latest.get("NUPL")));
            putIfPresent(metrics, "activeAddresses", nullableDouble(latest.get("AdrActCnt")));
            putIfPresent(metrics, "transactionCount", nullableDouble(latest.get("TxCnt")));
            putIfPresent(metrics, "adjustedTransferVolumeUsd", nullableDouble(latest.get("TxTfrValAdjUSD")));
            putIfPresent(metrics, "whaleTransferVolumeAbove1mUsd", nullableDouble(latest.get("TxTfrValAbUSD1MUSD")));
            putIfPresent(metrics, "hashRate", nullableDouble(latest.get("HashRate")));
            putIfPresent(metrics, "networkFeesUsd", nullableDouble(latest.get("FeeTotUSD")));
            if (rows.size() > 1 && rows.get(rows.size() - 2) instanceof Map<?, ?> previous) {
                putChange(metrics, "activeAddressesChangePercent", latest.get("AdrActCnt"), previous.get("AdrActCnt"));
                putChange(metrics, "transactionCountChangePercent", latest.get("TxCnt"), previous.get("TxCnt"));
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private void populateDefiLlama(String asset, Map<String, Object> metrics) {
        String chain = switch (asset) {
            case "BTC" -> "Bitcoin";
            case "ETH" -> "Ethereum";
            case "SOL" -> "Solana";
            case "BNB" -> "BSC";
            default -> null;
        };
        if (chain == null) return;
        try {
            List<?> rows = restTemplate.getForObject("https://api.llama.fi/v2/historicalChainTvl/" + chain, List.class);
            if (rows == null || rows.isEmpty() || !(rows.get(rows.size() - 1) instanceof Map<?, ?> latest)) return;
            Double current = nullableDouble(latest.get("tvl"));
            putIfPresent(metrics, "chainTvlUsd", current);
            int previousIndex = Math.max(0, rows.size() - 8);
            if (rows.get(previousIndex) instanceof Map<?, ?> previous) {
                putChange(metrics, "chainTvl7dChangePercent", current, previous.get("tvl"));
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private void populateMempoolSpace(Map<String, Object> metrics) {
        try {
            Map<String, Object> mempool = restTemplate.getForObject("https://mempool.space/api/mempool", Map.class);
            if (mempool != null) {
                putIfPresent(metrics, "mempoolTransactionCount", nullableDouble(mempool.get("count")));
                putIfPresent(metrics, "mempoolVirtualSize", nullableDouble(mempool.get("vsize")));
                putIfPresent(metrics, "mempoolTotalFeesBtc", satoshisToBtc(mempool.get("total_fee")));
            }
        } catch (Exception ignored) { }
        try {
            Map<String, Object> fees = restTemplate.getForObject("https://mempool.space/api/v1/fees/recommended", Map.class);
            if (fees != null) putIfPresent(metrics, "fastestFeeSatVb", nullableDouble(fees.get("fastestFee")));
        } catch (Exception ignored) { }
    }

    private void populateCryptoQuant(String asset, Map<String, Object> metrics) {
        String base = "https://api.cryptoquant.com/v1/" + asset.toLowerCase(Locale.ROOT);
        putIfPresent(metrics, "exchangeNetflow", cryptoQuantLatest(base + "/exchange-flows/netflow?exchange=all_exchange&window=day&limit=2", "netflow_total"));
        putIfPresent(metrics, "exchangeInflow", cryptoQuantLatest(base + "/exchange-flows/inflow?exchange=all_exchange&window=day&limit=2", "inflow_total"));
        putIfPresent(metrics, "exchangeOutflow", cryptoQuantLatest(base + "/exchange-flows/outflow?exchange=all_exchange&window=day&limit=2", "outflow_total"));
        if ("BTC".equals(asset)) {
            putIfPresent(metrics, "minerOutflow", cryptoQuantLatest(base + "/miner-flows/outflow?miner=all_miner&window=day&limit=2", "outflow_total"));
            putIfPresent(metrics, "sopr", cryptoQuantLatest(base + "/market-indicator/sopr?window=day&limit=2", "sopr"));
            putIfPresent(metrics, "mvrv", cryptoQuantLatest(base + "/market-indicator/mvrv?window=day&limit=2", "mvrv"));
            putIfPresent(metrics, "nupl", cryptoQuantLatest(base + "/network-indicator/nupl?window=day&limit=2", "nupl"));
        }
    }

    @SuppressWarnings("unchecked")
    private Double cryptoQuantLatest(String url, String field) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(cryptoQuantKey);
            Map<String, Object> root = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (root == null || !(root.get("result") instanceof Map<?, ?> rawResult)) return null;
            Object rawData = rawResult.get("data");
            if (!(rawData instanceof List<?> data) || data.isEmpty()) return null;
            Object latest = data.get(0);
            if (!(latest instanceof Map<?, ?> row)) return null;
            return nullableDouble(row.get(field));
        } catch (Exception ignored) { return null; }
    }

    private void populateGlassnode(String asset, Map<String, Object> metrics) {
        long since = Instant.now().minus(4, ChronoUnit.DAYS).getEpochSecond();
        String params = "?a=" + asset + "&i=24h&s=" + since;
        putIfAbsent(metrics, "exchangeNetflow", glassnodeLatest("/v1/metrics/transactions/transfers_volume_exchanges_net" + params));
        putIfAbsent(metrics, "exchangeInflow", glassnodeLatest("/v1/metrics/transactions/transfers_volume_to_exchanges_sum" + params));
        putIfAbsent(metrics, "exchangeOutflow", glassnodeLatest("/v1/metrics/transactions/transfers_volume_from_exchanges_sum" + params));
        if ("BTC".equals(asset)) {
            putIfAbsent(metrics, "sopr", glassnodeLatest("/v1/metrics/indicators/sopr" + params));
            putIfAbsent(metrics, "mvrv", glassnodeLatest("/v1/metrics/market/mvrv" + params));
            putIfAbsent(metrics, "nupl", glassnodeLatest("/v1/metrics/indicators/net_unrealized_profit_loss" + params));
            putIfAbsent(metrics, "minerOutflow", glassnodeLatest("/v1/metrics/transactions/transfers_volume_miners_to_exchanges_all" + params));
        }
    }

    private Double glassnodeLatest(String path) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", glassnodeKey);
            List<?> rows = restTemplate.exchange("https://api.glassnode.com" + path, HttpMethod.GET, new HttpEntity<>(headers), List.class).getBody();
            if (rows == null || rows.isEmpty() || !(rows.get(rows.size() - 1) instanceof Map<?, ?> row)) return null;
            return nullableDouble(row.get("v"));
        } catch (Exception ignored) { return null; }
    }

    private void putIfPresent(Map<String, Object> map, String key, Double value) { if (value != null) map.put(key, value); }
    private void putIfAbsent(Map<String, Object> map, String key, Double value) { if (!map.containsKey(key) && value != null) map.put(key, value); }
    private void putChange(Map<String, Object> map, String key, Object currentRaw, Object previousRaw) {
        Double current = nullableDouble(currentRaw);
        Double previous = nullableDouble(previousRaw);
        if (current != null && previous != null && previous != 0) map.put(key, ((current - previous) / previous) * 100.0);
    }
    private Double satoshisToBtc(Object raw) { Double sats = nullableDouble(raw); return sats == null ? null : sats / 100_000_000.0; }
    private Double nullableDouble(Object raw) { try { return raw == null ? null : Double.parseDouble(String.valueOf(raw)); } catch (Exception ignored) { return null; } }
    private double value(Object raw) { Double number = nullableDouble(raw); return number == null ? 0 : number; }
    private Map<String, Object> cache(String asset, Map<String, Object> result) { cache.put(asset, new CachedSnapshot(System.currentTimeMillis(), result)); return result; }
    private record CachedSnapshot(long createdAt, Map<String, Object> value) {}
}
