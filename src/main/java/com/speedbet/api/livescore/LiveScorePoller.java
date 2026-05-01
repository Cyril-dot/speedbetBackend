package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.sportsdata.LiveScoreApiClient;
import com.speedbet.api.sportsdata.odds.OddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralised polling orchestrator.
 *
 * Schedules:
 *   • Live scores       — every  30s
 *   • Today's fixtures  — every  15min
 *   • Upcoming (7 days) — every   1h
 *   • Stale LIVE sweep  — every  10min
 *   • Live odds refresh — every   2min  (cache + DB)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveScorePoller {

    private final LiveScoreApiClient     liveScoreApiClient;
    private final MatchService           matchService;
    private final OddsPersistenceService oddsPersistenceService;
    private final CacheManager           cacheManager;

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        log.debug("=== Live score poll starting ===");
        try {
            List<Map<String, Object>> lsLive = liveScoreApiClient.getLiveScores();
            if (lsLive == null || lsLive.isEmpty()) {
                log.info("Live poll [LiveScoreApi]: no live scores returned.");
            } else {
                log.info("Live poll [LiveScoreApi]: {} live matches received.", lsLive.size());
                int updated = 0, skipped = 0;
                for (Map<String, Object> event : lsLive) {
                    try {
                        Match m = mapLiveScoreApiMatchToMatch(event, true);
                        if (m != null) {
                            matchService.saveOrUpdate(m);
                            updated++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Live poll: failed event id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Live poll [LiveScoreApi]: done — updated={}, skipped={}.", updated, skipped);
            }
        } catch (Exception e) {
            log.error("Live poll [LiveScoreApi]: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== Live score poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. TODAY'S FIXTURES — every 15 minutes
    //    Saves pre-match odds for any newly persisted fixture.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 10_000L)
    public void pollTodaysFixtures() {
        String today = java.time.LocalDate.now().toString();
        log.info("=== Today's fixtures poll starting for date={} ===", today);
        try {
            List<Map<String, Object>> history = liveScoreApiClient.getMatchesByDate(today);
            if (history == null || history.isEmpty()) {
                log.info("Today poll: no matches returned for date={}.", today);
            } else {
                int saved = 0, skipped = 0;
                for (Map<String, Object> event : history) {
                    try {
                        Match m = mapLiveScoreApiMatchToMatch(event, false);
                        if (m != null) {
                            Match persisted = matchService.saveOrUpdate(m);
                            if ("UPCOMING".equals(persisted.getStatus()) ||
                                    "SCHEDULED".equals(persisted.getStatus())) {
                                try {
                                    oddsPersistenceService.generateAndSaveAllOdds(persisted);
                                } catch (Exception oe) {
                                    log.warn("Today poll: odds save failed matchId={} — {}",
                                            persisted.getId(), oe.getMessage());
                                }
                            }
                            saved++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Today poll: failed event id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Today poll: done — saved={}, skipped={}.", saved, skipped);
                // Evict all match caches once after the full poll completes so the
                // next HTTP request reads the complete dataset, not a partial one.
                evictMatchCaches();
            }
        } catch (Exception e) {
            log.error("Today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Today's fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. UPCOMING FIXTURES (next 7 days) — every hour
    //    Saves pre-match odds for each fixture so bets can be placed.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Upcoming fixtures poll starting ===");
        try {
            List<Map<String, Object>> fixtures = liveScoreApiClient.getTop6Fixtures();
            if (fixtures == null || fixtures.isEmpty()) {
                log.info("Upcoming poll: no top-6 fixtures returned.");
            } else {
                int saved = 0, skipped = 0;
                for (Map<String, Object> event : fixtures) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null) {
                            Match persisted = matchService.saveOrUpdate(m);
                            try {
                                oddsPersistenceService.generateAndSaveAllOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Upcoming poll: odds save failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            saved++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Upcoming poll: failed fixture id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Upcoming poll: done — saved={}, skipped={}.", saved, skipped);
                // Evict all match caches once after the full poll completes so the
                // next HTTP request reads the complete dataset, not a partial one.
                evictMatchCaches();
            }
        } catch (Exception e) {
            log.error("Upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Upcoming fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. STALE LIVE SWEEP — every 10 minutes
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLiveMatches() {
        log.debug("=== Stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
            int closed = matchService.finishStaleLiveMatches(cutoff);
            if (closed > 0) log.info("Stale sweep: force-finished {} LIVE matches.", closed);
            else log.debug("Stale sweep: no stale LIVE matches found.");
        } catch (Exception e) {
            log.error("Stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== Stale LIVE sweep complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. LIVE ODDS REFRESH — every 2 minutes
    //    Updates both the in-memory cache (for fast reads) AND the DB
    //    (so BetService can resolve odds at bet placement time).
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 15_000L)
    public void refreshLiveOdds() {
        log.debug("=== Live odds refresh starting ===");
        try {
            List<Match> liveMatches = matchService.getLiveMatches();
            if (liveMatches.isEmpty()) {
                log.debug("Live odds refresh: no live matches, skipping.");
                return;
            }
            log.info("Live odds refresh: regenerating odds for {} live match(es).", liveMatches.size());

            matchService.refreshLiveOddsCache(liveMatches);

            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                try {
                    oddsPersistenceService.generateAndSaveLiveOdds(match);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Live odds refresh: DB save failed matchId={} — {}",
                            match.getId(), e.getMessage());
                }
            }
            log.info("Live odds refresh: DB persisted={}/{} failed={}",
                    persisted, liveMatches.size(), failed);

        } catch (Exception e) {
            log.warn("Live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== Live odds refresh complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Clears all match-related Spring caches in one shot after a bulk poll
     * completes. This ensures the next HTTP request re-queries the DB with
     * the full dataset rather than serving a stale empty or partial result
     * that was cached mid-poll.
     */
    private void evictMatchCaches() {
        for (String name : List.of("matches", "todayMatches", "futureMatches", "featuredMatches")) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        log.debug("evictMatchCaches: all match caches cleared after poll.");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    private Match mapLiveScoreApiMatchToMatch(Map<String, Object> event, boolean forceStatusLive) {
        if (event == null) return null;

        String externalId = LiveScoreApiClient.extractMatchId(event);
        if (externalId == null || externalId.isBlank()) return null;

        Match match = new Match();
        match.setExternalId("ls-" + externalId);
        match.setSource(MatchSource.LIVESCORE);
        match.setSport("football");

        if (forceStatusLive) {
            match.setStatus("LIVE");
        } else if (LiveScoreApiClient.isFinished(event)) {
            match.setStatus("FINISHED");
        } else if (LiveScoreApiClient.isLive(event)) {
            match.setStatus("LIVE");
        } else {
            match.setStatus("UPCOMING");
        }

        match.setHomeTeam(LiveScoreApiClient.extractHomeName(event));
        match.setAwayTeam(LiveScoreApiClient.extractAwayName(event));
        match.setHomeLogo(LiveScoreApiClient.extractHomeLogo(event));
        match.setAwayLogo(LiveScoreApiClient.extractAwayLogo(event));
        match.setLeague(LiveScoreApiClient.extractCompetitionName(event));

        String scoreStr = LiveScoreApiClient.extractScore(event);
        if (scoreStr != null && scoreStr.contains("-")) {
            String[] parts = scoreStr.split("-");
            if (parts.length == 2) {
                try { match.setScoreHome(Integer.parseInt(parts[0].trim())); } catch (NumberFormatException ignored) {}
                try { match.setScoreAway(Integer.parseInt(parts[1].trim())); } catch (NumberFormatException ignored) {}
            }
        }

        Instant kickoff = parseInstant(LiveScoreApiClient.extractScheduledTime(event));
        if (kickoff == null && "LIVE".equals(match.getStatus())) {
            kickoff = Instant.now().minus(45, ChronoUnit.MINUTES);
        }
        match.setKickoffAt(kickoff);

        // ── Half-time scores → metadata (required for half_time bet settlement) ──
        String htScore = LiveScoreApiClient.extractHalfTimeScore(event);
        if (htScore != null && htScore.contains("-")) {
            String[] htParts = htScore.split("-");
            if (htParts.length == 2) {
                try {
                    int htHome = Integer.parseInt(htParts[0].trim());
                    int htAway = Integer.parseInt(htParts[1].trim());
                    Map<String, Object> meta = match.getMetadata() != null
                            ? new HashMap<>(match.getMetadata())
                            : new HashMap<>();
                    meta.put("score_home_ht", htHome);
                    meta.put("score_away_ht", htAway);
                    match.setMetadata(meta);
                    log.debug("mapLiveScoreApiMatchToMatch: externalId={} ht_score={}-{}",
                            externalId, htHome, htAway);
                } catch (NumberFormatException ignored) {
                    log.debug("mapLiveScoreApiMatchToMatch: could not parse ht_score '{}'", htScore);
                }
            }
        }

        return match;
    }

    private Match mapLiveScoreApiFixtureToMatch(Map<String, Object> event) {
        if (event == null) return null;

        String externalId = LiveScoreApiClient.extractFixtureId(event);
        if (externalId == null || externalId.isBlank()) {
            externalId = LiveScoreApiClient.extractMatchId(event);
        }
        if (externalId == null || externalId.isBlank()) return null;

        Match match = new Match();
        match.setExternalId("ls-" + externalId);
        match.setSource(MatchSource.LIVESCORE);
        match.setSport("football");
        match.setStatus("UPCOMING");

        match.setHomeTeam(LiveScoreApiClient.extractHomeName(event));
        match.setAwayTeam(LiveScoreApiClient.extractAwayName(event));
        match.setHomeLogo(LiveScoreApiClient.extractHomeLogo(event));
        match.setAwayLogo(LiveScoreApiClient.extractAwayLogo(event));
        match.setLeague(LiveScoreApiClient.extractCompetitionName(event));
        match.setKickoffAt(parseInstant(LiveScoreApiClient.extractScheduledTime(event)));

        return match;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILS
    // ═══════════════════════════════════════════════════════════════════════

    private static Instant parseInstant(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try {
            long epoch = Long.parseLong(s);
            return (epoch > 10_000_000_000L) ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException ignored) {}
        try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeParseException ignored) {}
        try {
            return OffsetDateTime.parse(s + "Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {}
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant();
        } catch (DateTimeParseException ignored) {}
        log.debug("parseInstant: could not parse '{}'", s);
        return null;
    }
}