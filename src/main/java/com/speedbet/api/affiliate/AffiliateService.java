package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

interface PayoutRequestRepository extends JpaRepository<PayoutRequest, UUID> {

    Page<PayoutRequest> findByAdminIdOrderByCreatedAtDesc(UUID adminId, Pageable pageable);

    @Query(value = "SELECT * FROM payout_requests WHERE status = CAST(:status AS payout_status)",
            nativeQuery = true)
    List<PayoutRequest> findByStatus(@Param("status") String status);

    @Query(value = "SELECT COALESCE(SUM(p.amount), 0) FROM payout_requests p " +
            "WHERE p.admin_id = :adminId AND p.status = CAST('PAID' AS payout_status)",
            nativeQuery = true)
    BigDecimal totalPaidByAdmin(@Param("adminId") UUID adminId);
}

@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateService {

    private final PayoutRequestRepository payoutRepo;
    private final WalletService walletService;

    private boolean payoutWindowOpen = false;

    @Scheduled(cron = "0 0 0 * * FRI")
    public void openPayoutWindow() {
        payoutWindowOpen = true;
        log.info("Friday payout window opened");
    }

    @Transactional
    public PayoutRequest requestPayout(UUID adminId) {
        if (!payoutWindowOpen)
            throw ApiException.badRequest("Payout requests are only available on Fridays");

        var wallet = walletService.getWallet(adminId);
        var commissionBalance = wallet.getBalance();
        if (commissionBalance.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("No balance available for payout");

        var now = Instant.now();
        return payoutRepo.save(PayoutRequest.builder()
                .adminId(adminId)
                .amount(commissionBalance)
                .periodEnd(now)
                .status("REQUESTED")
                .build());
    }

    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PayoutRequest approve(UUID payoutId) {
        var payout = getById(payoutId);
        if (!"REQUESTED".equals(payout.getStatus()))
            throw ApiException.badRequest("Payout is not in REQUESTED status");
        payout.setStatus("APPROVED");
        return payoutRepo.save(payout);
    }

    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PayoutRequest reject(UUID payoutId, String reason) {
        var payout = getById(payoutId);
        payout.setStatus("REJECTED");
        payout.setRejectReason(reason);
        return payoutRepo.save(payout);
    }

    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PayoutRequest markPaid(UUID payoutId) {
        var payout = getById(payoutId);
        payout.setStatus("PAID");
        payout.setPaidAt(Instant.now());
        walletService.debit(payout.getAdminId(), payout.getAmount(), TxKind.PAYOUT,
                "PAYOUT-" + payoutId, Map.of("payoutId", payoutId.toString()));
        return payoutRepo.save(payout);
    }

    public Page<PayoutRequest> getForAdmin(UUID adminId, Pageable pageable) {
        return payoutRepo.findByAdminIdOrderByCreatedAtDesc(adminId, pageable);
    }

    public List<PayoutRequest> getPendingForSuperAdmin() {
        return payoutRepo.findByStatus("REQUESTED");
    }

    public boolean isPayoutWindowOpen() { return payoutWindowOpen; }

    private PayoutRequest getById(UUID id) {
        return payoutRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Payout not found"));
    }
}