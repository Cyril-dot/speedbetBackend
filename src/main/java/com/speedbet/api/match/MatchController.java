package com.speedbet.api.match;

import com.speedbet.api.ai.MistralClient;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.odds.Odds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MatchController {

    private final MatchService  matchService;
    private final MistralClient mistralClient;

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC LOBBY ENDPOINTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatches() {
        log.debug("GET /api/public/matches — fetching all lobby buckets");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/matches — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     live);
        payload.put("today",    today);
        payload.put("upcoming", upcoming);
        payload.put("future",   future);
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/matches/with-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchesWithOdds() {
        log.debug("GET /api/public/matches/with-odds — fetching all lobby buckets with odds");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/matches/with-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     matchService.withOdds(live));
        payload.put("today",    matchService.withOdds(today));
        payload.put("upcoming", matchService.withOdds(upcoming));
        payload.put("future",   matchService.withOdds(future));
        payload.put("results",  matchService.withOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/matches/with-all-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchesWithAllOdds() {
        log.debug("GET /api/public/matches/with-all-odds — fetching all lobby buckets with all markets");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/matches/with-all-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     matchService.withAllOdds(live));
        payload.put("today",    matchService.withAllOdds(today));
        payload.put("upcoming", matchService.withAllOdds(upcoming));
        payload.put("future",   matchService.withAllOdds(future));
        payload.put("results",  matchService.withAllOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicLive() {
        log.debug("GET /api/public/matches/live — fetching live matches with odds");
        List<Match> live = matchService.getLiveMatches();
        log.info("GET /api/public/matches/live — {} live match(es) found", live.size());
        if (live.isEmpty()) {
            log.warn("GET /api/public/matches/live — no live matches in DB right now");
        } else {
            live.forEach(m -> log.debug("  live match: externalId={} home='{}' away='{}' score={}-{} status={}",
                    m.getExternalId(), m.getHomeTeam(), m.getAwayTeam(),
                    m.getScoreHome(), m.getScoreAway(), m.getStatus()));
        }
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(live)));
    }

    @GetMapping("/api/public/matches/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicToday() {
        log.debug("GET /api/public/matches/today — fetching today's matches with odds");
        List<Match> today = matchService.getTodayMatches();
        log.info("GET /api/public/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(today)));
    }

    @GetMapping("/api/public/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicUpcoming() {
        log.debug("GET /api/public/matches/upcoming — fetching upcoming matches with odds");
        List<Match> upcoming = matchService.getUpcomingMatches();
        log.info("GET /api/public/matches/upcoming — {} match(es) upcoming", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(upcoming)));
    }

    @GetMapping("/api/public/matches/future")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFuture() {
        log.debug("GET /api/public/matches/future — fetching future matches with odds");
        List<Match> future = matchService.getFutureMatches();
        log.info("GET /api/public/matches/future — {} match(es) in next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(future)));
    }

    @GetMapping("/api/public/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicResults() {
        log.debug("GET /api/public/matches/results — fetching recent results");
        List<Match> results = matchService.getRecentResults();
        log.info("GET /api/public/matches/results — {} recent result(s)", results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping("/api/public/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> featuredMatches() {
        log.debug("GET /api/public/matches/featured — fetching featured matches");
        List<Match> featured = matchService.getFeaturedMatches();
        log.info("GET /api/public/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    @GetMapping("/api/public/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicMatchById(@PathVariable String id) {
        log.debug("GET /api/public/matches/{} — fetching match by id", id);
        Match match = matchService.getById(id);
        log.info("GET /api/public/matches/{} — found: home='{}' away='{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/public/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFullDetail(
            @PathVariable String id) {
        log.debug("GET /api/public/matches/{}/detail — fetching full detail bundle", id);
        Match match                    = matchService.getById(id);
        Map<String, Object> detail     = matchService.getMatchDetail(id);
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        Map<String, Object> prediction = matchService.getPrediction(id);
        log.info("GET /api/public/matches/{}/detail — match='{}' vs '{}' detailKeys={} oddsCount={} hasPrediction={}",
                id, match.getHomeTeam(), match.getAwayTeam(),
                detail.keySet(), odds.size(), !prediction.isEmpty());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",      match);
        bundle.put("detail",     detail);
        bundle.put("odds",       odds);
        bundle.put("prediction", prediction);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping("/api/public/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicMatchOdds(
            @PathVariable String id) {
        log.debug("GET /api/public/matches/{}/odds — fetching odds", id);
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        log.info("GET /api/public/matches/{}/odds — {} odds entry/entries returned", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/standings/top6")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Object>>>> publicTop6Standings() {
        log.info("GET /api/public/standings/top6 — fetching all top-6 standings from LiveScoreApi");
        Map<String, Map<String, Object>> standings = matchService.getAllTop6Standings();
        log.debug("GET /api/public/standings/top6 — {} league(s) returned", standings.size());
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    @GetMapping("/api/public/standings/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicStandings(
            @PathVariable int competitionId) {
        log.info("GET /api/public/standings/{} — fetching standings from LiveScoreApi", competitionId);
        Map<String, Object> standings = matchService.getLiveScoreApiStandings(competitionId);
        log.debug("GET /api/public/standings/{} — {} key(s) in response", competitionId, standings.size());
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    @GetMapping("/api/public/scorers/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTopScorers(
            @PathVariable int competitionId) {
        log.info("GET /api/public/scorers/{} — fetching top scorers from LiveScoreApi", competitionId);
        Map<String, Object> scorers = matchService.getLiveScoreApiTopScorers(competitionId);
        log.debug("GET /api/public/scorers/{} — {} key(s) in response", competitionId, scorers.size());
        return ResponseEntity.ok(ApiResponse.ok(scorers));
    }

    @GetMapping("/api/public/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> config() {
        log.debug("GET /api/public/config — returning platform config");
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "minDepositAmount", 300,
                "currency",         "GHS",
                "platformName",     "SpeedBet",
                "slogan",           "HIT DIFFERENT. CASH OUT SMART."
        )));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — CORE MATCH DATA
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/live")
    public ResponseEntity<ApiResponse<List<Match>>> liveMatches() {
        log.debug("GET /api/matches/live — authenticated live matches request");
        List<Match> live = matchService.getLiveMatches();
        log.info("GET /api/matches/live — {} live match(es)", live.size());
        if (live.isEmpty()) {
            log.warn("GET /api/matches/live — no live matches found (poller may not have run yet)");
        } else {
            live.forEach(m -> log.debug("  live: externalId={} home='{}' away='{}' score={}-{} status={}",
                    m.getExternalId(), m.getHomeTeam(), m.getAwayTeam(),
                    m.getScoreHome(), m.getScoreAway(), m.getStatus()));
        }
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/matches/today")
    public ResponseEntity<ApiResponse<List<Match>>> todayMatches() {
        log.debug("GET /api/matches/today — authenticated today's matches request");
        List<Match> today = matchService.getTodayMatches();
        log.info("GET /api/matches/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> upcomingMatches() {
        log.debug("GET /api/matches/upcoming — authenticated upcoming matches request");
        List<Match> upcoming = matchService.getUpcomingMatches();
        log.info("GET /api/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/matches/future")
    public ResponseEntity<ApiResponse<List<Match>>> futureMatches() {
        log.debug("GET /api/matches/future — authenticated future matches request");
        List<Match> future = matchService.getFutureMatches();
        log.info("GET /api/matches/future — {} match(es) in next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(future));
    }

    @GetMapping("/api/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> matchDetail(@PathVariable String id) {
        log.debug("GET /api/matches/{} — fetching match", id);
        Match match = matchService.getById(id);
        log.info("GET /api/matches/{} — home='{}' away='{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchFullDetail(
            @PathVariable String id) {
        log.debug("GET /api/matches/{}/detail — fetching full external detail", id);
        Map<String, Object> detail = matchService.getMatchDetail(id);
        log.info("GET /api/matches/{}/detail — source='{}' keys={}",
                id, detail.get("source"), detail.keySet());
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping("/api/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchEvents(@PathVariable String id) {
        log.debug("GET /api/matches/{}/events — fetching match events", id);
        Map<String, Object> events = matchService.getEvents(id);
        log.info("GET /api/matches/{}/events — source='{}' keys={}", id, events.get("source"), events.keySet());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping("/api/matches/{id}/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchStats(@PathVariable String id) {
        log.debug("GET /api/matches/{}/stats — fetching match stats", id);
        Map<String, Object> stats = matchService.getStats(id);
        log.info("GET /api/matches/{}/stats — source='{}' type='{}'",
                id, stats.get("source"), stats.get("type"));
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/api/matches/{id}/lineups")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchLineups(@PathVariable String id) {
        log.debug("GET /api/matches/{}/lineups — fetching lineups", id);
        Map<String, Object> lineups = matchService.getLineups(id);
        log.info("GET /api/matches/{}/lineups — source='{}' empty={}",
                id, lineups.get("source"), lineups.isEmpty());
        return ResponseEntity.ok(ApiResponse.ok(lineups));
    }

    @GetMapping("/api/matches/{id}/h2h")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchH2H(@PathVariable String id) {
        log.debug("GET /api/matches/{}/h2h — fetching head-to-head data", id);
        Map<String, Object> h2h = matchService.getH2H(id);
        log.info("GET /api/matches/{}/h2h — source='{}' empty={}", id, h2h.get("source"), h2h.isEmpty());
        return ResponseEntity.ok(ApiResponse.ok(h2h));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Odds>>> matchOddsDb(@PathVariable String id) {
        log.debug("GET /api/matches/{}/odds — fetching DB odds", id);
        List<Odds> odds = matchService.getOddsForMatch(id);
        log.info("GET /api/matches/{}/odds — {} DB odds entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchOddsLive(
            @PathVariable String id) {
        log.debug("GET /api/matches/{}/odds/live — fetching live odds", id);
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        log.info("GET /api/matches/{}/odds/live — {} live odds entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchOddsAll(@PathVariable String id) {
        log.debug("GET /api/matches/{}/odds/all — fetching all markets bundle", id);
        Map<String, Object> odds = matchService.getAllOddsForMatch(id);
        log.info("GET /api/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/correct-score")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchCorrectScoreOdds(
            @PathVariable String id) {
        log.debug("GET /api/matches/{}/odds/correct-score — fetching correct score odds", id);
        List<Map<String, Object>> odds = matchService.getCorrectScoreOdds(id);
        log.info("GET /api/matches/{}/odds/correct-score — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/half-time")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchHalfTimeOdds(
            @PathVariable String id) {
        log.debug("GET /api/matches/{}/odds/half-time — fetching half-time odds", id);
        List<Map<String, Object>> odds = matchService.getHalfTimeOdds(id);
        log.info("GET /api/matches/{}/odds/half-time — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/handicap")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchHandicapOdds(
            @PathVariable String id) {
        log.debug("GET /api/matches/{}/odds/handicap — fetching Asian handicap odds", id);
        List<Map<String, Object>> odds = matchService.getHandicapOdds(id);
        log.info("GET /api/matches/{}/odds/handicap — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — PREDICTIONS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/{id}/prediction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchPrediction(@PathVariable String id) {
        log.debug("GET /api/matches/{}/prediction — fetching prediction", id);
        Map<String, Object> prediction = matchService.getPrediction(id);
        if (prediction != null && !prediction.isEmpty()) {
            log.info("GET /api/matches/{}/prediction — prediction found, source='{}'",
                    id, prediction.get("source"));
            return ResponseEntity.ok(ApiResponse.ok(prediction));
        }
        log.info("GET /api/matches/{}/prediction — no cached prediction, falling back to AI", id);
        Match match = matchService.getById(id);
        Map<String, Object> context = new HashMap<>();
        context.put("home_team", match.getHomeTeam());
        context.put("away_team", match.getAwayTeam());
        context.put("league",    match.getLeague());
        context.put("kickoff",   match.getKickoffAt());
        Map<String, Object> aiResult = mistralClient.predictMatch(context);
        log.info("GET /api/matches/{}/prediction — AI prediction returned {} key(s)",
                id, aiResult != null ? aiResult.size() : 0);
        return ResponseEntity.ok(ApiResponse.ok(aiResult));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDINGS / SCORERS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/standings/top6")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Object>>>> top6Standings() {
        log.info("GET /api/standings/top6 — fetching all top-6 standings from LiveScoreApi");
        Map<String, Map<String, Object>> standings = matchService.getAllTop6Standings();
        log.debug("GET /api/standings/top6 — {} league(s) returned", standings.size());
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    @GetMapping("/api/standings/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> standings(
            @PathVariable int competitionId) {
        log.info("GET /api/standings/{} — fetching standings from LiveScoreApi", competitionId);
        Map<String, Object> standings = matchService.getLiveScoreApiStandings(competitionId);
        log.debug("GET /api/standings/{} — {} key(s) in response", competitionId, standings.size());
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    @GetMapping("/api/scorers/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> topScorers(
            @PathVariable int competitionId) {
        log.info("GET /api/scorers/{} — fetching top scorers from LiveScoreApi", competitionId);
        Map<String, Object> scorers = matchService.getLiveScoreApiTopScorers(competitionId);
        log.debug("GET /api/scorers/{} — {} key(s) in response", competitionId, scorers.size());
        return ResponseEntity.ok(ApiResponse.ok(scorers));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — TEAM DATA
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/teams/{teamId}/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> teamMatches(@PathVariable int teamId) {
        log.debug("GET /api/teams/{}/matches — fetching last matches from LiveScoreApi", teamId);
        Map<String, Object> result = matchService.getLiveScoreApiTeamMatches(teamId);
        log.info("GET /api/teams/{}/matches — isEmpty={}", teamId, result.isEmpty());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — LIVESCORE API PASS-THROUGH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/livescore/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreLive() {
        log.debug("GET /api/livescore/live — fetching live scores from LiveScoreApi");
        List<Map<String, Object>> live = matchService.getLiveScoreApiLive();
        log.info("GET /api/livescore/live — {} match(es) returned", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/livescore/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreToday() {
        log.debug("GET /api/livescore/today — fetching today's matches from LiveScoreApi");
        List<Map<String, Object>> today = matchService.getLiveScoreApiToday();
        log.info("GET /api/livescore/today — {} match(es) returned", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/livescore/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreFixtures() {
        log.debug("GET /api/livescore/fixtures — fetching upcoming fixtures from LiveScoreApi");
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiFixtures();
        log.info("GET /api/livescore/fixtures — {} fixture(s) returned", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/public/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchOddsAll(
            @PathVariable String id) {
        log.debug("GET /api/public/matches/{}/odds/all — fetching all markets bundle (public)", id);
        Map<String, Object> odds = matchService.getAllOddsForMatch(id);
        log.info("GET /api/public/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }
}