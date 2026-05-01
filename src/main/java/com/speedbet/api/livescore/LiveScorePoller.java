package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.sportsdata.LiveScoreApiClient;
import com.speedbet.api.sportsdata.TeamLogoCache;
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
 *   • Logo cache warm   — at startup + every 6h (competition-specific endpoints → TeamLogoCache)
 *   • Live scores       — every  30s
 *   • Today's fixtures  — every  15min
 *   • Upcoming (7 days) — every   1h
 *   • Stale LIVE sweep  — every  10min
 *   • Live odds refresh — every   2min
 *
 * ── How logos flow through the system ────────────────────────────────────
 *
 *   LIVE matches (pollLiveScores):
 *     matches/live.json always returns nested home.logo / away.logo.
 *     extractHomeLogo() reads home.logo → logos are always present.
 *     These are used as the reference shape — everything else mirrors this.
 *
 *   TOP-6 fixtures (pollUpcomingFixtures Step A):
 *     fixtures/matches.json?competition_id=X returns same nested shape as live.
 *     Logos extracted directly + ingested into TeamLogoCache for later reuse.
 *
 *   GENERAL fixtures (pollUpcomingFixtures Step B):
 *     fixtures/matches.json (no competition_id) returns flat home_image/away_image.
 *     extractHomeLogo() now reads these flat fields as fallback (fix applied).
 *     Any remaining blanks are backfilled from TeamLogoCache via enrichLogos().
 *     Fixtures already saved in Step A are deduplicated by externalId.
 *
 *   Result: top-6 fixtures always get logos; other leagues get logos from
 *           the cache or from flat API fields where available.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveScorePoller {

    private final LiveScoreApiClient     liveScoreApiClient;
    private final MatchService           matchService;
    private final OddsPersistenceService oddsPersistenceService;
    private final CacheManager           cacheManager;
    private final TeamLogoCache          teamLogoCache;

    // ═══════════════════════════════════════════════════════════════════════
    // 0. LOGO CACHE WARM — runs at startup (delay=2s) then every 6 hours
    //
    //    Calls competition-specific fixture/live/history endpoints which
    //    return nested home.logo / away.logo (same shape as live scores).
    //    Ingests results into TeamLogoCache so Step B of upcoming poll can
    //    backfill logos for general-endpoint fixtures.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 6 * 60 * 60_000L, initialDelay = 2_000L)
    public void warmLogoCache() {
        log.info("=== Logo cache warm starting ===");
        try {
            // Top-6 fixtures — nested home.logo / away.logo (same shape as live)
            List<Map<String, Object>> top6 = liveScoreApiClient.getTop6Fixtures();
            teamLogoCache.ingest(top6);
            log.info("Logo cache warm: ingested {} top-6 fixtures", top6.size());

            // Live scores — always carry logos, opportunistic warm
            List<Map<String, Object>> live = liveScoreApiClient.getLiveScores();
            teamLogoCache.ingest(live);

            // Today history — also carries logos
            String today = java.time.LocalDate.now().toString();
            List<Map<String, Object>> todayMatches = liveScoreApiClient.getMatchesByDate(today);
            teamLogoCache.ingest(todayMatches);

            log.info("Logo cache warm: complete — cache size={}", teamLogoCache.size());
        } catch (Exception e) {
            log.warn("Logo cache warm: error — {}", e.getMessage());
        }
        log.info("=== Logo cache warm complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    //
    //    matches/live.json always returns nested home.logo / away.logo.
    //    This is the reference shape: extractHomeLogo() reads home.logo first.
    //    Live logos are used to opportunistically warm TeamLogoCache too.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        log.debug("=== Live score poll starting ===");
        try {
            List<Map<String, Object>> lsLive = liveScoreApiClient.getLiveScores();
            if (lsLive == null || lsLive.isEmpty()) {
                log.info("Live poll: no live scores returned.");
            } else {
                // Live matches always have logos — ingest to warm cache for fixture backfill
                teamLogoCache.ingest(lsLive);
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
    //
    //    history endpoint returns logos — ingest to warm cache.
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
                teamLogoCache.ingest(history); // today matches carry logos — warm cache
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
    //    Strategy mirrors how live scores always have logos:
    //
    //    Step A — getTop6Fixtures() → competition-specific endpoint per league.
    //             Returns nested home.logo / away.logo (same shape as live).
    //             Ingest into TeamLogoCache AND persist matches directly.
    //             These fixtures arrive with logos, exactly like live matches do.
    //
    //    Step B — getUpcomingFixtures() → general endpoint (no competition_id).
    //             Returns flat home_image / away_image (handled by extractHomeLogo).
    //             enrichLogos() backfills any remaining blanks from TeamLogoCache.
    //             matchService.saveOrUpdate() deduplicates by externalId so
    //             Step A fixtures are updated, not duplicated.
    //
    //    Outcome: all top-6 fixtures have logos (Step A); other leagues get logos
    //             from flat API fields or cache where available (Step B).
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Upcoming fixtures poll starting ===");
        try {
            // ── Step A: top-6 competition-specific fixtures (logos included) ──
            // Same nested home.logo / away.logo shape as the live endpoint.
            log.info("Upcoming poll [A]: fetching top-6 competition fixtures...");
            List<Map<String, Object>> top6 = liveScoreApiClient.getTop6Fixtures();
            int top6Saved = 0, top6Skipped = 0;
            if (top6 != null && !top6.isEmpty()) {
                teamLogoCache.ingest(top6); // warm cache with fresh logos before Step B
                for (Map<String, Object> event : top6) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null && m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                            // Logos already extracted from nested home.logo / away.logo
                            Match persisted = matchService.saveOrUpdate(m);
                            try {
                                oddsPersistenceService.generateAndSaveAllOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Upcoming poll [A]: odds save failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            top6Saved++;
                        } else {
                            top6Skipped++;
                        }
                    } catch (Exception e) {
                        top6Skipped++;
                        log.warn("Upcoming poll [A]: failed fixture id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Upcoming poll [A]: done — saved={}, skipped={}", top6Saved, top6Skipped);
            } else {
                log.info("Upcoming poll [A]: no top-6 fixtures returned.");
            }

            // ── Step B: general endpoint (flat home_image/away_image + cache backfill) ──
            // extractHomeLogo() reads home_image as fallback; enrichLogos() fills the rest.
            log.info("Upcoming poll [B]: fetching general upcoming fixtures... Logo cache size={}",
                    teamLogoCache.size());
            List<Map<String, Object>> fixtures = liveScoreApiClient.getUpcomingFixtures();
            if (fixtures == null || fixtures.isEmpty()) {
                log.info("Upcoming poll [B]: no fixtures returned from general endpoint.");
            } else {
                log.info("Upcoming poll [B]: {} fixtures to process.", fixtures.size());
                int saved = 0, skipped = 0, logoHits = 0;
                for (Map<String, Object> event : fixtures) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null) {
                            if (m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                                // enrichLogos fills any blanks left after extractHomeLogo
                                logoHits += enrichLogos(m);
                                Match persisted = matchService.saveOrUpdate(m);
                                try {
                                    oddsPersistenceService.generateAndSaveAllOdds(persisted);
                                } catch (Exception oe) {
                                    log.warn("Upcoming poll [B]: odds save failed matchId={} — {}",
                                            persisted.getId(), oe.getMessage());
                                }
                                saved++;
                            } else {
                                log.debug("Upcoming poll [B]: skipping past/null kickoff for externalId={}",
                                        m.getExternalId());
                                skipped++;
                            }
                        } else skipped++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Upcoming poll [B]: failed fixture id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Upcoming poll [B]: done — saved={}, skipped={}, logoHits={}.", saved, skipped, logoHits);
            }

            evictMatchCaches();
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
    // LOGO ENRICHMENT
    //
    // Called after mapLiveScoreApiFixtureToMatch() to backfill any logos
    // that extractHomeLogo() could not resolve from the raw API payload.
    // Uses TeamLogoCache which is warmed from live + competition-specific
    // endpoints (both of which always return logos).
    //
    // Returns count of fields filled (0–3).
    // ═══════════════════════════════════════════════════════════════════════

    private int enrichLogos(Match match) {
        int hits = 0;
        if (isBlank(match.getHomeLogo())) {
            String logo = teamLogoCache.getTeamLogo(match.getHomeTeam());
            if (!logo.isBlank()) { match.setHomeLogo(logo); hits++; }
        }
        if (isBlank(match.getAwayLogo())) {
            String logo = teamLogoCache.getTeamLogo(match.getAwayTeam());
            if (!logo.isBlank()) { match.setAwayLogo(logo); hits++; }
        }
        if (isBlank(match.getLeagueLogo())) {
            String logo = teamLogoCache.getLeagueLogo(match.getLeague());
            if (!logo.isBlank()) { match.setLeagueLogo(logo); hits++; }
        }
        return hits;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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

    /**
     * Maps a live score API event to a Match entity.
     *
     * Uses the same extractHomeLogo() / extractAwayLogo() that live scores do —
     * these read home.logo from the nested "home" object, which is always present
     * in the live endpoint response.
     */
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
        match.setHomeLogo(LiveScoreApiClient.extractHomeLogo(event));   // reads home.logo (nested)
        match.setAwayLogo(LiveScoreApiClient.extractAwayLogo(event));   // reads away.logo (nested)
        match.setLeague(LiveScoreApiClient.extractCompetitionName(event));
        match.setLeagueLogo(LiveScoreApiClient.extractLeagueLogo(event));
        enrichLogos(match); // fill any blanks from cache (defensive)

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

    /**
     * Maps a fixture API event to a Match entity.
     *
     * Handles both response shapes:
     *   - Competition-specific (Step A): nested home.logo / away.logo
     *   - General endpoint (Step B):     flat home_image / away_image
     *
     * extractHomeLogo() resolves whichever field is present.
     * enrichLogos() is called by the caller (Step B) to fill any remaining blanks.
     */
    private Match mapLiveScoreApiFixtureToMatch(Map<String, Object> event) {
        if (event == null) return null;

        String externalId = LiveScoreApiClient.extractFixtureId(event);
        if (externalId == null || externalId.isBlank()) return null;

        String homeName = LiveScoreApiClient.extractHomeName(event);
        String awayName = LiveScoreApiClient.extractAwayName(event);

        if (homeName.isBlank() || awayName.isBlank()) {
            log.debug("mapLiveScoreApiFixtureToMatch: blank team names for id={}, skipping", externalId);
            return null;
        }

        String homeLogo   = LiveScoreApiClient.extractHomeLogo(event);   // nested OR flat fallback
        String awayLogo   = LiveScoreApiClient.extractAwayLogo(event);   // nested OR flat fallback
        String leagueLogo = LiveScoreApiClient.extractLeagueLogo(event);

        log.debug("mapLiveScoreApiFixtureToMatch: id={} home='{}' homeLogo='{}' awayLogo='{}'",
                externalId, homeName, homeLogo, awayLogo);

        Match match = new Match();
        match.setExternalId("ls-" + externalId);
        match.setSource(MatchSource.LIVESCORE);
        match.setSport("football");
        match.setStatus("UPCOMING");
        match.setHomeTeam(homeName);
        match.setAwayTeam(awayName);
        match.setHomeLogo(homeLogo);
        match.setAwayLogo(awayLogo);
        match.setLeague(LiveScoreApiClient.extractCompetitionName(event));
        match.setLeagueLogo(leagueLogo);

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