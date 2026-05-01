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
 *   • Live odds refresh — every   2min
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
                log.info("Live poll: no live scores returned.");
            } else {
                log.info("Live poll: {} live matches received.", lsLive.size());
                int updated = 0, skipped = 0;
                for (Map<String, Object> event : lsLive) {
                    try {
                        Match m = mapLiveScoreApiMatchToMatch(event, true);
                        if (m != null) { matchService.saveOrUpdate(m); updated++; }
                        else skipped++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Live poll: failed event id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Live poll: done — updated={}, skipped={}.", updated, skipped);
            }
        } catch (Exception e) {
            log.error("Live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== Live score poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. TODAY'S FIXTURES — every 15 minutes
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
                        } else skipped++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Today poll: failed event id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Today poll: done — saved={}, skipped={}.", saved, skipped);
                evictMatchCaches();
            }
        } catch (Exception e) {
            log.error("Today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Today's fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. UPCOMING FIXTURES (next 7 days) — every hour
    //
    //    Strategy:
    //    a) Try top-6 leagues via competition-specific endpoint first
    //       (nested home.name/away.name + "scheduled" ISO field)
    //    b) If top-6 returns nothing, fall back to general endpoint
    //       (flat home_name/away_name + "date"+"time" fields)
    //    Both shapes are handled by the extractor methods.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Upcoming fixtures poll starting ===");
        try {
            // Primary: top-6 leagues (competition-specific endpoint)
            List<Map<String, Object>> fixtures = liveScoreApiClient.getTop6Fixtures();

            // Fallback: general endpoint if top-6 is empty
            if (fixtures == null || fixtures.isEmpty()) {
                log.info("Upcoming poll: top-6 returned no fixtures, trying general endpoint...");
                fixtures = liveScoreApiClient.getUpcomingFixtures();
            }

            if (fixtures == null || fixtures.isEmpty()) {
                log.info("Upcoming poll: no fixtures returned from any endpoint.");
            } else {
                log.info("Upcoming poll: {} fixtures to process.", fixtures.size());
                int saved = 0, skipped = 0;
                for (Map<String, Object> event : fixtures) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null) {
                            // Only persist if kickoff is in the future
                            if (m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                                Match persisted = matchService.saveOrUpdate(m);
                                try {
                                    oddsPersistenceService.generateAndSaveAllOdds(persisted);
                                } catch (Exception oe) {
                                    log.warn("Upcoming poll: odds save failed matchId={} — {}",
                                            persisted.getId(), oe.getMessage());
                                }
                                saved++;
                            } else {
                                log.debug("Upcoming poll: skipping past/null kickoff for externalId={}",
                                        m.getExternalId());
                                skipped++;
                            }
                        } else skipped++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Upcoming poll: failed fixture id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Upcoming poll: done — saved={}, skipped={}.", saved, skipped);
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
            log.info("Live odds refresh: {} live match(es).", liveMatches.size());
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
            log.info("Live odds refresh: persisted={}/{} failed={}", persisted, liveMatches.size(), failed);
        } catch (Exception e) {
            log.warn("Live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== Live odds refresh complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void evictMatchCaches() {
        List<String> names = List.of("matches", "todayMatches", "futureMatches", "featuredMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            log.info("evictMatchCaches: cache='{}' found={}", name, cache != null);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("evictMatchCaches: {}/{} caches cleared.", cleared, names.size());
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

        Instant kickoff = LiveScoreApiClient.buildKickoffInstant(event);
        if (kickoff == null && "LIVE".equals(match.getStatus())) {
            kickoff = Instant.now().minus(45, ChronoUnit.MINUTES);
        }
        match.setKickoffAt(kickoff);

        String htScore = LiveScoreApiClient.extractHalfTimeScore(event);
        if (htScore != null && htScore.contains("-")) {
            String[] htParts = htScore.split("-");
            if (htParts.length == 2) {
                try {
                    int htHome = Integer.parseInt(htParts[0].trim());
                    int htAway = Integer.parseInt(htParts[1].trim());
                    Map<String, Object> meta = match.getMetadata() != null
                            ? new HashMap<>(match.getMetadata()) : new HashMap<>();
                    meta.put("score_home_ht", htHome);
                    meta.put("score_away_ht", htAway);
                    match.setMetadata(meta);
                } catch (NumberFormatException ignored) {}
            }
        }

        return match;
    }

    private Match mapLiveScoreApiFixtureToMatch(Map<String, Object> event) {
        if (event == null) return null;

        // extractFixtureId handles both "fixture_id" (competition-specific)
        // and "id" (general endpoint) — whichever is present
        String externalId = LiveScoreApiClient.extractFixtureId(event);
        if (externalId == null || externalId.isBlank()) return null;

        String homeName = LiveScoreApiClient.extractHomeName(event);
        String awayName = LiveScoreApiClient.extractAwayName(event);

        // Skip if team names are blank — indicates a malformed response row
        if (homeName.isBlank() || awayName.isBlank()) {
            log.debug("mapLiveScoreApiFixtureToMatch: blank team names for id={}, skipping", externalId);
            return null;
        }

        Match match = new Match();
        match.setExternalId("ls-" + externalId);
        match.setSource(MatchSource.LIVESCORE);
        match.setSport("football");
        match.setStatus("UPCOMING");
        match.setHomeTeam(homeName);
        match.setAwayTeam(awayName);
        match.setHomeLogo(LiveScoreApiClient.extractHomeLogo(event));
        match.setAwayLogo(LiveScoreApiClient.extractAwayLogo(event));
        match.setLeague(LiveScoreApiClient.extractCompetitionName(event));

        // buildKickoffInstant handles both:
        //  - competition-specific: "scheduled" ISO datetime
        //  - general endpoint:     "date" + "time" fields
        Instant kickoff = LiveScoreApiClient.buildKickoffInstant(event);
        match.setKickoffAt(kickoff);

        if (kickoff != null) {
            log.debug("mapLiveScoreApiFixtureToMatch: id={} {} vs {} kickoff={}",
                    externalId, homeName, awayName, kickoff);
        } else {
            log.warn("mapLiveScoreApiFixtureToMatch: id={} {} vs {} — could not parse kickoff",
                    externalId, homeName, awayName);
        }

        return match;
    }
}