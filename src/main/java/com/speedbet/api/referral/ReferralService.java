package com.speedbet.api.referral;

import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReferralLinkRepository linkRepo;
    private final ReferralRepository referralRepo;
    private final WalletService walletService;

    public Optional<UUID> findLinkIdByCode(String code) {
        Optional<UUID> result = linkRepo.findByCodeAndActiveTrue(code).map(ReferralLink::getId);
        log.info("findLinkIdByCode: code={} found={}", code, result.isPresent());
        return result;
    }

    @Transactional
    public void attributeUser(UUID linkId, UUID userId) {
        if (referralRepo.findByUserId(userId).isPresent()) {
            log.info("attributeUser: userId={} already attributed, skipping", userId);
            return;
        }
        referralRepo.save(Referral.builder().linkId(linkId).userId(userId).build());
        log.info("attributeUser: userId={} attributed to linkId={}", userId, linkId);
    }

    @Transactional
    public void attributeCommission(UUID userId, BigDecimal stake) {
        log.info("attributeCommission: userId={} stake={}", userId, stake);
        referralRepo.findByUserId(userId).ifPresent(referral -> {
            linkRepo.findById(referral.getLinkId()).ifPresent(link -> {
                var commission = stake.multiply(
                        link.getCommissionPercent().divide(BigDecimal.valueOf(100), MathContext.DECIMAL64));
                referral.setLifetimeStake(referral.getLifetimeStake().add(stake));
                referral.setLifetimeCommission(referral.getLifetimeCommission().add(commission));
                referralRepo.save(referral);

                walletService.credit(link.getAdminId(), commission,
                        TxKind.REFERRAL_COMMISSION, "REF-" + userId + "-" + System.currentTimeMillis(),
                        Map.of("userId", userId.toString(), "stake", stake.toString()));
                log.info("attributeCommission: GHS {} credited to adminId={} for userId={}", commission, link.getAdminId(), userId);
            });
        });
    }

    @Transactional
    public ReferralLink createLink(UUID adminId, String label, BigDecimal commissionPercent, Instant expiresAt) {
        log.info("createLink: adminId={} label='{}' commission={}% expiresAt={}", adminId, label, commissionPercent, expiresAt);
        ReferralLink link = linkRepo.save(ReferralLink.builder()
                .adminId(adminId)
                .code(generateUniqueCode())
                .label(label)
                .commissionPercent(commissionPercent != null ? commissionPercent : BigDecimal.TEN)
                .expiresAt(expiresAt)
                .build());
        log.info("createLink: success — code={} id={}", link.getCode(), link.getId());
        return link;
    }

    public List<ReferralLink> getLinksForAdmin(UUID adminId) {
        List<ReferralLink> links = linkRepo.findByAdminId(adminId);
        log.info("getLinksForAdmin: adminId={} found={}", adminId, links.size());
        return links;
    }

    public List<Referral> getReferralsForAdmin(UUID adminId) {
        List<Referral> referrals = referralRepo.findByAdminId(adminId);
        log.info("getReferralsForAdmin: adminId={} found={}", adminId, referrals.size());
        return referrals;
    }

    private String generateUniqueCode() {
        String code;
        int attempts = 0;
        do {
            code = generateCode(8);
            attempts++;
        } while (linkRepo.findByCode(code).isPresent());
        log.debug("generateUniqueCode: code={} attempts={}", code, attempts);
        return code;
    }

    private String generateCode(int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
        return sb.toString();
    }
}