package com.vaishnav.Inventory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CryptoAiConsensusService {

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final String INSTRUCTIONS = "You are a conservative crypto market risk reviewer. Analyze only the supplied real market snapshot. Do not invent data. Return strict JSON with signal LONG, SHORT, or NO_TRADE; confidence 0-100; and a short reason. Prefer NO_TRADE when evidence conflicts. This is paper-trading analysis, not a profit guarantee.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final Map<String, CachedConsensus> cache = new ConcurrentHashMap<>();

    @Value("${OPENAI_API_KEY:}") private String openAiKey;
    @Value("${OPENAI_MODEL:gpt-5.5}") private String openAiModel;
    @Value("${GEMINI_API_KEY:}") private String geminiKey;
    @Value("${GEMINI_MODEL:gemini-3.5-flash}") private String geminiModel;
    @Value("${DEEPSEEK_API_KEY:}") private String deepSeekKey;
    @Value("${DEEPSEEK_MODEL:deepseek-chat}") private String deepSeekModel;
    @Value("${GROQ_API_KEY:}") private String groqKey;
    @Value("${GROQ_MODEL:llama-3.3-70b-versatile}") private String groqModel;
    @Value("${CEREBRAS_API_KEY:}") private String cerebrasKey;
    @Value("${CEREBRAS_MODEL:gpt-oss-120b}") private String cerebrasModel;
    @Value("${MISTRAL_API_KEY:}") private String mistralKey;
    @Value("${MISTRAL_MODEL:mistral-small-latest}") private String mistralModel;

    public CryptoAiConsensusService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8_000);
        factory.setReadTimeout(25_000);
        restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> analyze(String symbol, Map<String, Object> snapshot) {
        String cacheKey = symbol;
        CachedConsensus existing = cache.get(cacheKey);
        if (existing != null && System.currentTimeMillis() - existing.createdAt < CACHE_MS) return existing.value;

        List<Map<String, Object>> votes = new ArrayList<>();
        String prompt;
        try {
            prompt = "Symbol: " + symbol + "\nReal market snapshot:\n" + objectMapper.writeValueAsString(snapshot);
        } catch (Exception error) {
            prompt = "Symbol: " + symbol + "\nSnapshot unavailable";
        }

        votes.add(openAiKey.isBlank() ? notConfigured("ChatGPT", "OPENAI_API_KEY") : callOpenAi(prompt));
        votes.add(geminiKey.isBlank() ? notConfigured("Gemini", "GEMINI_API_KEY") : callGemini(prompt));
        votes.add(deepSeekKey.isBlank() ? notConfigured("DeepSeek", "DEEPSEEK_API_KEY") : callDeepSeek(prompt));
        votes.add(groqKey.isBlank() ? notConfigured("Groq Llama", "GROQ_API_KEY") : callOpenAiCompatible("Groq Llama", groqModel, groqKey, "https://api.groq.com/openai/v1/chat/completions", prompt));
        votes.add(cerebrasKey.isBlank() ? notConfigured("Cerebras GPT-OSS", "CEREBRAS_API_KEY") : callOpenAiCompatible("Cerebras GPT-OSS", cerebrasModel, cerebrasKey, "https://api.cerebras.ai/v1/chat/completions", prompt));
        votes.add(mistralKey.isBlank() ? notConfigured("Mistral", "MISTRAL_API_KEY") : callOpenAiCompatible("Mistral", mistralModel, mistralKey, "https://api.mistral.ai/v1/chat/completions", prompt));

        List<Map<String, Object>> liveVotes = votes.stream().filter(v -> "LIVE".equals(v.get("status"))).toList();
        long longs = liveVotes.stream().filter(v -> "LONG".equals(v.get("signal"))).count();
        long shorts = liveVotes.stream().filter(v -> "SHORT".equals(v.get("signal"))).count();
        long noTrades = liveVotes.size() - longs - shorts;
        String consensus = liveVotes.isEmpty() ? "NOT_CONFIGURED" : longs > shorts && longs > noTrades ? "LONG" : shorts > longs && shorts > noTrades ? "SHORT" : "NO_TRADE";
        double confidence = liveVotes.stream().mapToDouble(v -> toDouble(v.get("confidence"))).average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", consensus);
        result.put("confidence", Math.round(confidence));
        result.put("configuredProviders", liveVotes.size());
        result.put("requiredForConsensus", 1);
        result.put("weighting", "EQUAL_WEIGHT_PER_LIVE_PROVIDER");
        result.put("tieRule", "NO_TRADE");
        result.put("votes", votes);
        result.put("source", liveVotes.isEmpty() ? "NO_LLM_KEYS" : "REAL_PROVIDER_APIS");
        result.put("cachedForSeconds", CACHE_MS / 1000);
        cache.put(cacheKey, new CachedConsensus(System.currentTimeMillis(), result));
        return result;
    }

    private Map<String, Object> callOpenAi(String prompt) {
        try {
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(openAiKey);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", openAiModel);
            body.put("reasoning", Map.of("effort", "low"));
            body.put("instructions", INSTRUCTIONS);
            body.put("input", prompt);
            body.put("max_output_tokens", 250);
            JsonNode root = exchange("https://api.openai.com/v1/responses", headers, body);
            StringBuilder text = new StringBuilder();
            for (JsonNode output : root.path("output")) {
                if (!"message".equals(output.path("type").asText())) continue;
                for (JsonNode content : output.path("content")) if (content.has("text")) text.append(content.path("text").asText());
            }
            return parseVote("ChatGPT", openAiModel, text.toString());
        } catch (Exception error) {
            return providerError("ChatGPT", openAiModel, error);
        }
    }

    private Map<String, Object> callGemini(String prompt) {
        try {
            HttpHeaders headers = jsonHeaders();
            headers.set("x-goog-api-key", geminiKey);
            Map<String, Object> body = Map.of(
                    "system_instruction", Map.of("parts", List.of(Map.of("text", INSTRUCTIONS))),
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("responseMimeType", "application/json", "maxOutputTokens", 250)
            );
            JsonNode root = exchange("https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent", headers, body);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            return parseVote("Gemini", geminiModel, text);
        } catch (Exception error) {
            return providerError("Gemini", geminiModel, error);
        }
    }

    private Map<String, Object> callDeepSeek(String prompt) {
        try {
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(deepSeekKey);
            Map<String, Object> body = Map.of(
                    "model", deepSeekModel,
                    "messages", List.of(Map.of("role", "system", "content", INSTRUCTIONS), Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", 250,
                    "stream", false
            );
            JsonNode root = exchange("https://api.deepseek.com/chat/completions", headers, body);
            String text = root.path("choices").path(0).path("message").path("content").asText();
            return parseVote("DeepSeek", deepSeekModel, text);
        } catch (Exception error) {
            return providerError("DeepSeek", deepSeekModel, error);
        }
    }

    private Map<String, Object> callOpenAiCompatible(String provider, String model, String apiKey, String url, String prompt) {
        try {
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(apiKey);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "system", "content", INSTRUCTIONS), Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", 250,
                    "temperature", 0.1,
                    "stream", false
            );
            JsonNode root = exchange(url, headers, body);
            String text = root.path("choices").path(0).path("message").path("content").asText();
            return parseVote(provider, model, text);
        } catch (Exception error) {
            return providerError(provider, model, error);
        }
    }

    private JsonNode exchange(String url, HttpHeaders headers, Object body) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return objectMapper.readTree(response.getBody());
    }

    private Map<String, Object> parseVote(String provider, String model, String raw) throws Exception {
        String cleaned = raw == null ? "" : raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{'), end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Provider did not return JSON");
        Map<String, Object> parsed = objectMapper.readValue(cleaned.substring(start, end + 1), new TypeReference<>() {});
        String signal = String.valueOf(parsed.getOrDefault("signal", "NO_TRADE")).toUpperCase(Locale.ROOT);
        if (!Set.of("LONG", "SHORT", "NO_TRADE").contains(signal)) signal = "NO_TRADE";
        double confidence = Math.max(0, Math.min(100, toDouble(parsed.getOrDefault("confidence", 0))));
        Map<String, Object> vote = new LinkedHashMap<>();
        vote.put("ai", provider);
        vote.put("model", model);
        vote.put("status", "LIVE");
        vote.put("signal", signal);
        vote.put("confidence", Math.round(confidence));
        vote.put("reason", String.valueOf(parsed.getOrDefault("reason", "No reason returned")));
        return vote;
    }

    private Map<String, Object> notConfigured(String provider, String keyName) {
        return Map.of("ai", provider, "status", "NOT_CONFIGURED", "signal", "NO_VOTE", "confidence", 0, "reason", keyName + " required");
    }

    private Map<String, Object> providerError(String provider, String model, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 180) message = message.substring(0, 180);
        return Map.of("ai", provider, "model", model, "status", "ERROR", "signal", "NO_VOTE", "confidence", 0, "reason", message);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    private record CachedConsensus(long createdAt, Map<String, Object> value) {}
}
