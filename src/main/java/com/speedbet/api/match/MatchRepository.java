package com.speedbet.api.match;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchRepository extends JpaRepository<Match, UUID> {

    // ── Existing methods (unchanged signatures) ──────────────────────────

    List<Match> findByStatusOrderByKickoffAt(String status);
    List<Match> findByFeaturedTrueOrderByKickoffAt();
    List<Match> findByStatusIn(List<String> statuses);

    @Query("SELECT m FROM Match m WHERE m.kickoffAt BETWEEN :from AND :to ORDER BY m.kickoffAt")
    List<Match> findUpcoming(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT m FROM Match m WHERE m.status = 'FINISHED' AND m.settledAt IS NULL")
    List<Match> findUnsettledFinished();

    Optional<Match> findByExternalId(String externalId);

    Page<Match> findByLeagueContainingIgnoreCaseOrHomeTeamContainingIgnoreCaseOrAwayTeamContainingIgnoreCase(
            String league, String home, String away, Pageable pageable);

    // ── New methods for live/today/future wiring ─────────────────────────

    /**
     * Any match (regardless of status) kicking off within [from, to).
     * Used by MatchService.getTodayMatches.
     */
    @Query("""
            SELECT m FROM Match m
             WHERE m.kickoffAt >= :from
               AND m.kickoffAt <  :to
             ORDER BY m.kickoffAt ASC
            """)
    List<Match> findByKickoffBetween(@Param("from") Instant from,
                                     @Param("to")   Instant to);

    /**
     * Future matches (status = UPCOMING) within [from, to), kickoff-ordered.
     * Used by MatchService.getFutureMatches.
     */
    @Query("""
            SELECT m FROM Match m
             WHERE m.status = 'UPCOMING'
               AND m.kickoffAt >= :from
               AND m.kickoffAt <  :to
             ORDER BY m.kickoffAt ASC
            """)
    List<Match> findUpcomingScheduled(@Param("from") Instant from,
                                      @Param("to")   Instant to);

    /**
     * LIVE matches whose kickoff is older than the given cutoff.
     * Used by the stale-live sweep job.
     */
    @Query("""
            SELECT m FROM Match m
             WHERE m.status = 'LIVE'
               AND m.kickoffAt IS NOT NULL
               AND m.kickoffAt < :cutoff
            """)
    List<Match> findStaleLive(@Param("cutoff") Instant cutoff);
}