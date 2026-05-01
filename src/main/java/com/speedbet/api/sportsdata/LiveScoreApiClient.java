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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Client for the LiveScore API (livescore-api.com) — PRIMARY data source.
 *
 * Base URL  : https://livescore-api.com
 * Auth      : key + secret query params
 *
 * DEMO DATA DISABLED: All getDemoXxx() methods removed. When the API returns
 * no data or all keys are exhausted, empty collections / empty maps are
 * returned so the UI can handle the no-data state gracefully.
 *
 * ── Endpoints used ──────────────────────────────────────────────────────────
 *   GET /api-client/matches/live.json                     → live scores (all)
 *   GET /api-client/matches/live.json?competition_id=X    → live scores by league
 *   GET /api-client/scores/history.json?date=YYYY-MM-DD   → today/past results
 *   GET /api-client/fixtures/matches.json?date=YYYY-MM-DD → upcoming fixtures
 *   GET /api-client/matches/stats.json?match_id=X         → match statistics
 *   GET /api-client/matches/lineups.json?match_id=X       → match lineups
 *   GET /api-client/scores/events.json?id=X               → match events (goals, cards)
 *   GET /api-client/matches/commentary.json?match_id=X    → live commentary
 *   GET /api-client/standings/table.json?competition_id=X → league standings
 *   GET /api-client/standings/live.json?competition_id=X  → live standings
 *   GET /api-client/competitions/list.json                → all competitions
 *   GET /api-client/teams/head2head.json?team1_id=X&team2_id=Y → head to head
 *   GET /api-client/teams/list.json?competition_id=X      → teams in league
 *   GET /api-client/teams/matches.json?team_id=X          → team last matches
 *   GET /api-client/countries/list.json                   → countries list
 *   GET /api-client/seasons/list.json                     → seasons list
 *   GET /api-client/users/pair.json                       → verify credentials
 */
@Slf4j
@Component
public class LiveScoreApiClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private static final String BASE_URL = "https://livescore-api.com";

    /** Inter-key delay (ms) */
    private static final long KEY_ROTATE_DELAY_MS = 150;

    /** Retry same key once on transient network errors */
    private static final int TRANSIENT_RETRIES = 1;

    /** Cooldown duration for rate-limited keys (5 minutes) */
    private static final long KEY_COOLDOWN_MS = 5 * 60_000L;

    /** Cache TTL for static/semi-static data (minutes) */
    private static final long CACHE_TTL_MINUTES = 5;

    // ── Top 6 League competition IDs ──────────────────────────────────────
    public static final Map<String, Integer> TOP_6_COMPETITION_IDS = Map.of(
            "Premier League",     2,
            "La Liga",            5,
            "Bundesliga",         1,
            "Serie A",            8,
            "Ligue 1",            4,
            "Champions League",   244
    );

    // All major competition IDs
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

    // ── API key pairs (key + secret) ──────────────────────────────────────
    private final List<String[]> apiCredentials = List.of(
            new String[]{"TA16A6HrD4mYThZ8",  "6QXYN60QILflOkUHUVOv2vXGzkFtBFCH"},  // Primary
            new String[]{"nIznBfFZuqhOnB3h",   "fwDKGjvEE8Qh1XMpGmNhBsh5TyUgaXyp"}
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
            log.info("LiveScoreApiClient: demo-mode flag detected but demo data is disabled. " +
                    "Real API calls will be made; empty results returned on failure.");
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
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (server error), trying next",
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

        if (usableKeys == 0) {
            log.warn("LiveScoreAPI [{}] → ALL keys on cooldown, returning null", path);
        } else {
            log.error("LiveScoreAPI [{}] → ALL {} usable keys exhausted, returning null", path, usableKeys);
        }
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

    // ── 1. Verify credentials ─────────────────────────────────────────────

    public boolean verifyCredentials() {
        Map<String, Object> result = callWithFallback("users/pair.json");
        if (result == null) return false;
        Object success = result.get("success");
        return success != null && !"false".equals(success.toString());
    }

    // ── 2. Live Scores ────────────────────────────────────────────────────

    /**
     * Get all currently live matches across all competitions.
     * Returns empty list if API is unavailable — no demo fallback.
     */
    public List<Map<String, Object>> getLiveScores() {
        Map<String, Object> result = callWithFallback("matches/live.json");
        if (result == null) {
            log.warn("getLiveScores: API returned null, returning empty list");
            return Collections.emptyList();
        }
        return extractMatchList(result, "data", "match");
    }

    /**
     * Get live scores for the top 6 leagues only.
     */
    public List<Map<String, Object>> getTop6LiveScores() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
            Map<String, Object> result = callWithFallback(
                    "matches/live.json?competition_id=" + entry.getValue());
            if (result != null) {
                all.addAll(extractMatchList(result, "data", "match"));
            }
            sleepQuietly(200);
        }
        return all;
    }

    public List<Map<String, Object>> getLiveScoresByCompetition(int competitionId) {
        Map<String, Object> result = callWithFallback(
                "matches/live.json?competition_id=" + competitionId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "match");
    }

    public List<Map<String, Object>> getLiveScoresByTeam(int teamId) {
        Map<String, Object> result = callWithFallback(
                "matches/live.json?team_id=" + teamId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "match");
    }

    public List<Map<String, Object>> getLiveScoresByCountry(int countryId) {
        Map<String, Object> result = callWithFallback(
                "matches/live.json?country_id=" + countryId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "match");
    }

    // ── 3. Today's Matches ────────────────────────────────────────────────

    /**
     * Get all matches played or scheduled for today.
     * Returns empty list if API is unavailable — no demo fallback.
     */
    public List<Map<String, Object>> getTodayMatches() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return cached("today:" + today, () -> {
            Map<String, Object> result = callWithFallback("scores/history.json?date=" + today);
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
                        "scores/history.json?date=" + today + "&competition_id=" + entry.getValue());
                if (result != null) all.addAll(extractMatchList(result, "data", "match"));
                sleepQuietly(200);
            }
            return all;
        });
    }

    public List<Map<String, Object>> getMatchesByDate(String date) {
        return cached("history:" + date, () -> {
            Map<String, Object> result = callWithFallback("scores/history.json?date=" + date);
            if (result == null) return Collections.emptyList();
            return extractMatchList(result, "data", "match");
        });
    }

    // ── 4. Upcoming Fixtures ──────────────────────────────────────────────

    /**
     * Get all upcoming fixtures.
     * Returns empty list if API is unavailable — no demo fallback.
     */
    public List<Map<String, Object>> getUpcomingFixtures() {
        return cached("fixtures:all", () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json");
            if (result == null) return Collections.emptyList();
            return extractMatchList(result, "data", "fixture");
        });
    }

    public List<Map<String, Object>> getFixturesByDate(String date) {
        return cached("fixtures:" + date, () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json?date=" + date);
            if (result == null) return Collections.emptyList();
            return extractMatchList(result, "data", "fixture");
        });
    }

    /**
     * Get upcoming fixtures for top 6 leagues.
     * Returns empty list if API is unavailable — no demo fallback.
     */
    public List<Map<String, Object>> getTop6Fixtures() {
        return cached("fixtures:top6", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
                Map<String, Object> result = callWithFallback(
                        "fixtures/matches.json?competition_id=" + entry.getValue());
                if (result != null) all.addAll(extractMatchList(result, "data", "fixture"));
                sleepQuietly(200);
            }
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
        Map<String, Object> result = callWithFallback(
                "fixtures/matches.json?team_id=" + teamId);
        if (result == null) return Collections.emptyList();
        return extractMatchList(result, "data", "fixture");
    }

    // ── 5. Match Details & Statistics ─────────────────────────────────────

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

    // ── 6. Standings ──────────────────────────────────────────────────────

    public Map<String, Object> getStandings(int competitionId) {
        return cached("standings:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "standings/table.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getLiveStandings(int competitionId) {
        Map<String, Object> result = callWithFallback(
                "standings/live.json?competition_id=" + competitionId);
        return result != null ? result : Map.of();
    }

    public Map<String, Map<String, Object>> getAllTop6Standings() {
        return cached("standings:top6", () -> {
            Map<String, Map<String, Object>> all = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : TOP_6_COMPETITION_IDS.entrySet()) {
                Map<String, Object> standings = getStandings(entry.getValue());
                if (standings != null && !standings.isEmpty()) {
                    all.put(entry.getKey(), standings);
                }
                sleepQuietly(200);
            }
            return all;
        });
    }

    // ── 7. Competitions & Leagues ─────────────────────────────────────────

    public Map<String, Object> getAllCompetitions() {
        return cached("competitions:all", () -> {
            Map<String, Object> result = callWithFallback("competitions/list.json");
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getCompetitionsByCountry(int countryId) {
        return cached("competitions:country:" + countryId, () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/list.json?country_id=" + countryId);
            return result != null ? result : Map.of();
        });
    }

    // ── 8. Teams ──────────────────────────────────────────────────────────

    public Map<String, Object> getTeamsByCompetition(int competitionId) {
        return cached("teams:comp:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "teams/list.json?competition_id=" + competitionId);
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

    // ── 9. Top Scorers & Disciplinary ─────────────────────────────────────

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

    // ── 10. Countries & Seasons ───────────────────────────────────────────

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

    // ── 11. Odds (embedded in match responses) ────────────────────────────

    public Map<String, Object> extractOdds(Map<String, Object> matchData) {
        Object odds = matchData.get("odds");
        if (odds instanceof Map<?, ?> oddsMap) {
            return new HashMap<>((Map<String, Object>) oddsMap);
        }
        return Map.of();
    }

    public String extractPreOddsHome(Map<String, Object> matchData) {
        return extractNestedOdds(matchData, "pre", "1");
    }

    public String extractPreOddsDraw(Map<String, Object> matchData) {
        return extractNestedOdds(matchData, "pre", "X");
    }

    public String extractPreOddsAway(Map<String, Object> matchData) {
        return extractNestedOdds(matchData, "pre", "2");
    }

    public String extractLiveOddsHome(Map<String, Object> matchData) {
        return extractNestedOdds(matchData, "live", "1");
    }

    public String extractLiveOddsDraw(Map<String, Object> matchData) {
        return extractNestedOdds(matchData, "live", "X");
    }

    public String extractLiveOddsAway(Map<String, Object> matchData) {
        return extractNestedOdds(matchData, "live", "2");
    }

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

    // ── 12. Field extraction helpers ──────────────────────────────────────

    public static String extractMatchId(Map<String, Object> match) {
        Object v = match.get("id");
        return v != null ? v.toString() : "";
    }

    public static String extractFixtureId(Map<String, Object> match) {
        Object v = match.get("fixture_id");
        return v != null ? v.toString() : "";
    }

    public static String extractHomeName(Map<String, Object> match) {
        Object home = match.get("home");
        if (home instanceof Map<?, ?> homeMap) {
            Object name = ((Map<?, ?>) homeMap).get("name");
            if (name != null) return name.toString();
        }
        return "";
    }

    public static String extractAwayName(Map<String, Object> match) {
        Object away = match.get("away");
        if (away instanceof Map<?, ?> awayMap) {
            Object name = ((Map<?, ?>) awayMap).get("name");
            if (name != null) return name.toString();
        }
        return "";
    }

    public static String extractHomeLogo(Map<String, Object> match) {
        Object home = match.get("home");
        if (home instanceof Map<?, ?> homeMap) {
            Object logo = ((Map<?, ?>) homeMap).get("logo");
            if (logo != null) return logo.toString();
        }
        return "";
    }

    public static String extractAwayLogo(Map<String, Object> match) {
        Object away = match.get("away");
        if (away instanceof Map<?, ?> awayMap) {
            Object logo = ((Map<?, ?>) awayMap).get("logo");
            if (logo != null) return logo.toString();
        }
        return "";
    }

    public static String extractScore(Map<String, Object> match) {
        Object scores = match.get("scores");
        if (scores instanceof Map<?, ?> scoresMap) {
            Object score = ((Map<?, ?>) scoresMap).get("score");
            if (score != null) return score.toString();
        }
        return "-";
    }

    public static String extractHalfTimeScore(Map<String, Object> match) {
        Object scores = match.get("scores");
        if (scores instanceof Map<?, ?> scoresMap) {
            Object ht = ((Map<?, ?>) scoresMap).get("ht_score");
            if (ht != null) return ht.toString();
        }
        return "";
    }

    public static String extractStatus(Map<String, Object> match) {
        Object status = match.get("status");
        return status != null ? status.toString() : "";
    }

    public static String extractMatchTime(Map<String, Object> match) {
        Object time = match.get("time");
        return time != null ? time.toString() : "";
    }

    public static String extractScheduledTime(Map<String, Object> match) {
        Object scheduled = match.get("scheduled");
        return scheduled != null ? scheduled.toString() : "";
    }

    public static String extractCompetitionName(Map<String, Object> match) {
        Object comp = match.get("competition");
        if (comp instanceof Map<?, ?> compMap) {
            Object name = ((Map<?, ?>) compMap).get("name");
            if (name != null) return name.toString();
        }
        return "";
    }

    public static boolean isLive(Map<String, Object> match) {
        String time   = extractMatchTime(match);
        String status = extractStatus(match);
        return "LIVE".equalsIgnoreCase(status) ||
                (!time.isEmpty() && !"FT".equals(time) && !"HT".equals(time)
                        && !time.isEmpty() && !"POSTP".equals(time));
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

    // ── Key status (debugging) ────────────────────────────────────────────

    public Map<String, Object> getKeyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String[] cred : apiCredentials) {
            String key  = cred[0];
            Long until  = keyCooldowns.get(key);
            String tail = tail(key);
            if (until == null || now >= until) {
                status.put("..." + tail, "ACTIVE");
            } else {
                long secondsLeft = (until - now) / 1000;
                status.put("..." + tail, "COOLDOWN (" + secondsLeft + "s remaining)");
            }
        }
        return status;
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractMatchList(Map<String, Object> response,
                                                       String... keys) {
        if (response == null) return Collections.emptyList();
        for (String key : keys) {
            Object val = response.get(key);
            if (val instanceof List<?> list && !list.isEmpty()) {
                return (List<Map<String, Object>>) list;
            }
            if (val instanceof Map<?, ?> nested) {
                for (Object innerVal : ((Map<?, ?>) nested).values()) {
                    if (innerVal instanceof List<?> innerList && !innerList.isEmpty()) {
                        return (List<Map<String, Object>>) innerList;
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    private int extractStatusCode(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return 0;
        try {
            if (msg.contains("401")) return 401;
            if (msg.contains("403")) return 403;
            if (msg.contains("402")) return 402;
            if (msg.contains("429")) return 429;
            if (msg.contains("500")) return 500;
            if (msg.contains("502")) return 502;
            if (msg.contains("503")) return 503;
        } catch (Exception ignored) {}
        return 0;
    }

    private static String tail(String key) {
        return key.length() > 4 ? key.substring(key.length() - 4) : key;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Exception types ───────────────────────────────────────────────────

    private static class SkipKeyException extends RuntimeException {
        SkipKeyException(String msg) { super(msg); }
    }
}