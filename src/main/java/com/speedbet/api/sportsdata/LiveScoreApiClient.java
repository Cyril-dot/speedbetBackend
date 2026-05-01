package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * Client for the LiveScore API (livescore-api.com) — PRIMARY data source.
 *
 * Base URL  : https://livescore-api.com
 * Auth      : key + secret query params
 *
 * ── Response shape differences ───────────────────────────────────────────
 *
 *   LIVE endpoint (matches/live.json):
 *     home.name / away.name     — nested objects
 *     home.logo / away.logo     — nested logo URL  ← ALWAYS present for live
 *     scores.score              — "1 - 0" format
 *     time                      — match clock e.g. "45", "HT", "FT"
 *     list key: "match"
 *
 *   COMPETITION-SPECIFIC fixture endpoints (fixtures/matches.json?competition_id=X):
 *     home.name / away.name     — nested objects
 *     home.logo / away.logo     — nested logo URL  ← present
 *     scheduled                 — full ISO string e.g. "2026-05-01T10:00:00"
 *     fixture_id                — fixture identifier
 *     list key: "fixture"
 *
 *   GENERAL fixture endpoint (fixtures/matches.json with no competition_id):
 *     home_name / away_name     — flat strings
 *     home_image / away_image   — flat logo URL  ← extractHomeLogo reads these
 *     date + time               — separate fields e.g. date="2026-05-01" time="10:00:00"
 *     id                        — used as fixture identifier (no fixture_id field)
 *     list key: "fixtures"
 *
 * All extractor methods handle ALL three shapes via fallback chains.
 *
 * ── Logo extraction priority (extractHomeLogo / extractAwayLogo) ─────────
 *   1. home.logo          — nested, competition-specific + live endpoints
 *   2. home.image         — nested alt key (some endpoints)
 *   3. home_image         — flat, general fixture endpoint
 *   4. home_logo          — flat alt key
 *
 * ── Endpoints used ───────────────────────────────────────────────────────
 *   GET /api-client/matches/live.json
 *   GET /api-client/matches/live.json?competition_id=X
 *   GET /api-client/matches/history.json?from=YYYY-MM-DD&to=YYYY-MM-DD
 *   GET /api-client/fixtures/matches.json?competition_id=X
 *   GET /api-client/fixtures/matches.json                    (general, all upcoming)
 *   GET /api-client/matches/stats.json?match_id=X
 *   GET /api-client/matches/lineups.json?match_id=X
 *   GET /api-client/scores/events.json?id=X
 *   GET /api-client/matches/commentary.json?match_id=X
 *   GET /api-client/standings/table.json?competition_id=X
 *   GET /api-client/standings/live.json?competition_id=X
 *   GET /api-client/competitions/list.json
 *   GET /api-client/teams/head2head.json?team1_id=X&team2_id=Y
 *   GET /api-client/teams/list.json?competition_id=X
 *   GET /api-client/teams/matches.json?team_id=X
 *   GET /api-client/countries/list.json
 *   GET /api-client/seasons/list.json
 *   GET /api-client/users/pair.json
 */
@Slf4j
@Component
public class LiveScoreApiClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private static final String BASE_URL = "https://livescore-api.com";

    private static final long KEY_ROTATE_DELAY_MS = 150;
    private static final int  TRANSIENT_RETRIES   = 1;
    private static final long KEY_COOLDOWN_MS     = 5 * 60_000L;
    private static final long CACHE_TTL_MINUTES   = 5;

    // ── Top 6 League competition IDs ──────────────────────────────────────
    public static final Map<String, Integer> TOP_6_COMPETITION_IDS = Map.of(
            "Premier League",   2,
            "La Liga",          5,
            "Bundesliga",       1,
            "Serie A",          8,
            "Ligue 1",          4,
            "Champions League", 244
    );

    public static final Map<String, Integer> ALL_COMPETITION_IDS = new LinkedHashMap<>() {{
        put("Premier League",       2);
        put("La Liga",              5);
        put("Bundesliga",           1);
        put("Serie A",              8);
        put("Ligue 1",              4);
        put("Champions League",     244);
        put("Europa League",        245);
        put("Conference League",    246);
        put("Championship",         3);
        put("Eredivisie",           10);
        put("Liga Portugal",        13);
        put("Super Lig",            17);
        put("Jupiler Pro League",   14);
        put("Scottish Premiership", 23);
        put("MLS",                  44);
        put("Copa del Rey",         86);
        put("DFB Pokal",            87);
        put("Coppa Italia",         88);
        put("Coupe de France",      89);
        put("FA Cup",               85);
    }};

    // ── API key pairs ─────────────────────────────────────────────────────
    private final List<String[]> apiCredentials = List.of(
            new String[]{"Y7JvzQYJm1c4Vlyh", "aRZDfUJzl1HOfVae3TJCKIl6JyaFUJX4"},
            new String[]{"TA16A6HrD4mYThZ8", "6QXYN60QILflOkUHUVOv2vXGzkFtBFCH"}
    );

    private final WebClient    client;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CacheEntry> cache        = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>       keyCooldowns = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Constructor ───────────────────────────────────────────────────────

    public LiveScoreApiClient(WebClient.Builder builder,
                              @Value("${app.platform.demo-mode:false}") boolean demoMode) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        if (demoMode) {
            log.info("LiveScoreApiClient: demo-mode flag detected; real API calls will be made.");
        }
    }

    // ── Key cooldown helpers ──────────────────────────────────────────────

    private boolean isCredentialCoolingDown(String key) {
        Long until = keyCooldowns.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            keyCooldowns.remove(key);
            log.debug("LiveScoreAPI key ...{} cooldown expired, re-enabling.", tail(key));
            return false;
        }
        return true;
    }

    private void coolDownCredential(String key, long ms) {
        keyCooldowns.put(key, System.currentTimeMillis() + ms);
        log.warn("LiveScoreAPI key ...{} placed on {}s cooldown.", tail(key), ms / 1000);
    }

    // ── Core caller with key rotation ─────────────────────────────────────

    private Map<String, Object> callWithFallback(String path) {
        int usableKeys = 0;
        for (String[] cred : apiCredentials) {
            String key    = cred[0];
            String secret = cred[1];

            if (key.contains("FALLBACK") || key.isBlank()) continue;
            if (isCredentialCoolingDown(key)) {
                log.debug("LiveScoreAPI [{}] key ...{} cooling down, skipping.", path, tail(key));
                continue;
            }
            usableKeys++;

            for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
                final int currentAttempt = attempt;
                try {
                    String fullPath = path + (path.contains("?") ? "&" : "?")
                            + "key=" + key + "&secret=" + secret;

                    String raw = client.get()
                            .uri("/api-client/" + fullPath)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(12))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                int status = extractStatusCode(e);
                                if (status == 401 || status == 403) {
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (auth failure), rotating",
                                            path, tail(key), status);
                                    return Mono.empty();
                                }
                                if (status == 402 || status == 429) {
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (rate limited), cooling down",
                                            path, tail(key), status);
                                    coolDownCredential(key, KEY_COOLDOWN_MS);
                                    return Mono.error(new SkipKeyException("Rate limited: HTTP " + status));
                                }
                                if (status >= 500) {
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (server error)",
                                            path, tail(key), status);
                                    return Mono.error(new SkipKeyException("Server error: HTTP " + status));
                                }
                                log.warn("LiveScoreAPI [{}] key ...{} attempt={} network error: {}",
                                        path, tail(key), currentAttempt, e.getMessage());
                                return Mono.empty();
                            })
                            .block();

                    if (raw == null || raw.isBlank()) {
                        log.debug("LiveScoreAPI [{}] key ...{} attempt={} → blank response",
                                path, tail(key), currentAttempt);
                        continue;
                    }

                    Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);

                    Object success = result.get("success");
                    if (success != null && "false".equals(success.toString())) {
                        log.warn("LiveScoreAPI [{}] key ...{} → success=false: {}",
                                path, tail(key), result.get("error"));
                        break;
                    }

                    log.info("LiveScoreAPI [{}] key ...{} → OK ({} bytes)", path, tail(key), raw.length());
                    return result;

                } catch (SkipKeyException e) {
                    log.debug("LiveScoreAPI [{}] key ...{} → {}, skipping", path, tail(key), e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("LiveScoreAPI [{}] key ...{} attempt={} → threw: {}",
                            path, tail(key), currentAttempt, e.getMessage());
                    if (currentAttempt == TRANSIENT_RETRIES) break;
                }
            }
            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        if (usableKeys == 0) log.warn("LiveScoreAPI [{}] → ALL keys on cooldown", path);
        else log.error("LiveScoreAPI [{}] → ALL {} usable keys exhausted", path, usableKeys);
        return null;
    }

    // ── Cache helper ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, java.util.function.Supplier<T> loader) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("LiveScoreAPI cache HIT: '{}'", cacheKey);
            return (T) entry.data();
        }
        T result = loader.get();
        if (result != null) {
            long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CACHE_TTL_MINUTES);
            cache.put(cacheKey, new CacheEntry(result, expiresAt));
        }
        return result;
    }

    public void invalidateCache(String key) { cache.remove(key); }
    public void clearCache() { cache.clear(); }

    // ═════════════════════════════════════════════════════════════════════
    //  PUBLIC API METHODS
    // ═════════════════════════════════════════════════════════════════════

    public boolean verifyCredentials() {
        Map<String, Object> result = callWithFallback("users/pair.json");
        if (result == null) return false;
        Object success = result.get("success");
        return success != null && !"false".equals(success.toString());
    }

    // ── Live Scores ───────────────────────────────────────────────────────

    public List<Map<String, Object>> getLiveScores() {
        Map<String, Object> result = callWithFallback("matches/live.json");
        if (result == null) { log.warn("getLiveScores: null response"); return Collections.emptyList(); }
        return extractMatchList(result, "data", "match");
    }

    public List<Map<String, Object>> getTop6LiveScores() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
            Map<String, Object> result = callWithFallback(
                    "matches/live.json?competition_id=" + entry.getValue());
            if (result != null) all.addAll(extractMatchList(result, "data", "match"));
            sleepQuietly(200);
        }
        return all;
    }

    public List<Map<String, Object>> getLiveScoresByCompetition(int competitionId) {
        Map<String, Object> result = callWithFallback("matches/live.json?competition_id=" + competitionId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "match");
    }

    public List<Map<String, Object>> getLiveScoresByTeam(int teamId) {
        Map<String, Object> result = callWithFallback("matches/live.json?team_id=" + teamId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "match");
    }

    public List<Map<String, Object>> getLiveScoresByCountry(int countryId) {
        Map<String, Object> result = callWithFallback("matches/live.json?country_id=" + countryId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "match");
    }

    // ── Today's Matches ───────────────────────────────────────────────────

    public List<Map<String, Object>> getTodayMatches() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return cached("today:" + today, () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/history.json?from=" + today + "&to=" + today);
            if (result == null) return Collections.emptyList();
            return extractMatchList(result, "data", "match");
        });
    }

    public List<Map<String, Object>> getTodayTop6Matches() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return cached("today:top6:" + today, () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
                Map<String, Object> result = callWithFallback(
                        "matches/history.json?from=" + today + "&to=" + today
                                + "&competition_id=" + entry.getValue());
                if (result != null) all.addAll(extractMatchList(result, "data", "match"));
                sleepQuietly(200);
            }
            return all;
        });
    }

    public List<Map<String, Object>> getMatchesByDate(String date) {
        return cached("history:" + date, () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/history.json?from=" + date + "&to=" + date);
            if (result == null) return Collections.emptyList();
            return extractMatchList(result, "data", "match");
        });
    }

    // ── Upcoming Fixtures ─────────────────────────────────────────────────

    /**
     * All upcoming fixtures — general endpoint.
     * Response shape: flat home_name/away_name/home_image/away_image, separate date/time.
     * List key: "fixtures".
     *
     * NOTE: this endpoint does NOT return logos for most fixtures.
     *       Logo enrichment is handled in LiveScorePoller via TeamLogoCache.
     *       extractHomeLogo / extractAwayLogo read home_image / away_image as fallback.
     */
    public List<Map<String, Object>> getUpcomingFixtures() {
        return cached("fixtures:all", () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json");
            if (result == null) return Collections.emptyList();
            return extractFixtureList(result);
        });
    }

    public List<Map<String, Object>> getFixturesByDate(String date) {
        return cached("fixtures:" + date, () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json?date=" + date);
            if (result == null) return Collections.emptyList();
            return extractFixtureList(result);
        });
    }

    /**
     * Top-6 upcoming fixtures — competition-specific endpoint per league.
     * Response shape: nested home.name/away.name/home.logo/away.logo, "scheduled" ISO string.
     * List key: "fixture".
     *
     * These ARE the reference for how logos work — same nested shape as live scores.
     * LiveScorePoller calls this first (Step A) to warm TeamLogoCache before
     * processing the general fixture endpoint (Step B).
     */
    public List<Map<String, Object>> getTop6Fixtures() {
        return cached("fixtures:top6", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
                Map<String, Object> result = callWithFallback(
                        "fixtures/matches.json?competition_id=" + entry.getValue());
                if (result != null) {
                    List<Map<String, Object>> fixtures = extractMatchList(result, "data", "fixture");
                    log.info("getTop6Fixtures: {} fixtures for {}", fixtures.size(), entry.getKey());
                    all.addAll(fixtures);
                }
                sleepQuietly(200);
            }
            log.info("getTop6Fixtures: {} total fixtures across top 6 leagues", all.size());
            return all;
        });
    }

    public List<Map<String, Object>> getFixturesByCompetition(int competitionId) {
        return cached("fixtures:comp:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "fixtures/matches.json?competition_id=" + competitionId);
            if (result == null) return Collections.emptyList();
            return extractMatchList(result, "data", "fixture");
        });
    }

    public List<Map<String, Object>> getFixturesByTeam(int teamId) {
        Map<String, Object> result = callWithFallback("fixtures/matches.json?team_id=" + teamId);
        if (result == null) return Collections.emptyList();
        return extractFixtureList(result);
    }

    // ── Match Details ─────────────────────────────────────────────────────

    public Map<String, Object> getMatchStats(int matchId) {
        Map<String, Object> result = callWithFallback("matches/stats.json?match_id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getMatchLineup(int matchId) {
        Map<String, Object> result = callWithFallback("matches/lineups.json?match_id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getMatchEvents(int matchId) {
        Map<String, Object> result = callWithFallback("scores/events.json?id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getMatchCommentary(int matchId) {
        Map<String, Object> result = callWithFallback("matches/commentary.json?match_id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getFullMatchDetails(int matchId) {
        Map<String, Object> details = new HashMap<>();
        details.put("matchId", matchId);
        details.put("stats",   getMatchStats(matchId));
        details.put("events",  getMatchEvents(matchId));
        details.put("lineups", getMatchLineup(matchId));
        return Collections.unmodifiableMap(details);
    }

    // ── Standings ─────────────────────────────────────────────────────────

    public Map<String, Object> getStandings(int competitionId) {
        return cached("standings:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "standings/table.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getLiveStandings(int competitionId) {
        Map<String, Object> result = callWithFallback("standings/live.json?competition_id=" + competitionId);
        return result != null ? result : Map.of();
    }

    public Map<String, Map<String, Object>> getAllTop6Standings() {
        return cached("standings:top6", () -> {
            Map<String, Map<String, Object>> all = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
                Map<String, Object> standings = getStandings(entry.getValue());
                if (standings != null && !standings.isEmpty()) all.put(entry.getKey(), standings);
                sleepQuietly(200);
            }
            return all;
        });
    }

    // ── Competitions ──────────────────────────────────────────────────────

    public Map<String, Object> getAllCompetitions() {
        return cached("competitions:all", () -> {
            Map<String, Object> result = callWithFallback("competitions/list.json");
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getCompetitionsByCountry(int countryId) {
        return cached("competitions:country:" + countryId, () -> {
            Map<String, Object> result = callWithFallback("competitions/list.json?country_id=" + countryId);
            return result != null ? result : Map.of();
        });
    }

    // ── Teams ─────────────────────────────────────────────────────────────

    public Map<String, Object> getTeamsByCompetition(int competitionId) {
        return cached("teams:comp:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback("teams/list.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getTeamLastMatches(int teamId) {
        Map<String, Object> result = callWithFallback("teams/matches.json?team_id=" + teamId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getHeadToHead(int team1Id, int team2Id) {
        Map<String, Object> result = callWithFallback(
                "teams/head2head.json?team1_id=" + team1Id + "&team2_id=" + team2Id);
        return result != null ? result : Map.of();
    }

    // ── Top Scorers & Disciplinary ────────────────────────────────────────

    public Map<String, Object> getTopScorers(int competitionId) {
        return cached("topscorers:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/topscorers.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getTopDisciplinary(int competitionId) {
        return cached("topdisciplinary:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/disciplinary.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    // ── Countries & Seasons ───────────────────────────────────────────────

    public Map<String, Object> getCountries() {
        return cached("countries:all", () -> {
            Map<String, Object> result = callWithFallback("countries/list.json");
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getSeasons() {
        return cached("seasons:all", () -> {
            Map<String, Object> result = callWithFallback("seasons/list.json");
            return result != null ? result : Map.of();
        });
    }

    // ── Odds helpers ──────────────────────────────────────────────────────

    public Map<String, Object> extractOdds(Map<String, Object> matchData) {
        Object odds = matchData.get("odds");
        if (odds instanceof Map<?, ?> oddsMap) return new HashMap<>((Map<String, Object>) oddsMap);
        return Map.of();
    }

    public String extractPreOddsHome(Map<String, Object> m)  { return extractNestedOdds(m, "pre",  "1"); }
    public String extractPreOddsDraw(Map<String, Object> m)  { return extractNestedOdds(m, "pre",  "X"); }
    public String extractPreOddsAway(Map<String, Object> m)  { return extractNestedOdds(m, "pre",  "2"); }
    public String extractLiveOddsHome(Map<String, Object> m) { return extractNestedOdds(m, "live", "1"); }
    public String extractLiveOddsDraw(Map<String, Object> m) { return extractNestedOdds(m, "live", "X"); }
    public String extractLiveOddsAway(Map<String, Object> m) { return extractNestedOdds(m, "live", "2"); }

    @SuppressWarnings("unchecked")
    private String extractNestedOdds(Map<String, Object> matchData, String type, String outcome) {
        try {
            Object odds = matchData.get("odds");
            if (odds instanceof Map<?, ?> oddsMap) {
                Object typeOdds = ((Map<String, Object>) oddsMap).get(type);
                if (typeOdds instanceof Map<?, ?> typeMap) {
                    Object val = ((Map<String, Object>) typeMap).get(outcome);
                    return val != null ? val.toString() : "";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIELD EXTRACTION — handles ALL three response shapes
    //  (live, competition-specific fixture, general fixture)
    // ═════════════════════════════════════════════════════════════════════

    public static String extractMatchId(Map<String, Object> match) {
        Object v = match.get("id");
        return v != null ? v.toString() : "";
    }

    /**
     * Fixture identifier.
     * Competition-specific: "fixture_id"
     * General endpoint:     "id" (no fixture_id field)
     */
    public static String extractFixtureId(Map<String, Object> match) {
        Object fixtureId = match.get("fixture_id");
        if (fixtureId != null && !fixtureId.toString().isBlank()) return fixtureId.toString();
        Object id = match.get("id");
        return id != null ? id.toString() : "";
    }

    /**
     * Home team name.
     * Live + competition-specific: home.name (nested object)
     * General fixture endpoint:    home_name (flat string)
     */
    public static String extractHomeName(Map<String, Object> match) {
        Object home = match.get("home");
        if (home instanceof Map<?, ?> homeMap) {
            Object name = ((Map<?, ?>) homeMap).get("name");
            if (name != null && !name.toString().isBlank()) return name.toString();
        }
        Object flat = match.get("home_name");
        return flat != null ? flat.toString() : "";
    }

    /**
     * Away team name.
     * Live + competition-specific: away.name (nested object)
     * General fixture endpoint:    away_name (flat string)
     */
    public static String extractAwayName(Map<String, Object> match) {
        Object away = match.get("away");
        if (away instanceof Map<?, ?> awayMap) {
            Object name = ((Map<?, ?>) awayMap).get("name");
            if (name != null && !name.toString().isBlank()) return name.toString();
        }
        Object flat = match.get("away_name");
        return flat != null ? flat.toString() : "";
    }

    /**
     * Home team logo URL.
     *
     * Priority order — mirrors how the LIVE endpoint works (which always has logos):
     *   1. home.logo          — live endpoint + competition-specific nested shape
     *   2. home.image         — alternative nested key used by some endpoints
     *   3. home_image         — general fixture endpoint flat field
     *   4. home_logo          — alternative flat key
     *
     * The live endpoint always returns shape 1 (home.logo), which is why live
     * matches always have logos. We apply the same chain so fixture endpoints
     * also resolve correctly regardless of which shape they use.
     */
    public static String extractHomeLogo(Map<String, Object> match) {
        // 1 & 2 — nested "home" object (live + competition-specific endpoints)
        Object home = match.get("home");
        if (home instanceof Map<?, ?> homeMap) {
            Object logo = ((Map<?, ?>) homeMap).get("logo");
            if (logo != null && !logo.toString().isBlank()) return logo.toString();
            Object image = ((Map<?, ?>) homeMap).get("image");
            if (image != null && !image.toString().isBlank()) return image.toString();
        }
        // 3 — flat "home_image" (general fixture endpoint)
        Object homeImage = match.get("home_image");
        if (homeImage != null && !homeImage.toString().isBlank()) return homeImage.toString();
        // 4 — flat "home_logo" (alternative flat key)
        Object homeLogo = match.get("home_logo");
        if (homeLogo != null && !homeLogo.toString().isBlank()) return homeLogo.toString();

        return "";
    }

    /**
     * Away team logo URL.
     *
     * Priority order — mirrors extractHomeLogo:
     *   1. away.logo          — live endpoint + competition-specific nested shape
     *   2. away.image         — alternative nested key
     *   3. away_image         — general fixture endpoint flat field
     *   4. away_logo          — alternative flat key
     */
    public static String extractAwayLogo(Map<String, Object> match) {
        // 1 & 2 — nested "away" object (live + competition-specific endpoints)
        Object away = match.get("away");
        if (away instanceof Map<?, ?> awayMap) {
            Object logo = ((Map<?, ?>) awayMap).get("logo");
            if (logo != null && !logo.toString().isBlank()) return logo.toString();
            Object image = ((Map<?, ?>) awayMap).get("image");
            if (image != null && !image.toString().isBlank()) return image.toString();
        }
        // 3 — flat "away_image" (general fixture endpoint)
        Object awayImage = match.get("away_image");
        if (awayImage != null && !awayImage.toString().isBlank()) return awayImage.toString();
        // 4 — flat "away_logo" (alternative flat key)
        Object awayLogo = match.get("away_logo");
        if (awayLogo != null && !awayLogo.toString().isBlank()) return awayLogo.toString();

        return "";
    }

    public static String extractScore(Map<String, Object> match) {
        Object scores = match.get("scores");
        if (scores instanceof Map<?, ?> scoresMap) {
            Object score = ((Map<?, ?>) scoresMap).get("score");
            if (score != null) return score.toString().replace(" ", "");
        }
        return "-";
    }

    public static String extractHalfTimeScore(Map<String, Object> match) {
        Object scores = match.get("scores");
        if (scores instanceof Map<?, ?> scoresMap) {
            Object ht = ((Map<?, ?>) scoresMap).get("ht_score");
            if (ht != null) return ht.toString().replace(" ", "");
        }
        return "";
    }

    public static String extractStatus(Map<String, Object> match) {
        Object status = match.get("status");
        return status != null ? status.toString() : "";
    }

    /**
     * Match clock / status string (e.g. "45", "FT", "HT").
     *
     * NOTE: on the GENERAL fixture endpoint the "time" field holds the kickoff
     * time (e.g. "10:00:00"), not a match clock. Only use this for live data
     * where the field actually represents elapsed minutes or status markers.
     */
    public static String extractMatchTime(Map<String, Object> match) {
        Object time = match.get("time");
        return time != null ? time.toString() : "";
    }

    public static String extractMatchDate(Map<String, Object> match) {
        Object date = match.get("date");
        return date != null ? date.toString() : "";
    }

    /**
     * Raw scheduled/kickoff time string.
     * Competition-specific: "scheduled" (ISO datetime or HH:mm)
     * General endpoint:     "time"      (HH:mm:ss)
     */
    public static String extractScheduledTime(Map<String, Object> match) {
        Object scheduled = match.get("scheduled");
        if (scheduled != null && !scheduled.toString().isBlank()) return scheduled.toString();
        Object time = match.get("time");
        return time != null ? time.toString() : "";
    }

    /**
     * Builds a UTC Instant for kickoff — handles all three API response shapes.
     *
     * Shape 1 — Live (matches/live.json):
     *   "date": "2026-05-01"  +  "time": "45" (match clock, not kickoff)
     *   → kickoff = date combined with a fallback or stored kickoffAt (not reconstructable here)
     *
     * Shape 2 — Competition-specific (fixtures/matches.json?competition_id=X):
     *   "scheduled": "2026-05-01T10:00:00"  → parsed as LocalDateTime UTC
     *   "scheduled": "10:00"                 → combined with "date" field
     *
     * Shape 3 — General (fixtures/matches.json, no competition_id):
     *   "date": "2026-05-01"  +  "time": "10:00:00"  → combined as UTC Instant
     *
     * Returns null if required fields are missing or unparseable.
     */
    public static Instant buildKickoffInstant(Map<String, Object> match) {

        // ── Shape 2: "scheduled" field (competition-specific endpoint) ────
        Object scheduledObj = match.get("scheduled");
        if (scheduledObj != null && !scheduledObj.toString().isBlank()) {
            String scheduled = scheduledObj.toString();
            // Full ISO datetime e.g. "2026-05-01T10:00:00"
            if (scheduled.contains("T")) {
                try { return LocalDateTime.parse(scheduled).toInstant(ZoneOffset.UTC); }
                catch (DateTimeParseException ignored) {}
                try { return OffsetDateTime.parse(scheduled).toInstant(); }
                catch (DateTimeParseException ignored) {}
            }
            // Bare "HH:mm" — fall through to combine with date below
        }

        // ── Shape 3: "date" + "time" fields (general fixture endpoint) ───
        String date    = extractMatchDate(match);
        String timeStr = "";

        // General endpoint: "time" field holds kickoff time e.g. "10:00:00"
        Object timeObj = match.get("time");
        if (timeObj != null && !timeObj.toString().isBlank()) {
            timeStr = timeObj.toString();
        } else if (scheduledObj != null && !scheduledObj.toString().isBlank()) {
            // Bare HH:mm from "scheduled" field (shape 2 partial)
            timeStr = scheduledObj.toString();
        }

        if (date.isBlank() || timeStr.isBlank()) {
            log.debug("buildKickoffInstant: missing date='{}' or time='{}' — cannot build Instant", date, timeStr);
            return null;
        }

        try {
            LocalDate ld   = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
            // Normalise to HH:mm — handles "10:00:00" and "10:00" both
            String    hhmm = timeStr.length() >= 5 ? timeStr.substring(0, 5) : timeStr;
            LocalTime lt   = LocalTime.parse(hhmm, DateTimeFormatter.ofPattern("HH:mm"));
            Instant kickoff = LocalDateTime.of(ld, lt).toInstant(ZoneOffset.UTC);
            log.debug("buildKickoffInstant: date='{}' time='{}' → {}", date, timeStr, kickoff);
            return kickoff;
        } catch (DateTimeParseException e) {
            log.debug("buildKickoffInstant: could not parse date='{}' time='{}'", date, timeStr);
            return null;
        }
    }

    public static String extractCompetitionName(Map<String, Object> match) {
        Object comp = match.get("competition");
        if (comp instanceof Map<?, ?> compMap) {
            Object name = ((Map<?, ?>) compMap).get("name");
            if (name != null) return name.toString();
        }
        // General endpoint exposes competition name as a flat field
        Object compName = match.get("competition_name");
        if (compName != null && !compName.toString().isBlank()) return compName.toString();
        return "";
    }

    /**
     * League/competition logo URL.
     * Live + competition-specific: competition.logo or competition.image (nested)
     * General endpoint:            competition_logo or league_logo (flat)
     */
    public static String extractLeagueLogo(Map<String, Object> match) {
        Object comp = match.get("competition");
        if (comp instanceof Map<?, ?> compMap) {
            Object logo = ((Map<?, ?>) compMap).get("logo");
            if (logo != null && !logo.toString().isBlank()) return logo.toString();
            Object image = ((Map<?, ?>) compMap).get("image");
            if (image != null && !image.toString().isBlank()) return image.toString();
        }
        Object flat = match.get("competition_logo");
        if (flat != null && !flat.toString().isBlank()) return flat.toString();
        Object flat2 = match.get("league_logo");
        if (flat2 != null && !flat2.toString().isBlank()) return flat2.toString();
        return "";
    }

    public static boolean isLive(Map<String, Object> match) {
        String status = extractStatus(match);
        String time   = extractMatchTime(match);
        return "LIVE".equalsIgnoreCase(status) ||
                (!time.isEmpty() && !"FT".equals(time) && !"HT".equals(time) && !"POSTP".equals(time));
    }

    public static boolean isFinished(Map<String, Object> match) {
        String status = extractStatus(match);
        String time   = extractMatchTime(match);
        return "FINISHED".equalsIgnoreCase(status) || "FT".equals(time);
    }

    public static boolean isScheduled(Map<String, Object> match) {
        String status = extractStatus(match);
        return "SCHEDULED".equalsIgnoreCase(status) || status.isEmpty();
    }

    // ── Key status ────────────────────────────────────────────────────────

    public Map<String, Object> getKeyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String[] cred : apiCredentials) {
            String key  = cred[0];
            Long until  = keyCooldowns.get(key);
            if (until == null || now >= until) status.put("..." + tail(key), "ACTIVE");
            else status.put("..." + tail(key), "COOLDOWN (" + (until - now) / 1000 + "s remaining)");
        }
        return status;
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Extracts fixture list from general endpoint response.
     * General:              data.fixtures  (list key "fixtures")
     * Competition-specific: data.fixture   (list key "fixture")
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFixtureList(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        Object data = response.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            // General endpoint key
            Object fixtures = ((Map<?, ?>) dataMap).get("fixtures");
            if (fixtures instanceof List<?> list && !list.isEmpty()) {
                log.debug("extractFixtureList: {} fixtures via data.fixtures", list.size());
                return (List<Map<String, Object>>) list;
            }
            // Competition-specific endpoint key (fallback)
            Object fixture = ((Map<?, ?>) dataMap).get("fixture");
            if (fixture instanceof List<?> list && !list.isEmpty()) {
                log.debug("extractFixtureList: {} fixtures via data.fixture", list.size());
                return (List<Map<String, Object>>) list;
            }
        }
        // Last resort
        return extractMatchList(response, "fixtures", "fixture");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractMatchList(Map<String, Object> response, String... keys) {
        if (response == null) return Collections.emptyList();
        for (String key : keys) {
            Object val = response.get(key);
            if (val instanceof List<?> list && !list.isEmpty()) return (List<Map<String, Object>>) list;
            if (val instanceof Map<?, ?> nested) {
                for (Object innerVal : ((Map<?, ?>) nested).values()) {
                    if (innerVal instanceof List<?> innerList && !innerList.isEmpty())
                        return (List<Map<String, Object>>) innerList;
                }
            }
        }
        return Collections.emptyList();
    }

    private int extractStatusCode(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return 0;
        if (msg.contains("401")) return 401;
        if (msg.contains("403")) return 403;
        if (msg.contains("402")) return 402;
        if (msg.contains("429")) return 429;
        if (msg.contains("500")) return 500;
        if (msg.contains("502")) return 502;
        if (msg.contains("503")) return 503;
        return 0;
    }

    private static String tail(String key) {
        return key.length() > 4 ? key.substring(key.length() - 4) : key;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static class SkipKeyException extends RuntimeException {
        SkipKeyException(String msg) { super(msg); }
    }
}