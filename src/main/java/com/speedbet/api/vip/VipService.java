package com.speedbet.api.vip;

import com.speedbet.api.bet.Bet;
import com.speedbet.api.bet.BetSelection;
import com.speedbet.api.bet.BetStatus;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * VipService
 *
 * Cashback eligibility — updated for multi-market settlement:
 *
 *   A VIP cashback is awarded when a bet is LOST and all of the following hold:
 *     1. The user held an active VIP membership when the bet was placed.
 *     2. The bet has ≥ cashbackMinSelections legs.
 *     3. The stake is ≥ cashbackMinStake.
 *     4. No cashback has been issued for this bet already.
 *     5. Exactly ONE leg is responsible for the loss, counting:
 *          • a LOST leg                = 1 full loss
 *          • a HALF_LOST leg           = 0.5 loss  (quarter Asian Handicap)
 *        A sum of 0.5 does NOT qualify — it must reach exactly 1.0.
 *        This means two HALF_LOST legs together also do not qualify (sum = 1.0
 *        across two legs, but neither is the sole culprit).
 *     6. Weekly cashback cap not already reached.
 *
 *   Cashback is also checked for VOID bets (all legs pushed) — they are NOT
 *   eligible because the stake is already returned.  Only LOST bets qualify.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipService {

    @Value("${app.vip.membership-price:250}")        private BigDecimal membershipPrice;
    @Value("${app.vip.membership-days:30}")           private int        membershipDays;
    @Value("${app.vip.cashback-min-selections:10}")   private int        cashbackMinSelections;
    @Value("${app.vip.cashback-min-stake:10}")        private BigDecimal cashbackMinStake;
    @Value("${app.vip.cashback-weekly-cap:1000}")     private BigDecimal cashbackWeeklyCap;

    private final VipMembershipRepository membershipRepo;
    private final VipCashbackRepository   cashbackRepo;
    private final VipGiftRepository       giftRepo;
    private final VipGiveawayRepository   giveawayRepo;
    private final WalletService           walletService;

    // ── Membership queries ────────────────────────────────────────────────

    public boolean isActiveVip(UUID userId) {
        return membershipRepo.existsByUserIdAndStatus(userId, "ACTIVE");
    }

    public VipStatusDto getStatus(UUID userId) {
        return membershipRepo.findByUserIdAndStatus(userId, "ACTIVE")
                .map(m -> new VipStatusDto(true, m.getExpiresAt(), m.isAutoRenew(),
                        ChronoUnit.DAYS.between(Instant.now(), m.getExpiresAt())))
                .orElse(new VipStatusDto(false, null, false, 0));
    }

    // ── Membership management ─────────────────────────────────────────────

    @Transactional
    public VipMembership subscribe(UUID userId, boolean autoRenew) {
        if (isActiveVip(userId)) throw ApiException.conflict("Already a VIP member");

        walletService.debit(userId, membershipPrice, TxKind.VIP_MEMBERSHIP,
                null, Map.of("type", "vip_subscription"));

        var now = Instant.now();
        return membershipRepo.save(VipMembership.builder()
                .userId(userId)
                .startedAt(now)
                .expiresAt(now.plus(membershipDays, ChronoUnit.DAYS))
                .autoRenew(autoRenew)
                .pricePaid(membershipPrice)
                .currency("GHS")
                .status("ACTIVE")
                .activatedVia("WALLET")
                .build());
    }

    @Transactional
    public VipMembership grantVip(UUID userId, int days, UUID grantedByAdminId) {
        membershipRepo.findByUserIdAndStatus(userId, "ACTIVE")
                .ifPresent(m -> { m.setStatus("SUPERSEDED"); membershipRepo.save(m); });
        var now = Instant.now();
        return membershipRepo.save(VipMembership.builder()
                .userId(userId)
                .startedAt(now)
                .expiresAt(now.plus(days, ChronoUnit.DAYS))
                .status("ACTIVE")
                .activatedVia("ADMIN_GRANT")
                .build());
    }

    // ── VIP Cashback ──────────────────────────────────────────────────────

    /**
     * Called by SettlementEngine after every bet is settled.
     *
     * Eligibility gate:
     *   • Only LOST bets qualify. VOID (all-push) bets do not — the stake
     *     is already returned. WON bets obviously don't need cashback.
     *   • Exactly 1.0 "loss units" across all legs (see lossUnits() below).
     */
    @Transactional
    public void checkCashback(Bet bet) {
        if (bet.getStatus() != BetStatus.LOST) return;
        if (!isActiveVipAtTime(bet.getUserId(), bet.getPlacedAt())) return;
        if (bet.getSelections().size() < cashbackMinSelections) return;
        if (bet.getStake().compareTo(cashbackMinStake) < 0) return;
        if (cashbackRepo.existsByBetId(bet.getId())) return;

        // Count loss units across all legs
        //   LOST      = 1.0
        //   HALF_LOST = 0.5   (quarter Asian Handicap leg)
        //   anything else = 0 (WON, VOID, PUSH, HALF_WON)
        double totalLossUnits = bet.getSelections().stream()
                .mapToDouble(VipService::lossUnits)
                .sum();

        // Exactly one full "culprit" leg required
        if (totalLossUnits != 1.0) return;

        // Weekly cap
        var weekStart    = Instant.now().minus(7, ChronoUnit.DAYS);
        var weeklyUsed   = cashbackRepo.sumByUserSince(bet.getUserId(), weekStart);
        if (weeklyUsed.compareTo(cashbackWeeklyCap) >= 0) return;

        var cashbackAmount = bet.getStake().min(cashbackWeeklyCap.subtract(weeklyUsed));

        cashbackRepo.save(VipCashback.builder()
                .betId(bet.getId())
                .userId(bet.getUserId())
                .amount(cashbackAmount)
                .build());

        walletService.credit(bet.getUserId(), cashbackAmount, TxKind.VIP_CASHBACK,
                "CASHBACK-" + bet.getId(),
                Map.of("betId", bet.getId().toString(), "type", "vip_cashback"));

        log.info("VIP cashback GHS {} credited to user {} for bet {}",
                cashbackAmount, bet.getUserId(), bet.getId());
    }

    /**
     * Maps a settled selection result to its loss-unit weight:
     *   LOST      → 1.0  (full loss leg)
     *   HALF_LOST → 0.5  (quarter-handicap partial loss)
     *   anything else → 0.0
     */
    private static double lossUnits(BetSelection sel) {
        return switch (sel.getResult()) {
            case "LOST"      -> 1.0;
            case "HALF_LOST" -> 0.5;
            default          -> 0.0;
        };
    }

    // ── Gifts ─────────────────────────────────────────────────────────────

    public List<VipGift> getGifts(UUID userId) {
        return giftRepo.findByUserIdOrderByIssuedAtDesc(userId);
    }

    @Transactional
    public VipGift consumeGift(UUID userId, UUID giftId) {
        var gift = giftRepo.findById(giftId)
                .orElseThrow(() -> ApiException.notFound("Gift not found"));
        if (!gift.getUserId().equals(userId)) throw ApiException.forbidden("Not your gift");
        if (gift.getConsumedAt() != null) throw ApiException.badRequest("Gift already consumed");
        gift.setConsumedAt(Instant.now());
        return giftRepo.save(gift);
    }

    // ── Scheduled jobs ────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 900_000) // every 15 min
    @Transactional
    public void expireVipMemberships() {
        var expired = membershipRepo.findExpired(Instant.now());
        for (var m : expired) {
            if (m.isAutoRenew()) {
                try {
                    walletService.debit(m.getUserId(), membershipPrice, TxKind.VIP_MEMBERSHIP,
                            null, Map.of("type", "vip_auto_renew"));
                    m.setStartedAt(Instant.now());
                    m.setExpiresAt(Instant.now().plus(membershipDays, ChronoUnit.DAYS));
                    membershipRepo.save(m);
                    log.info("VIP auto-renewed for user {}", m.getUserId());
                } catch (Exception e) {
                    log.warn("VIP auto-renew failed for user {}: {}", m.getUserId(), e.getMessage());
                    m.setStatus("EXPIRED");
                    membershipRepo.save(m);
                }
            } else {
                m.setStatus("EXPIRED");
                membershipRepo.save(m);
            }
        }
    }

    // Monday 08:00 UTC — weekly gift drop
    @Scheduled(cron = "0 0 8 * * MON")
    @Transactional
    public void weeklyGiftDrop() {
        var activeVips = membershipRepo.findAllActive();
        var gifts = List.of("FREE_BET", "BOOSTED_ODDS", "DEPOSIT_BONUS", "CASHBACK_CREDIT", "ENTRY_TICKET");
        var rand = new Random();
        for (var m : activeVips) {
            var kind = gifts.get(rand.nextInt(gifts.size()));
            giftRepo.save(VipGift.builder()
                    .userId(m.getUserId())
                    .kind(kind)
                    .payload(Map.of("value", "10.00", "currency", "GHS"))
                    .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                    .build());
        }
        log.info("Weekly gifts issued to {} VIP members", activeVips.size());
    }

    // Sunday 20:00 UTC — giveaway draw
    @Scheduled(cron = "0 0 20 * * SUN")
    @Transactional
    public void weeklyGiveawayDraw() {
        var activeVips = membershipRepo.findAllActive();
        if (activeVips.isEmpty()) return;
        var winner = activeVips.get(new Random().nextInt(activeVips.size()));
        var giveaway = VipGiveaway.builder()
                .weekStart(Instant.now().minus(7, ChronoUnit.DAYS))
                .prizeLabel("Weekly VIP Giveaway")
                .prizeAmount(new BigDecimal("500.00"))
                .winnerUserId(winner.getUserId())
                .drawnAt(Instant.now())
                .build();
        giveawayRepo.save(giveaway);
        walletService.credit(winner.getUserId(), new BigDecimal("500.00"), TxKind.ADJUSTMENT,
                "GIVEAWAY-" + giveaway.getId(),
                Map.of("type", "giveaway_prize"));
        log.info("Giveaway winner: user {}", winner.getUserId());
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private boolean isActiveVipAtTime(UUID userId, Instant atTime) {
        return membershipRepo.findByUserIdAndStatus(userId, "ACTIVE")
                .map(m -> !m.getStartedAt().isAfter(atTime))
                .orElse(false);
    }

    public record VipStatusDto(
            boolean isActive,
            Instant expiresAt,
            boolean autoRenew,
            long    daysRemaining
    ) {}
}