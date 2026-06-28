package com.vaishnav.Inventory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.time.Instant;

@Service
public class CryptoAiConsensusService {

    private static final long CACHE_MS = 20 * 60 * 1000L;
    private static final int MIN_LIVE_PROVIDERS = 2;
    private static final int MIN_LEARNING_LIVE_PROVIDERS = 1;
    private static final int MAX_PROMPT_CHARS = 9_000;
    private static final String INSTRUCTIONS = "You are a conservative crypto paper-trading risk reviewer. Analyze every supplied engine: market microstructure, technical timeframes, derivatives, liquidations, macro, published news, attributed whales, on-chain metrics, historical OHLCV analogs, stored event outcomes, risk policy, and data readiness. Compare the current numerical chart features with prior analogs, but never assume history must repeat. Strategy/MicroStrategy, BlackRock/IBIT, ETF and macro events may influence a decision only when present in attributed published data; never infer rumours or invent a pump/dump cause. A CME gap is only context when a verified CME futures feed explicitly supplies it; never score an educational-only gap. Never invent missing data. Return strict JSON with signal LONG, SHORT, or NO_TRADE; confidence 0-100; horizon SCALP, INTRADAY, or SWING; short reason; riskFlags array; and dataGaps array. Prefer NO_TRADE when mandatory data is missing or evidence conflicts. The deterministic risk engine has final authority.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final Map<String, CachedConsensus> cache = new ConcurrentHashMap<>();
    private final Map<String, ProviderHealth> providerHealth = new ConcurrentHashMap<>();

    @Value("${OPENAI_API_KEY:}") private String openAiKey;
    @Value("${OPENAI_MODEL:gpt-5.5}") private String openAiModel;
    @Value("${GEMINI_API_KEY:${GOOGLE_API_KEY:${GOOGLE_GEMINI_API_KEY:}}}") private String geminiKey;
    @Value("${GEMINI_MODEL:gemini-3.5-flash}") private String geminiModel;
    @Value("${ANTHROPIC_API_KEY:}") private String anthropicKey;
    @Value("${ANTHROPIC_MODEL:claude-sonnet-4-20250514}") private String anthropicModel;
    @Value("${DEEPSEEK_API_KEY:}") private String deepSeekKey;
    @Value("${DEEPSEEK_MODEL:deepseek-chat}") private String deepSeekModel;
    @Value("${GROQ_API_KEY:}") private String groqKey;
    @Value("${GROQ_MODEL:llama-3.3-70b-versatile}") private String groqModel;
    @Value("${CEREBRAS_API_KEY:}") private String cerebrasKey;
    @Value("${CEREBRAS_MODEL:gpt-oss-120b}") private String cerebrasModel;
    @Value("${MISTRAL_API_KEY:}") private String mistralKey;
    @Value("${MISTRAL_MODEL:mistral-small-latest}") private String mistralModel;
    @Value("${OPENROUTER_API_KEY:}") private String openRouterKey;
    @Value("${OPENROUTER_MODEL:openrouter/free}") private String openRouterModel;
    @Value("${OPENROUTER_MODELS:}") private String openRouterModels;

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
            String json = objectMapper.writeValueAsString(snapshot);
            if (json.length() > MAX_PROMPT_CHARS) {
                json = json.substring(0, MAX_PROMPT_CHARS)
                        + "\n...TRUNCATED_BY_BACKEND_PROMPT_GUARD. Use only visible data and mark missing details in dataGaps.";
            }
            prompt = "Symbol: " + symbol
                    + "\nPaper-trading review only. Return JSON only."
                    + "\nReal compact market snapshot:\n" + json;
        } catch (Exception error) {
            prompt = "Symbol: " + symbol + "\nSnapshot unavailable";
        }

        double preAiQuality = Math.max(toDouble(snapshot.get("preAiLongScore")), toDouble(snapshot.get("preAiShortScore")));
        int targetLiveProviders = "AI_PROVIDER_HEALTHCHECK".equals(symbol) || preAiQuality >= 65
                ? MIN_LIVE_PROVIDERS : MIN_LEARNING_LIVE_PROVIDERS;
        votes.addAll(collectProviderVotes(symbol, prompt, targetLiveProviders));

        List<Map<String, Object>> liveVotes = votes.stream().filter(v -> "LIVE".equals(v.get("status"))).toList();
        long longs = liveVotes.stream().filter(v -> "LONG".equals(v.get("signal"))).count();
        long shorts = liveVotes.stream().filter(v -> "SHORT".equals(v.get("signal"))).count();
        long noTrades = liveVotes.size() - longs - shorts;
        String majoritySignal = longs > shorts && longs > noTrades ? "LONG" : shorts > longs && shorts > noTrades ? "SHORT" : "NO_TRADE";
        boolean geminiLive = liveVotes.stream().anyMatch(v -> "Gemini".equals(v.get("ai")));
        boolean strictQuorumReady = liveVotes.size() >= MIN_LIVE_PROVIDERS;
        boolean learningQuorumReady = liveVotes.size() >= MIN_LEARNING_LIVE_PROVIDERS;
        String consensus = learningQuorumReady ? majoritySignal : "NO_TRADE";
        double confidence = liveVotes.stream()
                .filter(v -> majoritySignal.equals(v.get("signal")))
                .mapToDouble(v -> toDouble(v.get("confidence")))
                .average().orElse(0);
        double voteTotal = Math.max(1, liveVotes.size());
        double longConfidenceTotal = liveVotes.stream().filter(v -> "LONG".equals(v.get("signal"))).mapToDouble(v -> toDouble(v.get("confidence"))).sum();
        double shortConfidenceTotal = liveVotes.stream().filter(v -> "SHORT".equals(v.get("signal"))).mapToDouble(v -> toDouble(v.get("confidence"))).sum();
        long aiLongAverage = Math.round(longConfidenceTotal / voteTotal);
        long aiShortAverage = Math.round(shortConfidenceTotal / voteTotal);
        long aiNoTradeAverage = Math.max(0, 100 - aiLongAverage - aiShortAverage);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", consensus);
        result.put("confidence", Math.round(confidence));
        result.put("configuredProviders", liveVotes.size());
        result.put("liveProviders", liveVotes.size());
        result.put("requiredForConsensus", MIN_LIVE_PROVIDERS);
        result.put("minimumForLearningTrade", MIN_LEARNING_LIVE_PROVIDERS);
        result.put("geminiRequired", false);
        result.put("geminiLive", geminiLive);
        result.put("strictQuorumReady", strictQuorumReady);
        result.put("quorumReady", learningQuorumReady);
        result.put("consensusStatus", strictQuorumReady ? "READY_FULL_QUORUM" : learningQuorumReady ? "ONE_AI_LEARNING_MODE" : "NEED_ONE_LIVE_AI");
        result.put("longVotePercent", Math.round(longs * 100.0 / voteTotal));
        result.put("shortVotePercent", Math.round(shorts * 100.0 / voteTotal));
        result.put("noTradeVotePercent", Math.round(noTrades * 100.0 / voteTotal));
        result.put("aiLongAveragePercent", aiLongAverage);
        result.put("aiShortAveragePercent", aiShortAverage);
        result.put("aiNoTradeAveragePercent", aiNoTradeAverage);
        result.put("liveProviderNames", liveVotes.stream().map(v -> v.get("ai")).toList());
        result.put("weighting", "EQUAL_WEIGHT_PER_LIVE_PROVIDER");
        result.put("tieRule", "NO_TRADE");
        result.put("votes", votes);
        result.put("source", liveVotes.isEmpty() ? "NO_LIVE_LLM_APIS" : "REAL_PROVIDER_APIS");
        result.put("marketReviewPerformed", true);
        result.put("providerCallsTarget", targetLiveProviders);
        result.put("quotaPolicy", "ROTATE_CONFIGURED_PROVIDERS_AND_STOP_AFTER_REQUIRED_LIVE_VOTES");
        result.put("cachedForSeconds", CACHE_MS / 1000);
        cache.put(cacheKey, new CachedConsensus(System.currentTimeMillis(), result));
        return result;
    }

    public Map<String, Object> providerStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("OpenAI", providerConfig("ChatGPT", !openAiKey.isBlank(), openAiModel, "OPENAI_API_KEY"));
        status.put("Gemini", providerConfig("Gemini", !geminiKey.isBlank(), geminiModel, "GEMINI_API_KEY / GOOGLE_API_KEY"));
        status.put("Claude", providerConfig("Claude", !anthropicKey.isBlank(), anthropicModel, "ANTHROPIC_API_KEY"));
        status.put("DeepSeek", providerConfig("DeepSeek", !deepSeekKey.isBlank(), deepSeekModel, "DEEPSEEK_API_KEY"));
        status.put("Groq", providerConfig("Groq Llama", !groqKey.isBlank(), groqModel, "GROQ_API_KEY"));
        status.put("Cerebras", providerConfig("Cerebras GPT-OSS", !cerebrasKey.isBlank(), cerebrasModel, "CEREBRAS_API_KEY"));
        status.put("Mistral", providerConfig("Mistral", !mistralKey.isBlank(), mistralModel, "MISTRAL_API_KEY"));
        status.put("OpenRouter", providerConfig("OpenRouter", !openRouterKey.isBlank(), activeOpenRouterModelsLabel(), "OPENROUTER_API_KEY"));
        return status;
    }

    public Map<String, Object> skippedByPrefilter() {
        List<String> liveNames = healthyProviderNames();
        int configured = configuredProviderCount();
        boolean strictQuorumReady = liveNames.size() >= MIN_LIVE_PROVIDERS;
        boolean learningQuorumReady = liveNames.size() >= MIN_LEARNING_LIVE_PROVIDERS;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", "NO_TRADE");
        result.put("confidence", 0);
        result.put("configuredProviders", configured);
        result.put("liveProviders", liveNames.size());
        result.put("requiredForConsensus", MIN_LIVE_PROVIDERS);
        result.put("minimumForLearningTrade", MIN_LEARNING_LIVE_PROVIDERS);
        result.put("geminiRequired", false);
        result.put("geminiLive", liveNames.contains("Gemini"));
        result.put("strictQuorumReady", strictQuorumReady);
        result.put("quorumReady", learningQuorumReady);
        result.put("consensusStatus", "SKIPPED_PREFILTER_LOW_QUALITY");
        result.put("longVotePercent", 0);
        result.put("shortVotePercent", 0);
        result.put("noTradeVotePercent", 100);
        result.put("aiLongAveragePercent", 0);
        result.put("aiShortAveragePercent", 0);
        result.put("aiNoTradeAveragePercent", 100);
        result.put("liveProviderNames", liveNames);
        result.put("weighting", "EQUAL_WEIGHT_PER_LIVE_PROVIDER");
        result.put("tieRule", "NO_TRADE");
        result.put("votes", List.of());
        result.put("source", "AI_CALL_SKIPPED_TO_PROTECT_FREE_QUOTA");
        result.put("marketReviewPerformed", false);
        result.put("cachedForSeconds", 0);
        return result;
    }

    public void clearCache() { cache.clear(); }

    public Map<String, Object> verifyProvidersNow() {
        String healthSymbol = "AI_PROVIDER_HEALTHCHECK";
        cache.remove(healthSymbol);
        Map<String, Object> result = analyze(healthSymbol, Map.of(
                "purpose", "Verify that configured provider APIs return a parseable trade-review JSON response",
                "paperOnly", true,
                "candidateSignal", "NO_TRADE",
                "dataReadiness", "HEALTHCHECK_NOT_A_MARKET_DECISION"
        ));
        result.put("verificationOnly", true);
        result.put("verifiedAt", Instant.now().toString());
        return result;
    }

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 60_000)
    public void keepProviderQuorumWarm() {
        try { verifyProvidersNow(); } catch (Exception ignored) { }
    }

    private int configuredProviderCount() {
        int count = 0;
        if (!openAiKey.isBlank()) count++;
        if (!geminiKey.isBlank()) count++;
        if (!anthropicKey.isBlank()) count++;
        if (!deepSeekKey.isBlank()) count++;
        if (!groqKey.isBlank()) count++;
        if (!cerebrasKey.isBlank()) count++;
        if (!mistralKey.isBlank()) count++;
        if (!openRouterKey.isBlank()) count++;
        return count;
    }

    private List<String> healthyProviderNames() {
        return providerHealth.entrySet().stream()
                .filter(entry -> "LIVE".equals(entry.getValue().status))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private Map<String, Object> providerConfig(String provider, boolean configured, String model, String keyName) {
        ProviderHealth health = providerHealth.get(provider);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("configured", configured);
        value.put("model", model);
        value.put("requiredEnvironmentVariable", keyName);
        value.put("runtimeStatus", !configured ? "NOT_CONFIGURED" : health == null ? "NOT_TESTED" : health.status);
        value.put("lastCheckedAt", health == null ? null : health.checkedAt);
        value.put("lastError", health == null ? "" : health.error);
        value.put("cooldownUntil", health == null || health.cooldownUntil <= System.currentTimeMillis() ? null : Instant.ofEpochMilli(health.cooldownUntil).toString());
        return value;
    }

    private Map<String, Object> callOpenAi(String prompt) {
        Map<String, Object> cooldown = cooldownVote("ChatGPT", openAiModel);
        if (cooldown != null) return cooldown;
        try {
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(openAiKey);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", openAiModel);
            body.put("reasoning", Map.of("effort", "low"));
            body.put("instructions", INSTRUCTIONS);
            body.put("input", prompt);
            body.put("max_output_tokens", 250);
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "trade_vote",
                    "strict", true,
                    "schema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "signal", Map.of("type", "string", "enum", List.of("LONG", "SHORT", "NO_TRADE")),
                                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 100),
                                    "horizon", Map.of("type", "string", "enum", List.of("SCALP", "INTRADAY", "SWING")),
                                    "reason", Map.of("type", "string"),
                                    "riskFlags", Map.of("type", "array", "items", Map.of("type", "string")),
                                    "dataGaps", Map.of("type", "array", "items", Map.of("type", "string"))
                            ),
                            "required", List.of("signal", "confidence", "horizon", "reason", "riskFlags", "dataGaps"),
                            "additionalProperties", false
                    )
            )));
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

    private List<Map<String, Object>> collectProviderVotes(String symbol, String prompt, int targetLiveProviders) {
        List<ProviderCandidate> candidates = List.of(
                new ProviderCandidate("Mistral", !mistralKey.isBlank(), "MISTRAL_API_KEY",
                        () -> callOpenAiCompatible("Mistral", mistralModel, mistralKey, "https://api.mistral.ai/v1/chat/completions", prompt)),
                new ProviderCandidate("OpenRouter", !openRouterKey.isBlank(), "OPENROUTER_API_KEY",
                        () -> callOpenRouter(prompt)),
                new ProviderCandidate("Cerebras GPT-OSS", !cerebrasKey.isBlank(), "CEREBRAS_API_KEY",
                        () -> callOpenAiCompatible("Cerebras GPT-OSS", cerebrasModel, cerebrasKey, "https://api.cerebras.ai/v1/chat/completions", prompt)),
                new ProviderCandidate("Groq Llama", !groqKey.isBlank(), "GROQ_API_KEY",
                        () -> callOpenAiCompatible("Groq Llama", groqModel, groqKey, "https://api.groq.com/openai/v1/chat/completions", prompt)),
                new ProviderCandidate("Gemini", !geminiKey.isBlank(), "GEMINI_API_KEY",
                        () -> callGemini(prompt)),
                new ProviderCandidate("DeepSeek", !deepSeekKey.isBlank(), "DEEPSEEK_API_KEY",
                        () -> callDeepSeek(prompt)),
                new ProviderCandidate("ChatGPT", !openAiKey.isBlank(), "OPENAI_API_KEY",
                        () -> callOpenAi(prompt)),
                new ProviderCandidate("Claude", !anthropicKey.isBlank(), "ANTHROPIC_API_KEY",
                        () -> callAnthropic(prompt))
        );

        int start = "AI_PROVIDER_HEALTHCHECK".equals(symbol) ? 0 : Math.floorMod(symbol.hashCode(), candidates.size());
        List<Map<String, Object>> votes = new ArrayList<>();
        int liveVotes = 0;
        for (int offset = 0; offset < candidates.size(); offset++) {
            ProviderCandidate candidate = candidates.get((start + offset) % candidates.size());
            if (!candidate.configured) {
                votes.add(notConfigured(candidate.name, candidate.keyName));
                continue;
            }
            if (liveVotes >= targetLiveProviders) {
                votes.add(quotaProtected(candidate.name));
                continue;
            }
            Map<String, Object> vote = candidate.caller.get();
            votes.add(vote);
            if ("LIVE".equals(vote.get("status"))) liveVotes++;
        }
        return votes;
    }

    private Map<String, Object> quotaProtected(String provider) {
        return Map.of(
                "ai", provider,
                "status", "QUOTA_PROTECTED",
                "verification", "API_CALL_SKIPPED_QUORUM_READY",
                "signal", "NO_VOTE",
                "confidence", 0,
                "reason", "Required live AI votes already collected; free quota preserved"
        );
    }

    private Map<String, Object> callGemini(String prompt) {
        Exception lastError = null;
        List<String> models = new ArrayList<>();
        for (String model : List.of(geminiModel, "gemini-2.5-flash-lite", "gemini-2.0-flash-lite")) {
            if (!models.contains(model)) models.add(model);
        }
        for (String model : models) {
            try {
                HttpHeaders headers = jsonHeaders();
                headers.set("x-goog-api-key", geminiKey);
                Map<String, Object> responseSchema = Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                                "signal", Map.of("type", "STRING", "enum", List.of("LONG", "SHORT", "NO_TRADE")),
                                "confidence", Map.of("type", "NUMBER", "minimum", 0, "maximum", 100),
                                "horizon", Map.of("type", "STRING", "enum", List.of("SCALP", "INTRADAY", "SWING")),
                                "reason", Map.of("type", "STRING"),
                                "riskFlags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                                "dataGaps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                        ),
                        "required", List.of("signal", "confidence", "horizon", "reason", "riskFlags", "dataGaps")
                );
                Map<String, Object> body = Map.of(
                        "system_instruction", Map.of("parts", List.of(Map.of("text", INSTRUCTIONS))),
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                        "generationConfig", Map.of(
                                "responseMimeType", "application/json",
                                "responseSchema", responseSchema,
                                "maxOutputTokens", 800,
                                "temperature", 0.1
                        )
                );
                JsonNode root = exchange("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", headers, body);
                String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
                Map<String, Object> vote = parseVote("Gemini", model, text);
                vote.put("fallbackUsed", !model.equals(geminiModel));
                return vote;
            } catch (Exception error) {
                lastError = error;
            }
        }
        return providerError("Gemini", String.join(" -> ", models), lastError == null ? new RuntimeException("All Gemini models failed") : lastError);
    }

    private Map<String, Object> callAnthropic(String prompt) {
        Map<String, Object> cooldown = cooldownVote("Claude", anthropicModel);
        if (cooldown != null) return cooldown;
        try {
            HttpHeaders headers = jsonHeaders();
            headers.set("x-api-key", anthropicKey);
            headers.set("anthropic-version", "2023-06-01");
            Map<String, Object> body = Map.of(
                    "model", anthropicModel,
                    "max_tokens", 300,
                    "system", INSTRUCTIONS,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );
            JsonNode root = exchange("https://api.anthropic.com/v1/messages", headers, body);
            String text = root.path("content").path(0).path("text").asText();
            return parseVote("Claude", anthropicModel, text);
        } catch (Exception error) {
            return providerError("Claude", anthropicModel, error);
        }
    }

    private Map<String, Object> callDeepSeek(String prompt) {
        Map<String, Object> cooldown = cooldownVote("DeepSeek", deepSeekModel);
        if (cooldown != null) return cooldown;
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
        Map<String, Object> cooldown = cooldownVote(provider, model);
        if (cooldown != null) return cooldown;
        try {
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(apiKey);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "system", "content", INSTRUCTIONS), Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", 600,
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

    private Map<String, Object> callOpenRouter(String prompt) {
        Map<String, Object> cooldown = cooldownVote("OpenRouter", activeOpenRouterModelsLabel());
        if (cooldown != null) return cooldown;

        Exception lastError = null;
        List<String> models = activeOpenRouterModels();
        for (String model : models) {
            try {
                return callOpenRouterModel(model, prompt, true);
            } catch (Exception firstError) {
                lastError = firstError;
                try {
                    return callOpenRouterModel(model, prompt, false);
                } catch (Exception fallbackError) {
                    lastError = fallbackError;
                }
            }
        }
        return providerError("OpenRouter", String.join(" -> ", models), lastError == null ? new RuntimeException("All OpenRouter models failed") : lastError);
    }

    private Map<String, Object> callOpenRouterModel(String model, String prompt, boolean jsonMode) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(openRouterKey);
        headers.set("HTTP-Referer", "https://vaishnav-inventory-frontend.onrender.com");
        headers.set("X-Title", "Vaishnav Inventory Crypto Paper Trader");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", INSTRUCTIONS),
                Map.of("role", "user", "content", prompt + (jsonMode ? "" : "\nReturn only one compact JSON object."))
        ));
        if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", 500);
        body.put("temperature", 0.1);
        body.put("stream", false);
        JsonNode root = exchange("https://openrouter.ai/api/v1/chat/completions", headers, body);
        String text = root.path("choices").path(0).path("message").path("content").asText();
        Map<String, Object> vote = parseVote("OpenRouter", model, text);
        vote.put("jsonMode", jsonMode);
        return vote;
    }

    private List<String> activeOpenRouterModels() {
        List<String> models = new ArrayList<>();
        if (openRouterModels != null && !openRouterModels.isBlank()) {
            for (String model : openRouterModels.split(",")) {
                String trimmed = model.trim();
                if (!trimmed.isBlank() && !models.contains(trimmed)) models.add(trimmed);
            }
        }
        if (openRouterModel != null && !openRouterModel.isBlank() && !models.contains(openRouterModel.trim())) {
            models.add(openRouterModel.trim());
        }
        for (String fallback : List.of(
                "openrouter/free",
                "openai/gpt-oss-120b:free",
                "meta-llama/llama-3.3-70b-instruct:free",
                "qwen/qwen3-next-80b-a3b-instruct:free",
                "google/gemma-4-31b-it:free"
        )) {
            if (!models.contains(fallback)) models.add(fallback);
        }
        return models;
    }

    private String activeOpenRouterModelsLabel() {
        return String.join(" -> ", activeOpenRouterModels());
    }

    private JsonNode exchange(String url, HttpHeaders headers, Object body) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return objectMapper.readTree(response.getBody());
    }

    private Map<String, Object> parseVote(String provider, String model, String raw) throws Exception {
        String cleaned = raw == null ? "" : raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{'), end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            String preview = cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
            throw new IllegalArgumentException("Provider did not return complete JSON" + (preview.isBlank() ? " (empty response)" : ": " + preview));
        }
        Map<String, Object> parsed = objectMapper.readValue(cleaned.substring(start, end + 1), new TypeReference<>() {});
        String signal = String.valueOf(parsed.getOrDefault("signal", "NO_TRADE")).toUpperCase(Locale.ROOT);
        if (!Set.of("LONG", "SHORT", "NO_TRADE").contains(signal)) signal = "NO_TRADE";
        double confidence = Math.max(0, Math.min(100, toDouble(parsed.getOrDefault("confidence", 0))));
        Map<String, Object> vote = new LinkedHashMap<>();
        vote.put("ai", provider);
        vote.put("model", model);
        vote.put("status", "LIVE");
        vote.put("verification", "VERIFIED_PROVIDER_API_RESPONSE");
        vote.put("verifiedAt", Instant.now().toString());
        vote.put("signal", signal);
        vote.put("confidence", Math.round(confidence));
        vote.put("reason", String.valueOf(parsed.getOrDefault("reason", "No reason returned")));
        vote.put("horizon", String.valueOf(parsed.getOrDefault("horizon", "INTRADAY")));
        vote.put("riskFlags", parsed.getOrDefault("riskFlags", List.of()));
        vote.put("dataGaps", parsed.getOrDefault("dataGaps", List.of()));
        providerHealth.put(provider, new ProviderHealth("LIVE", Instant.now().toString(), "", 0));
        return vote;
    }

    private Map<String, Object> notConfigured(String provider, String keyName) {
        return Map.of("ai", provider, "status", "NOT_CONFIGURED", "verification", "NO_API_CALL", "signal", "NO_VOTE", "confidence", 0, "reason", keyName + " required");
    }

    private Map<String, Object> providerError(String provider, String model, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 180) message = message.substring(0, 180);
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        long cooldownMs = lowerMessage.contains("per-day")
                ? 24 * 60 * 60 * 1000L
                : message.contains("402") || lowerMessage.contains("insufficient balance")
                ? 6 * 60 * 60 * 1000L
                : message.contains("429") || lowerMessage.contains("too many requests")
                ? 30 * 60 * 1000L : 2 * 60 * 1000L;
        providerHealth.put(provider, new ProviderHealth("ERROR", Instant.now().toString(), message, System.currentTimeMillis() + cooldownMs));
        return Map.of("ai", provider, "model", model, "status", "ERROR", "verification", "API_CALL_FAILED", "signal", "NO_VOTE", "confidence", 0, "reason", message);
    }

    private Map<String, Object> cooldownVote(String provider, String model) {
        ProviderHealth health = providerHealth.get(provider);
        if (health == null || health.cooldownUntil <= System.currentTimeMillis()) return null;
        return Map.of(
                "ai", provider, "model", model, "status", "COOLDOWN", "verification", "API_CALL_DEFERRED",
                "signal", "NO_VOTE", "confidence", 0,
                "reason", "Temporary provider cooldown until " + Instant.ofEpochMilli(health.cooldownUntil)
        );
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
    private record ProviderHealth(String status, String checkedAt, String error, long cooldownUntil) {}
    private record ProviderCandidate(String name, boolean configured, String keyName, Supplier<Map<String, Object>> caller) {}
}
