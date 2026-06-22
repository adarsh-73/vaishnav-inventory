package com.vaishnav.Inventory.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class CryptoMacroNewsService {

    private static final long CACHE_MS = 5 * 60 * 1000L;
    private static final List<String> RISK_WORDS = List.of("hack", "exploit", "attack", "war", "ban", "lawsuit", "liquidation", "crash", "emergency", "rate hike", "recession", "default");
    private static final List<String> POSITIVE_WORDS = List.of("approval", "approved", "rate cut", "adoption", "inflow", "record high", "partnership");
    private final RestTemplate restTemplate;
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public CryptoMacroNewsService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(7_000);
        restTemplate = new RestTemplate(factory);
    }

    public synchronized Map<String, Object> getContext() {
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) return cached;

        Map<String, Object> context = new LinkedHashMap<>();
        CompletableFuture<Map<String, Object>> sp500Future = CompletableFuture.supplyAsync(() -> yahooChange("%5EGSPC"));
        CompletableFuture<Map<String, Object>> vixFuture = CompletableFuture.supplyAsync(() -> yahooChange("%5EVIX"));
        CompletableFuture<Map<String, Object>> dxyFuture = CompletableFuture.supplyAsync(() -> yahooChange("DX-Y.NYB"));
        CompletableFuture<Map<String, Object>> us10yFuture = CompletableFuture.supplyAsync(() -> yahooChange("%5ETNX"));
        CompletableFuture<Map<String, Object>> nasdaqFuture = CompletableFuture.supplyAsync(() -> yahooChange("%5EIXIC"));
        CompletableFuture<Map<String, Object>> goldFuture = CompletableFuture.supplyAsync(() -> yahooChange("GC%3DF"));
        CompletableFuture<Map<String, Object>> oilFuture = CompletableFuture.supplyAsync(() -> yahooChange("CL%3DF"));
        CompletableFuture<List<Map<String, Object>>> headlinesFuture = CompletableFuture.supplyAsync(this::gdeltHeadlines);
        CompletableFuture<List<Map<String, Object>>> coinDeskFuture = CompletableFuture.supplyAsync(() -> rssHeadlines("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/"));
        CompletableFuture<List<Map<String, Object>>> coinTelegraphFuture = CompletableFuture.supplyAsync(() -> rssHeadlines("Cointelegraph", "https://cointelegraph.com/rss"));
        CompletableFuture<List<Map<String, Object>>> decryptFuture = CompletableFuture.supplyAsync(() -> rssHeadlines("Decrypt", "https://decrypt.co/feed"));
        CompletableFuture<List<Map<String, Object>>> yahooNewsFuture = CompletableFuture.supplyAsync(() -> rssHeadlines("Yahoo Finance", "https://finance.yahoo.com/news/rssindex"));
        CompletableFuture<List<Map<String, Object>>> institutionNewsFuture = CompletableFuture.supplyAsync(() -> googleNews("Bitcoin OR crypto MicroStrategy OR Strategy OR BlackRock OR ETF OR Elon Musk OR Donald Trump"));
        CompletableFuture<List<Map<String, Object>>> globalRiskNewsFuture = CompletableFuture.supplyAsync(() -> googleNews("Bitcoin markets Federal Reserve OR China OR Japan OR Russia OR tariffs OR war OR inflation"));

        Map<String, Object> sp500 = sp500Future.join();
        Map<String, Object> vix = vixFuture.join();
        Map<String, Object> dxy = dxyFuture.join();
        Map<String, Object> us10y = us10yFuture.join();
        Map<String, Object> nasdaq = nasdaqFuture.join();
        Map<String, Object> gold = goldFuture.join();
        Map<String, Object> oil = oilFuture.join();
        List<Map<String, Object>> gdelt = headlinesFuture.join();
        List<Map<String, Object>> coinDesk = coinDeskFuture.join();
        List<Map<String, Object>> coinTelegraph = coinTelegraphFuture.join();
        List<Map<String, Object>> decrypt = decryptFuture.join();
        List<Map<String, Object>> yahooNews = yahooNewsFuture.join();
        List<Map<String, Object>> institutionNews = institutionNewsFuture.join();
        List<Map<String, Object>> globalRiskNews = globalRiskNewsFuture.join();
        List<Map<String, Object>> headlines = new ArrayList<>();
        headlines.addAll(institutionNews);
        headlines.addAll(globalRiskNews);
        headlines.addAll(yahooNews);
        headlines.addAll(gdelt);
        headlines.addAll(coinDesk);
        headlines.addAll(coinTelegraph);
        headlines.addAll(decrypt);
        LinkedHashMap<String, Map<String, Object>> uniqueHeadlines = new LinkedHashMap<>();
        for (Map<String, Object> item : headlines) {
            String title = String.valueOf(item.getOrDefault("title", "")).trim();
            if (!title.isBlank()) uniqueHeadlines.putIfAbsent(title.toLowerCase(Locale.ROOT), item);
        }
        headlines = uniqueHeadlines.values().stream().limit(80).toList();

        int negative = 0, positive = 0;
        List<Map<String, Object>> scoredHeadlines = new ArrayList<>();
        for (Map<String, Object> headline : headlines) {
            String title = String.valueOf(headline.getOrDefault("title", "")).toLowerCase(Locale.ROOT);
            int negativeHits = (int) RISK_WORDS.stream().filter(title::contains).count();
            int positiveHits = (int) POSITIVE_WORDS.stream().filter(title::contains).count();
            if (negativeHits > 0) negative++;
            if (positiveHits > 0) positive++;
            int sentiment = Math.max(-95, Math.min(95, (positiveHits - negativeHits) * 30));
            Map<String, Object> scored = new LinkedHashMap<>(headline);
            scored.put("sentiment", sentiment);
            scored.put("sentimentLabel", sentiment >= 20 ? "BULLISH" : sentiment <= -20 ? "BEARISH" : "NEUTRAL");
            scored.put("highImpact", title.matches(".*(hack|exploit|sec|etf|fed|inflation|ban|lawsuit|liquidation).*"));
            scoredHeadlines.add(scored);
        }

        double spChange = toDouble(sp500.get("changePercent"));
        double vixChange = toDouble(vix.get("changePercent"));
        double dxyChange = toDouble(dxy.get("changePercent"));
        boolean highRisk = negative >= 3 || vixChange >= 5 || spChange <= -1.5;
        String macroBias = highRisk || spChange < -0.5 || dxyChange > 0.5 ? "RISK_OFF" : spChange > 0.5 && dxyChange < 0 ? "RISK_ON" : "NEUTRAL";

        context.put("status", "LIVE_BEST_EFFORT");
        context.put("risk", highRisk ? "HIGH" : negative > positive ? "ELEVATED" : "NORMAL");
        context.put("macroBias", macroBias);
        context.put("sp500", sp500);
        context.put("vix", vix);
        context.put("dxy", dxy);
        context.put("us10y", us10y);
        context.put("nasdaq", nasdaq);
        context.put("gold", gold);
        context.put("oil", oil);
        context.put("headlineRiskCount", negative);
        context.put("headlinePositiveCount", positive);
        context.put("headlines", scoredHeadlines.stream().limit(30).toList());
        context.put("newsSentimentScore", scoredHeadlines.isEmpty() ? 0 : Math.round(scoredHeadlines.stream().mapToDouble(item -> toDouble(item.get("sentiment"))).average().orElse(0)));
        context.put("newsSource", headlines.isEmpty() ? "UNAVAILABLE" : "CRYPTONEWS_RSS_REAL");
        context.put("newsSourceStatus", Map.of(
                "CoinDesk", coinDesk.isEmpty() ? "UNAVAILABLE" : "LIVE",
                "Cointelegraph", coinTelegraph.isEmpty() ? "UNAVAILABLE" : "LIVE",
                "Decrypt", decrypt.isEmpty() ? "UNAVAILABLE" : "LIVE",
                "YahooFinance", yahooNews.isEmpty() ? "UNAVAILABLE" : "LIVE",
                "GoogleGlobalRisk", globalRiskNews.isEmpty() ? "UNAVAILABLE" : "LIVE",
                "GoogleInstitutions", institutionNews.isEmpty() ? "UNAVAILABLE" : "LIVE",
                "TheBlock", "API_OR_LICENSE_REQUIRED",
                "GDELT", gdelt.isEmpty() ? "UNAVAILABLE" : "LIVE"
        ));
        context.put("macroSource", "YAHOO_FINANCE_OR_NASDAQ_ETF_PROXY");
        context.put("fetchedAt", System.currentTimeMillis());
        context.put("futureNewsDisclaimer", "Unknown breaking news cannot be predicted; only published headlines and scheduled-event feeds can be checked.");
        cached = context;
        cachedAt = System.currentTimeMillis();
        return context;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yahooChange(String symbol) {
        try {
            Map<String, Object> root = restTemplate.getForObject("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=5d&interval=1h", Map.class);
            Map<String, Object> chart = (Map<String, Object>) root.get("chart");
            List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
            Map<String, Object> result = results.get(0);
            Map<String, Object> meta = (Map<String, Object>) result.get("meta");
            double current = toDouble(meta.get("regularMarketPrice"));
            double previous = toDouble(meta.get("chartPreviousClose"));
            double change = previous == 0 ? 0 : (current - previous) * 100 / previous;
            return Map.of("status", "LIVE", "price", current, "changePercent", change);
        } catch (Exception ignored) {
            return nasdaqMacroProxy(symbol);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nasdaqMacroProxy(String yahooSymbol) {
        String proxy = switch (yahooSymbol) {
            case "%5EGSPC" -> "SPY";
            case "%5EIXIC" -> "QQQ";
            case "DX-Y.NYB" -> "UUP";
            case "%5ETNX" -> "IEF";
            case "GC%3DF" -> "GLD";
            case "CL%3DF" -> "USO";
            case "%5EVIX" -> "VXX";
            default -> "";
        };
        if (proxy.isBlank()) return Map.of("status", "UNAVAILABLE", "price", 0, "changePercent", 0);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; VaishnavAegis/1.0)");
            Map<String, Object> root = restTemplate.exchange(
                    "https://api.nasdaq.com/api/quote/" + proxy + "/info?assetclass=etf",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (root == null || !(root.get("data") instanceof Map<?, ?> data)
                    || !(data.get("primaryData") instanceof Map<?, ?> primary)) {
                return Map.of("status", "UNAVAILABLE", "price", 0, "changePercent", 0);
            }
            double price = parseMarketNumber(primary.get("lastSalePrice"));
            double change = parseMarketNumber(primary.get("percentageChange"));
            if ("%5ETNX".equals(yahooSymbol)) change = -change;
            if (price <= 0) return Map.of("status", "UNAVAILABLE", "price", 0, "changePercent", 0);
            return Map.of(
                    "status", "LIVE_PROXY",
                    "price", price,
                    "changePercent", change,
                    "proxySymbol", proxy,
                    "source", "NASDAQ_PUBLIC_ETF_PROXY"
            );
        } catch (Exception ignored) {
            return Map.of("status", "UNAVAILABLE", "price", 0, "changePercent", 0);
        }
    }

    private double parseMarketNumber(Object raw) {
        if (raw == null) return 0;
        String cleaned = String.valueOf(raw).replaceAll("[^0-9+\\-.]", "");
        try { return Double.parseDouble(cleaned); } catch (Exception ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> gdeltHeadlines() {
        try {
            String query = URLEncoder.encode("(bitcoin OR ethereum OR crypto OR federal reserve OR inflation OR MicroStrategy OR Strategy bitcoin OR BlackRock OR IBIT OR bitcoin ETF)", StandardCharsets.UTF_8);
            String url = "https://api.gdeltproject.org/api/v2/doc/doc?query=" + query + "&mode=artlist&maxrecords=20&format=json&timespan=2h";
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            Object raw = response == null ? null : response.get("articles");
            if (!(raw instanceof List<?> articles)) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : articles) {
                if (!(item instanceof Map<?, ?> article)) continue;
                result.add(Map.of(
                        "title", String.valueOf(article.containsKey("title") ? article.get("title") : ""),
                        "url", String.valueOf(article.containsKey("url") ? article.get("url") : ""),
                        "source", String.valueOf(article.containsKey("domain") ? article.get("domain") : "GDELT"),
                        "publishedAt", String.valueOf(article.containsKey("seendate") ? article.get("seendate") : "")
                ));
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> rssHeadlines(String source, String url) {
        try {
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) return List.of();
            String trimmed = xml.stripLeading();
            if (!trimmed.startsWith("<") || trimmed.startsWith("<!DOCTYPE html") || trimmed.startsWith("<html")) return List.of();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void warning(SAXParseException error) { }
                @Override public void error(SAXParseException error) { }
                @Override public void fatalError(SAXParseException error) { }
            });
            Document document = builder.parse(new InputSource(new StringReader(trimmed)));
            NodeList items = document.getElementsByTagName("item");
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < Math.min(items.getLength(), 12); i++) {
                Element item = (Element) items.item(i);
                result.add(Map.of(
                        "title", childText(item, "title"),
                        "url", childText(item, "link"),
                        "source", source,
                        "publishedAt", childText(item, "pubDate")
                ));
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> googleNews(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return rssHeadlines("Google News Global", "https://news.google.com/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en");
    }

    private String childText(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }
}
