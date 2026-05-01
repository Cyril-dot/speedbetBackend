package com.speedbet.api.match;

import com.speedbet.api.ai.MistralClient;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.LiveScoreApiClient;
import com.speedbet.api.sportsdata.odds.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchRepository           matchRepo;
    private final OddsRepository            oddsRepo;
    private final MistralClient             mistralClient;
    private final LiveScoreApiClient        liveScoreApiClient;

    // ── Odds generators ───────────────────────────────────────────────────
    private final OddsGeneratorService      oddsGeneratorService;
    private final LiveOddsGeneratorService  liveOddsGeneratorService;
    private final CorrectScoreOddsService   correctScoreOddsService;
    private final HalfTimeOddsService       halfTimeOddsService;
    private final HandicapOddsService       handicapOddsService;

    // ── Live odds caches ──────────────────────────────────────────────────
    private static final long LIVE_ODDS_TTL_MS = 2 * 60_000L;

    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveOddsCache     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveHandicapCache = new ConcurrentHashMap<>();

    private record OddsCacheEntry(List<Map<String, Object>> odds, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() <= expiresAt; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw ApiException.notFound("Match not found: " + id); }
    }

    // ── 1X2 cache helpers ─────────────────────────────────────────────────

    public boolean isOddsCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveOddsCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveOddsCache.put(matchId, new OddsCacheEntry(odds, expires));
    }

    // ── Handicap cache helpers ────────────────────────────────────────────

    public boolean isHandicapCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveHandicapCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveHandicapOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveHandicapCache.put(matchId, new OddsCacheEntry(odds, expires));
    }

    /**
     * Exposes the live odds cache to BetService for odds resolution at bet-placement time.
     *
     * Used as a fallback when the DB has no odds row for a LIVE match — this happens when:
     *   a) The match was already live when the server started (generateAndSaveAllOdds only
     *      runs for UPCOMING fixtures, so no DB rows were ever written).
     *   b) The 2-minute live-odds DB write cycle hasn't run yet since the match went live.
     *
     * @param matchId  the match UUID
     * @param market   normalised market key — "1X2" or "asian_handicap"
     * @return the cached odds list, or null if the cache is absent or expired
     */
    public List<Map<String, Object>> getOddsFromCache(UUID matchId, String market) {
        return switch (market) {
            case "1X2" -> {
                OddsCacheEntry entry = liveOddsCache.get(matchId);
                yield (entry != null && entry.isValid()) ? entry.odds() : null;
            }
            case "asian_handicap" -> {
                OddsCacheEntry entry = liveHandicapCache.get(matchId);
                yield (entry != null && entry.isValid()) ? entry.odds() : null;
            }
            default -> {
                log.debug("getOddsFromCache: no cache for market='{}' matchId={}", market, matchId);
                yield null;
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // BASIC QUERIES
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE");
        log.info("getLiveMatches: {} LIVE match(es) found", matches.size());
        return matches;
    }

    @Cacheable("matches")
    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS));
        log.info("getUpcomingMatches: {} upcoming match(es) (next 7 days)", matches.size());
        return matches;
    }

    @Cacheable("todayMatches")
    public List<Match> getTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay);
        log.info("getTodayMatches: {} match(es) today UTC", matches.size());
        return matches;
    }

    @Cacheable("futureMatches")
    public List<Match> getFutureMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS));
        log.info("getFutureMatches: {} match(es) next 7 days", matches.size());
        return matches;
    }

    public List<Match> getRecentResults() {
        return getRecentResultsLimited(20);
    }

    public List<Match> getRecentResultsLimited(int limit) {
        Instant cutoff = Instant.now().minus(48, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("FINISHED").stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .limit(limit)
                .toList();
        log.info("getRecentResults: returning {} FINISHED match(es) (capped at {})", matches.size(), limit);
        return matches;
    }

    @Cacheable("featuredMatches")
    public List<Match> getFeaturedMatches() {
        List<Match> matches = matchRepo.findByFeaturedTrueOrderByKickoffAt();
        log.info("getFeaturedMatches: {} featured match(es)", matches.size());
        return matches;
    }

    public Match getById(String id) {
        return matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("Match not found: " + id));
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIST + ODDS BUNDLES
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> withOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        log.debug("withOdds: bundling odds for {} match(es)", matches.size());

        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (Match match : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);

            String status = match.getStatus();
            if ("LIVE".equals(status)) {
                OddsCacheEntry cached = liveOddsCache.get(match.getId());
                entry.put("odds", (cached != null && cached.isValid()) ? cached.odds() : List.of());

            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("odds", oddsGeneratorService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));

            } else {
                entry.put("odds", List.of());
            }
            out.add(entry);
        }
        log.debug("withOdds: bundled {} entries", out.size());
        return out;
    }

    public List<Map<String, Object>> withAllOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        log.debug("withAllOdds: bundling all markets for {} match(es)", matches.size());

        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (Match match : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);

            String status = match.getStatus();
            if ("LIVE".equals(status)) {
                OddsCacheEntry oddsEntry     = liveOddsCache.get(match.getId());
                OddsCacheEntry handicapEntry = liveHandicapCache.get(match.getId());
                entry.put("match_result",   (oddsEntry     != null && oddsEntry.isValid())     ? oddsEntry.odds()     : List.of());
                entry.put("asian_handicap", (handicapEntry != null && handicapEntry.isValid()) ? handicapEntry.odds() : List.of());

            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("match_result", oddsGeneratorService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
                entry.put("asian_handicap", handicapOddsService.generateHandicapOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));

            } else {
                entry.put("match_result",   List.of());
                entry.put("asian_handicap", List.of());
            }
            out.add(entry);
        }
        log.debug("withAllOdds: bundled {} entries", out.size());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE REFRESH — called by LiveScorePoller every 2 minutes
    // ══════════════════════════════════════════════════════════════════════

    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;

        int refreshed1X2      = 0;
        int refreshedHandicap = 0;

        for (Match match : liveMatches) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);

            try {
                List<Map<String, Object>> liveOdds = liveOddsGeneratorService.generateLiveOdds(
                        match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
                cacheLiveOdds(match.getId(), liveOdds);
                refreshed1X2++;
            } catch (Exception e) {
                log.warn("refreshLiveOddsCache [1X2]: matchId={} failed — {}", match.getId(), e.getMessage());
            }

            try {
                List<Map<String, Object>> liveHandicap = handicapOddsService.generateLiveHandicapOdds(
                        match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
                cacheLiveHandicapOdds(match.getId(), liveHandicap);
                refreshedHandicap++;
            } catch (Exception e) {
                log.warn("refreshLiveOddsCache [Handicap]: matchId={} failed — {}", match.getId(), e.getMessage());
            }
        }

        log.info("refreshLiveOddsCache: 1X2={}/{} Handicap={}/{} match(es) refreshed",
                refreshed1X2, liveMatches.size(), refreshedHandicap, liveMatches.size());
    }

    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object min = match.getMetadata().get("minute");
            if (min != null) {
                try { return Integer.parseInt(min.toString()); } catch (NumberFormatException ignored) {}
            }
        }
        if (match.getKickoffAt() != null) {
            long elapsed = ChronoUnit.MINUTES.between(match.getKickoffAt(), Instant.now());
            return (int) Math.min(Math.max(elapsed, 0), 95);
        }
        return 45;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — direct endpoints (1X2)
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getMatchOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();

        if ("LIVE".equals(status)) {
            OddsCacheEntry cached = liveOddsCache.get(match.getId());
            if (cached != null && cached.isValid()) return cached.odds();
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> generated = liveOddsGeneratorService.generateLiveOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            cacheLiveOdds(match.getId(), generated);
            return generated;
        }

        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return oddsGeneratorService.generatePreMatchOdds(
                    match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
        }

        return List.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — direct endpoints (Correct Score, Half Time)
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getCorrectScoreOdds(String id) {
        Match match = getById(id);
        return correctScoreOddsService.generateCorrectScoreOdds(
                match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
    }

    public List<Map<String, Object>> getHalfTimeOdds(String id) {
        Match match = getById(id);
        if ("LIVE".equals(match.getStatus())) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> liveHt = halfTimeOddsService.generateLiveHalfTimeOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            if (!liveHt.isEmpty()) return liveHt;
        }
        return halfTimeOddsService.generateHalfTimeOdds(
                match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — direct endpoints (Handicap)
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getHandicapOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();

        if ("LIVE".equals(status)) {
            OddsCacheEntry cached = liveHandicapCache.get(match.getId());
            if (cached != null && cached.isValid()) return cached.odds();
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> generated = handicapOddsService.generateLiveHandicapOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            cacheLiveHandicapOdds(match.getId(), generated);
            return generated;
        }

        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return handicapOddsService.generateHandicapOdds(
                    match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
        }

        return List.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — full multi-market bundle
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getAllOddsForMatch(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("match_result",   getMatchOdds(id));
        result.put("correct_score",  getCorrectScoreOdds(id));
        result.put("half_time",      getHalfTimeOdds(id));
        result.put("asian_handicap", getHandicapOdds(id));
        return result;
    }

    public List<Odds> getOddsForMatch(String id) {
        return oddsRepo.findByMatchId(toUuid(id));
    }

    // ══════════════════════════════════════════════════════════════════════
    // MATCH DETAIL / EVENTS / H2H / STANDINGS / STATS / LINEUPS
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null || match.getSource() != MatchSource.LIVESCORE) return Map.of();
        try {
            int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
            Map<String, Object> detail = liveScoreApiClient.getFullMatchDetails(matchId);
            if (detail != null && !detail.isEmpty()) return Map.of("source", "livescore-api", "data", detail);
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    public Map<String, Object> getEvents(String id) {
        Match match = getById(id);
        if (match.getSource() == MatchSource.LIVESCORE && match.getExternalId() != null) {
            try {
                int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
                Map<String, Object> events = liveScoreApiClient.getMatchEvents(matchId);
                if (events != null && !events.isEmpty()) return Map.of("source", "livescore-api", "data", events);
            } catch (NumberFormatException ignored) {}
        }
        return match.getMetadata() != null ? match.getMetadata() : Map.of("events", List.of());
    }

    @Cacheable(value = "h2h", key = "#id")
    public Map<String, Object> getH2H(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        if (match.getSource() == MatchSource.LIVESCORE && match.getMetadata() != null) {
            try {
                Object t1 = match.getMetadata().get("home_team_id");
                Object t2 = match.getMetadata().get("away_team_id");
                if (t1 != null && t2 != null) {
                    Map<String, Object> h2h = liveScoreApiClient.getHeadToHead(
                            Integer.parseInt(t1.toString()), Integer.parseInt(t2.toString()));
                    if (h2h != null && !h2h.isEmpty()) return Map.of("source", "livescore-api", "data", h2h);
                }
            } catch (NumberFormatException ignored) {}
        }
        return Map.of();
    }

    public Map<String, Object> getLiveScoreApiStandings(int competitionId) {
        return liveScoreApiClient.getStandings(competitionId);
    }

    public Map<String, Map<String, Object>> getAllTop6Standings() {
        return liveScoreApiClient.getAllTop6Standings();
    }

    public Map<String, Object> getLiveScoreApiTopScorers(int competitionId) {
        return liveScoreApiClient.getTopScorers(competitionId);
    }

    public Map<String, Object> getStats(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null || match.getSource() != MatchSource.LIVESCORE) return Map.of();
        try {
            int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
            Map<String, Object> stats = liveScoreApiClient.getMatchStats(matchId);
            if (stats != null && !stats.isEmpty())
                return Map.of("source", "livescore-api", "type", "match_stats", "data", stats);
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    @Cacheable(value = "lineups", key = "#id")
    public Map<String, Object> getLineups(String id) {
        Match match = getById(id);
        if (match.getSource() == MatchSource.LIVESCORE && match.getExternalId() != null) {
            try {
                int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
                Map<String, Object> lineup = liveScoreApiClient.getMatchLineup(matchId);
                if (lineup != null && !lineup.isEmpty()) return Map.of("source", "livescore-api", "data", lineup);
            } catch (NumberFormatException ignored) {}
        }
        return Map.of();
    }

    public List<Map<String, Object>> getLiveScoreApiLive()     { return liveScoreApiClient.getLiveScores(); }
    public List<Map<String, Object>> getLiveScoreApiToday()    { return liveScoreApiClient.getTodayMatches(); }
    public List<Map<String, Object>> getLiveScoreApiFixtures() { return liveScoreApiClient.getUpcomingFixtures(); }

    public Map<String, Object> getLiveScoreApiTeamMatches(int teamId) {
        return liveScoreApiClient.getTeamLastMatches(teamId);
    }

    public Map<String, Object> getPrediction(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        Map<String, Object> context = new HashMap<>();
        context.put("home_team", match.getHomeTeam());
        context.put("away_team", match.getAwayTeam());
        context.put("league",    match.getLeague());
        context.put("kickoff",   match.getKickoffAt());
        try {
            Map<String, Object> ai = mistralClient.predictMatch(context);
            if (ai != null && !ai.isEmpty()) return Map.of("source", "ai", "data", ai);
        } catch (Exception e) {
            log.warn("getPrediction: matchId={} failed — {}", id, e.getMessage());
        }
        return Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match saveOrUpdate(Match match) {
        if (match.getExternalId() == null || match.getExternalId().isBlank())
            return matchRepo.save(match);

        return matchRepo.findByExternalId(match.getExternalId())
                .map(existing -> {
                    if (match.getStatus()    != null) existing.setStatus(match.getStatus());
                    if (match.getScoreHome() != null) existing.setScoreHome(match.getScoreHome());
                    if (match.getScoreAway() != null) existing.setScoreAway(match.getScoreAway());
                    if (match.getMetadata()  != null) existing.setMetadata(match.getMetadata());
                    if (existing.getHomeTeam()   == null && match.getHomeTeam()   != null) existing.setHomeTeam(match.getHomeTeam());
                    if (existing.getAwayTeam()   == null && match.getAwayTeam()   != null) existing.setAwayTeam(match.getAwayTeam());
                    if (existing.getLeague()     == null && match.getLeague()     != null) existing.setLeague(match.getLeague());
                    if (existing.getSport()      == null && match.getSport()      != null) existing.setSport(match.getSport());
                    if (existing.getHomeLogo()   == null && match.getHomeLogo()   != null) existing.setHomeLogo(match.getHomeLogo());
                    if (existing.getAwayLogo()   == null && match.getAwayLogo()   != null) existing.setAwayLogo(match.getAwayLogo());
                    if (existing.getLeagueLogo() == null && match.getLeagueLogo() != null) existing.setLeagueLogo(match.getLeagueLogo());
                    if (existing.getKickoffAt()  == null && match.getKickoffAt()  != null) existing.setKickoffAt(match.getKickoffAt());
                    if (existing.getSource()     == null && match.getSource()     != null) existing.setSource(match.getSource());
                    return matchRepo.save(existing);
                })
                .orElseGet(() -> matchRepo.save(match));
    }

    @Transactional
    @CacheEvict(value = {"matches", "todayMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLive(cutoff);
        if (stale.isEmpty()) return 0;
        log.info("finishStaleLiveMatches: force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) { m.setStatus("FINISHED"); matchRepo.save(m); }
        return stale.size();
    }

    public List<Match> getUnsettledFinished() { return matchRepo.findUnsettledFinished(); }

    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
        });
    }
}