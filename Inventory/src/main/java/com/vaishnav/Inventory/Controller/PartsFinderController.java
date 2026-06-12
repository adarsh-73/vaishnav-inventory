package com.vaishnav.Inventory.Controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/parts-finder")
public class PartsFinderController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @GetMapping("/search")
    public PartsFinderResponse search(
            @RequestParam(required = false) String make,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String part
    ) {
        String expandedPart = expandLocalPartName(part == null ? "" : part.trim());
        String query = join(make, model, year, expandedPart);
        List<PartSourceResult> results = new ArrayList<>();

        if (query.isBlank()) {
            return new PartsFinderResponse(query, expandedPart, results);
        }

        String encoded = url(query);
        addSource(results, "Boodmo Auto Browser", "Local browser automation result", "http://localhost:8090/search?make=" + url(make == null ? "" : make) + "&model=" + url(model == null ? "" : model) + "&year=" + url(year == null ? "" : year) + "&part=" + url(part == null ? "" : part), false);
        addSource(results, "Boodmo OEM Catalog", "Open vehicle catalog, then choose OEM section", "https://boodmo.com/vehicles/", false);
        addSource(results, "Boodmo Part Search", "Vehicle + part search on Boodmo", "https://boodmo.com/search/" + encoded + "/", true);
            addSource(results, "Google Market", "Google product/source search", "https://www.google.com/search?q=" + url(query + " price India"), false);
            addSource(results, "Google Images", "Product image search", "https://www.google.com/search?tbm=isch&q=" + url(query + " price India"), false);
            addSource(results, "Amazon", "Aftermarket product search", "https://www.amazon.in/s?k=" + encoded, false);
            addSource(results, "Flipkart", "Aftermarket product search", "https://www.flipkart.com/search?q=" + encoded, false);

        return new PartsFinderResponse(query, expandedPart, results);
    }

    private void addSource(List<PartSourceResult> results, String source, String title, String url, boolean fetchPrice) {
        String price = null;
        String status = source.contains("OEM Catalog")
                ? "Boodmo vehicle OEM catalog opens here. Direct section needs Boodmo internal vehicle/category IDs."
                : "Open source for live price";

        if (fetchPrice) {
            try {
                String html = fetch(url);
                price = extractPrice(html);
                if (price != null) status = "Price found from source page";
            } catch (Exception ignored) {
                status = "Source blocked direct fetch";
            }
        }

        results.add(new PartSourceResult(source, title, url, price, status));
    }

    private String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String extractPrice(String html) {
        if (html == null || html.isBlank()) return null;

        List<Pattern> patterns = List.of(
                Pattern.compile("product:price:amount\"\\s+content=\"([0-9,.]+)\"", Pattern.CASE_INSENSITIVE),
                Pattern.compile("sale_price:amount\"\\s+content=\"([0-9,.]+)\"", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(₹|Rs\\.?|INR)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE)
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String amount = matcher.group(matcher.groupCount());
                if (amount != null && !amount.isBlank()) {
                    return "Rs. " + amount.replace("Rs.", "").replace("INR", "").replace("₹", "").trim();
                }
            }
        }

        return null;
    }

    private String expandLocalPartName(String value) {
        String text = value.toLowerCase();
        if (text.matches(".*(fogg|fog|foug).*")) return value + " fog lamp fog light";
        if (text.matches(".*(dikki|dicky|boot).*")) return value + " boot tailgate";
        if (text.matches(".*(shocker|shock).*")) return value + " shock absorber strut";
        if (text.matches(".*(glass machine|window machine).*")) return value + " power window regulator";
        if (text.matches(".*(side mirror|orpvm|orvm).*")) return value + " orvm side mirror";
        if (text.matches(".*(bumper clip|clip).*")) return value + " bumper retainer clip";
        if (text.contains("bumper")) return value + " front rear bumper";
        if (text.contains("light")) return value + " headlamp tail lamp light";
        return value;
    }

    private String join(String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) values.add(part.trim());
        }
        return String.join(" ", values);
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record PartsFinderResponse(String query, String expandedPart, List<PartSourceResult> results) {}

    public record PartSourceResult(String source, String title, String url, String price, String status) {}
}
