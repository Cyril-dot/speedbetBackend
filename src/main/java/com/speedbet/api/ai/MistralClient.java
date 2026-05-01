package com.speedbet.api.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class MistralClient {

    // ── Hardcoded Configuration ───────────────────────────────────────────
    private static final String BASE_URL = "https://integrate.api.nvidia.com/v1";
    private static final String API_KEY  = "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";
    private static final String MODEL     = "google/gemma-2-2b-it";

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    // Tokens needed per crash point (JSON overhead ~25 tokens each) + envelope ~100
    private static final int TOKENS_PER_CRASH_POINT = 28;
    private static final int TOKEN_ENVELOPE_OVERHEAD = 150;
    private static final int MAX_TOKENS_HARD_CAP     = 4096;

    public MistralClient(WebClient.Builder builder) {
        this.client = builder
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + API_KEY)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ── Public methods ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> predictMatch(Map<String, Object> matchContext) {
        try {
            var response = callMistral(
                    "You are a professional football analyst. Output STRICT JSON only. No markdown, no explanation.",
                    buildMatchPrompt(matchContext),
                    1000
            );
            return mapper.readValue(cleanJson(response), Map.class);
        } catch (Exception e) {
            log.warn("Match prediction failed: {}", e.getMessage());
            return getDemoPrediction(matchContext);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Double> generateCrashPoints(String gameName, int count, Map<String, Object> distribution) {
        try {
            // Dynamically size max_tokens so the full JSON array is never truncated
            int maxTokens = Math.min(
                    TOKEN_ENVELOPE_OVERHEAD + (count * TOKENS_PER_CRASH_POINT),
                    MAX_TOKENS_HARD_CAP
            );

            var response = callMistral(
                    "You are a crash game RNG engine. Output STRICT JSON only. No explanation. " +
                            "Your entire response must be a single valid JSON object: " +
                            "{\"crash_points\": [<array of " + count + " numbers>]}. " +
                            "Do NOT truncate. All " + count + " values are required.",
                    buildCrashPrompt(gameName, count, distribution),
                    maxTokens
            );

            var cleaned = cleanJson(response);
            var parsed  = mapper.readValue(cleaned, Map.class);
            var points  = (List<Double>) parsed.get("crash_points");

            if (points == null || points.isEmpty()) {
                log.warn("AI returned empty crash_points for {}, using PRNG fallback", gameName);
                return generateFallbackCrashPoints(count, distribution);
            }
            if (points.size() < count) {
                log.warn("AI returned only {}/{} crash points for {}, padding with PRNG",
                        points.size(), count, gameName);
                var extra = generateFallbackCrashPoints(count - points.size(), distribution);
                points = new java.util.ArrayList<>(points);
                points.addAll(extra);
            }
            return points;

        } catch (Exception e) {
            log.warn("Crash generation failed, using PRNG fallback: {}", e.getMessage());
            return generateFallbackCrashPoints(count, distribution);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateCrashInsight(String game, List<Double> recentCrashes) {
        try {
            var prompt = mapper.writeValueAsString(Map.of(
                    "game", game,
                    "recent_crashes", recentCrashes,
                    "task", "Generate a 2-sentence insight for bettors. Be direct. " +
                            "Mention streak patterns and suggest a cashout zone. " +
                            "Output JSON: {\"insight\": string, \"suggested_cashout_min\": float, \"suggested_cashout_max\": float}"
            ));
            var response = callMistral(
                    "You are a crash game analyst. Output STRICT JSON only.", prompt, 256);
            return mapper.readValue(cleanJson(response), Map.class);
        } catch (Exception e) {
            log.warn("Crash insight failed: {}", e.getMessage());
            return getDemoCrashInsight();
        }
    }

    // ── Core API caller ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callMistral(String systemPrompt, String userContent, int maxTokens) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", MODEL);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
        // Gemma 2B prefers lower temperature for precise JSON
        body.put("temperature", 0.2);
        body.put("top_p",       0.7);
        body.put("max_tokens",  maxTokens);

        var response = (Map<String, Object>) client.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(45))
                .onErrorResume(e -> {
                    log.error("NVIDIA NIM API error: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (response == null)
            throw new RuntimeException("No response from NVIDIA NIM");

        var choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty())
            throw new RuntimeException("Empty choices from NVIDIA NIM");

        var choice       = choices.get(0); // Java 17 compatible
        var finishReason = (String) choice.getOrDefault("finish_reason", "unknown");
        if ("length".equals(finishReason)) {
            throw new RuntimeException(
                    "NVIDIA NIM response truncated (finish_reason=length). " +
                            "Increase max_tokens or reduce batch size.");
        }

        var message = (Map<String, Object>) choice.get("message");
        var content = (String) message.get("content");
        if (content == null || content.isBlank())
            throw new RuntimeException("Empty content from NVIDIA NIM");

        log.debug("NVIDIA NIM response received ({} chars, finish_reason={})",
                content.length(), finishReason);
        return content;
    }

    // ── Prompt builders ───────────────────────────────────────────────────

    private String buildMatchPrompt(Map<String, Object> ctx) {
        try   { return mapper.writeValueAsString(ctx); }
        catch (Exception e) { return ctx.toString(); }
    }

    private String buildCrashPrompt(String game, int count, Map<String, Object> distribution) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "game",        game,
                    "count",       count,
                    "distribution", distribution,
                    "constraints", Map.of(
                            "no_consecutive_extreme", true,
                            "min_gap_between_high",   8
                    )
            ));
        } catch (Exception e) { return "{}"; }
    }

    /**
     * Robust JSON cleaner.
     * Finds the first '{' and last '}' to extract the JSON object.
     */
    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        var cleaned = raw.strip();

        // Remove markdown code blocks if present
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```json\\s*", "").replaceFirst("```\\s*", "");
            var end = cleaned.lastIndexOf("```");
            if (end >= 0) cleaned = cleaned.substring(0, end);
        }

        // Find the first { and last } to handle conversational filler
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned.strip();
    }

    // ── Fallback data ─────────────────────────────────────────────────────

    @SuppressWarnings("unused") // ctx is kept for interface consistency
    private Map<String, Object> getDemoPrediction(Map<String, Object> ctx) {
        return Map.of(
                "win_probability",  Map.of("home", 0.48, "draw", 0.27, "away", 0.25),
                "predicted_score",  Map.of("home", 2, "away", 1),
                "both_teams_to_score", true, // Fixed typo 'btts'
                "over_under_25",    "OVER",
                "correct_scores",   List.of(
                        Map.of("score", "2-1", "prob", 0.18),
                        Map.of("score", "1-0", "prob", 0.12),
                        Map.of("score", "2-0", "prob", 0.10)
                ),
                "confidence", 0.62,
                "reasoning",  "Based on recent form and head-to-head record, the home team has a " +
                        "clear advantage. Their pressing intensity and home crowd support " +
                        "typically yields 2+ goals.",
                "suggested_odds", Map.of("home", 2.1, "draw", 3.4, "away", 3.2)
        );
    }

    public List<Double> generateFallbackCrashPoints(int count, Map<String, Object> distribution) {
        var rand   = new Random(System.currentTimeMillis());
        var points = new java.util.ArrayList<Double>(count);

        double lowPct  = ((Number) distribution.getOrDefault("low_pct",  0.40)).doubleValue();
        double medPct  = ((Number) distribution.getOrDefault("med_pct",  0.35)).doubleValue();
        double highPct = ((Number) distribution.getOrDefault("high_pct", 0.20)).doubleValue();
        int lastHighIndex = -10;

        for (int i = 0; i < count; i++) {
            double r = rand.nextDouble();
            double val;
            if (r < lowPct)
                val = 1.00 + rand.nextDouble();
            else if (r < lowPct + medPct)
                val = 2.01 + rand.nextDouble() * 2.99;
            else if (r < lowPct + medPct + highPct && i - lastHighIndex >= 8) {
                val = 5.01 + rand.nextDouble() * 14.99;
                lastHighIndex = i;
            } else
                val = 1.00 + rand.nextDouble() * 3.0;
            points.add(Math.round(val * 100.0) / 100.0);
        }
        return points;
    }

    private Map<String, Object> getDemoCrashInsight() {
        return Map.of(
                "insight",               "Recent rounds show a LOW streak of 4 consecutive rounds. " +
                        "Statistically, a MEDIUM or HIGH round is due. Consider cashing out between 2x–4x.",
                "suggested_cashout_min", 2.0,
                "suggested_cashout_max", 4.0
        );
    }
}
