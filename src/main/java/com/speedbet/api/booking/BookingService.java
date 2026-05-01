package com.speedbet.api.booking;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.MatchRepository;
import com.speedbet.api.odds.OddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingCodeRepository bookingRepo;
    private final MatchRepository matchRepo;
    private final OddsRepository oddsRepo;

    @Transactional
    public BookingCode create(CreateBookingRequest req, UUID adminId) {
        log.info("createBookingCode: adminId={} label='{}' kind={} stake={} selections={}",
                adminId, req.label(), req.kind(), req.stake(), req.selections().size());

        validateSelections(req.selections());

        BigDecimal totalOdds = req.selections().stream()
                .map(s -> new BigDecimal(s.get("odds").toString()))
                .reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, MathContext.DECIMAL64));

        BigDecimal potentialPayout = req.stake() != null
                ? req.stake().multiply(totalOdds, MathContext.DECIMAL64)
                : null;

        String currency = (req.currency() != null && !req.currency().isBlank())
                ? req.currency() : "GHS";

        String kind = (req.kind() != null && !req.kind().isBlank())
                ? req.kind().toUpperCase()
                : deriveKind(req.selections());

        BookingCode saved = bookingRepo.save(BookingCode.builder()
                .code(generateUniqueCode())
                .creatorAdminId(adminId)
                .label(req.label())
                .kind(kind)
                .currency(currency)
                .stake(req.stake())
                .selections(req.selections())
                .totalOdds(totalOdds)
                .potentialPayout(potentialPayout)
                .maxRedemptions(req.maxRedemptions())
                .expiresAt(req.expiresAt())
                .build());

        log.info("createBookingCode: success — code={} id={} kind={} totalOdds={} payout={}",
                saved.getCode(), saved.getId(), saved.getKind(), totalOdds, potentialPayout);

        return saved;
    }

    @Transactional
    public RedeemResponse redeem(String code) {
        log.info("redeemBookingCode: code={}", code);

        var booking = bookingRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Booking code not found: " + code));

        if (!"active".equals(booking.getStatus()))
            throw ApiException.badRequest("Booking code is " + booking.getStatus());
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(Instant.now()))
            throw ApiException.badRequest("Booking code has expired");
        if (booking.getMaxRedemptions() != null &&
                booking.getRedemptionCount() >= booking.getMaxRedemptions())
            throw ApiException.badRequest("Booking code has reached max redemptions");

        booking.setRedemptionCount(booking.getRedemptionCount() + 1);
        bookingRepo.save(booking);

        log.info("redeemBookingCode: code={} redemptionCount={}", code, booking.getRedemptionCount());

        var enrichedSelections = booking.getSelections().stream().map(sel -> {
            var enriched = new HashMap<>(sel);
            // FIX: guard against null fixture_id / market / pick before enriching
            Object fixtureIdRaw = sel.get("fixture_id");
            Object marketRaw    = sel.get("market");
            Object pickRaw      = sel.get("pick");

            if (fixtureIdRaw == null || marketRaw == null || pickRaw == null) {
                log.warn("redeemBookingCode: selection missing required field(s) — skipping enrichment for sel={}",
                        sel);
                return (Map<String, Object>) enriched;
            }

            try {
                UUID matchId = UUID.fromString(fixtureIdRaw.toString());
                matchRepo.findById(matchId).ifPresent(m -> {
                    enriched.put("homeTeam", m.getHomeTeam());
                    enriched.put("awayTeam", m.getAwayTeam());
                    enriched.put("league", m.getLeague());
                    oddsRepo.findByMatchIdAndMarketAndSelection(
                            matchId,
                            marketRaw.toString(),
                            pickRaw.toString()
                    ).ifPresent(o -> enriched.put("odds", o.getValue()));
                });
            } catch (Exception e) {
                log.warn("redeemBookingCode: failed to enrich selection fixture_id={} — {}",
                        fixtureIdRaw, e.getMessage());
            }
            return (Map<String, Object>) enriched;
        }).toList();

        BigDecimal currentTotalOdds = enrichedSelections.stream()
                .map(s -> new BigDecimal(s.get("odds").toString()))
                .reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, MathContext.DECIMAL64));

        return new RedeemResponse(booking, enrichedSelections, currentTotalOdds);
    }

    public Page<BookingCode> getForAdmin(UUID adminId, Pageable pageable) {
        log.info("getBookingCodesForAdmin: adminId={}", adminId);
        return bookingRepo.findByCreatorAdminIdOrderByCreatedAtDesc(adminId, pageable);
    }

    public BookingCode getDetail(UUID id, UUID adminId) {
        log.info("getBookingCodeDetail: id={} adminId={}", id, adminId);
        var code = bookingRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Booking code not found"));
        if (!code.getCreatorAdminId().equals(adminId))
            throw ApiException.forbidden("Not your booking code");
        return code;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String deriveKind(List<Map<String, Object>> selections) {
        if (selections == null || selections.isEmpty()) return "MIXED";
        var markets = selections.stream()
                .map(s -> s.get("market") == null ? "" : s.get("market").toString().toUpperCase())
                .distinct()
                .toList();
        return markets.size() == 1 ? markets.get(0) : "MIXED";
    }

    /**
     * FIX: per-selection field validation now runs BEFORE the duplicate fixture_id
     * check so we never call .toString() on a potentially null fixture_id.
     *
     * Order:
     *   1. Null / size guards
     *   2. Per-selection field presence + odds floor  ← moved up
     *   3. Duplicate fixture_id check                 ← moved down (safe now)
     */
    private void validateSelections(List<Map<String, Object>> selections) {
        if (selections == null || selections.isEmpty())
            throw ApiException.badRequest("At least one selection required");
        if (selections.size() > 20)
            throw ApiException.badRequest("Max 20 selections");

        // FIX: validate each selection's required fields FIRST
        for (var sel : selections) {
            if (sel.get("fixture_id") == null)
                throw ApiException.badRequest("Selection missing fixture_id");
            if (sel.get("odds") == null)
                throw ApiException.badRequest("Selection missing odds");
            if (sel.get("market") == null)
                throw ApiException.badRequest("Selection missing market");
            if (sel.get("pick") == null)
                throw ApiException.badRequest("Selection missing pick");

            var odds = new BigDecimal(sel.get("odds").toString());
            if (odds.compareTo(new BigDecimal("1.10")) < 0)
                throw ApiException.badRequest("Minimum odds per selection is 1.10");
        }

        // Safe to call .toString() now — fixture_id is guaranteed non-null above
        var fixtureIds = selections.stream()
                .map(s -> s.get("fixture_id").toString())
                .toList();
        if (fixtureIds.size() != fixtureIds.stream().distinct().count())
            throw ApiException.badRequest("Duplicate fixtures in booking code");
    }

    private String generateUniqueCode() {
        String code;
        int attempts = 0;
        do {
            code = generateCode(8);
            attempts++;
        } while (bookingRepo.existsByCode(code));
        log.debug("generateUniqueCode: code={} attempts={}", code, attempts);
        return code;
    }

    private String generateCode(int len) {
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
        return sb.toString();
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    public record CreateBookingRequest(
            String kind,
            String label,
            String currency,
            BigDecimal stake,
            List<Map<String, Object>> selections,
            Integer maxRedemptions,
            Instant expiresAt
    ) {}

    public record RedeemResponse(
            BookingCode booking,
            List<Map<String, Object>> enrichedSelections,
            BigDecimal currentTotalOdds
    ) {}
}