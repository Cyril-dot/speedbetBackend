package com.speedbet.api.superadmin;

import com.speedbet.api.affiliate.AffiliateService;
import com.speedbet.api.affiliate.PayoutRequest;
import com.speedbet.api.audit.AuditLog;
import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.user.UserService;
import com.speedbet.api.vip.VipMembership;
import com.speedbet.api.vip.VipService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminController {

    private final UserRepository userRepo;
    private final UserService userService;
    private final VipService vipService;
    private final AuditService auditService;
    private final AffiliateService affiliateService;

    // ── Admins ──────────────────────────────────────────────

    @GetMapping("/admins")
    public ResponseEntity<ApiResponse<List<User>>> admins() {
        var admins = userRepo.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(admins));
    }

    @PostMapping("/admins")
    public ResponseEntity<ApiResponse<User>> createAdmin(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {
        var admin = userService.createAdmin(
                req.get("email"), req.get("password"),
                req.get("firstName"), req.get("lastName"),
                actor.getId());
        auditService.log(actor.getId(), "CREATE_ADMIN", "users", admin.getId(),
                null, Map.of("email", admin.getEmail()), null);
        return ResponseEntity.ok(ApiResponse.ok(admin, "Admin created"));
    }

    // ── Metrics ─────────────────────────────────────────────

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> metrics() {
        long totalUsers = userRepo.count();
        long totalAdmins = userRepo.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN).count();
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "totalUsers", totalUsers,
                "totalAdmins", totalAdmins,
                "platform", "SpeedBet"
        )));
    }

    // ── Audit Log ────────────────────────────────────────────

    @GetMapping("/audit-log")
    public ResponseEntity<ApiResponse<PageResponse<AuditLog>>> auditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(auditService.getAll(PageRequest.of(page, size)))));
    }

    // ── Payout Requests ──────────────────────────────────────

    @GetMapping("/payout-requests")
    public ResponseEntity<ApiResponse<List<PayoutRequest>>> payoutRequests() {
        return ResponseEntity.ok(ApiResponse.ok(affiliateService.getPendingForSuperAdmin()));
    }

    @PostMapping("/payout-requests/{id}/approve")
    public ResponseEntity<ApiResponse<PayoutRequest>> approvePayout(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(affiliateService.approve(id)));
    }

    @PostMapping("/payout-requests/{id}/reject")
    public ResponseEntity<ApiResponse<PayoutRequest>> rejectPayout(
            @PathVariable UUID id,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(ApiResponse.ok(affiliateService.reject(id, req.get("reason"))));
    }

    @PostMapping("/payout-requests/{id}/mark-paid")
    public ResponseEntity<ApiResponse<PayoutRequest>> markPaid(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(affiliateService.markPaid(id)));
    }

    // ── VIP ──────────────────────────────────────────────────

    @PostMapping("/vip/grant")
    public ResponseEntity<ApiResponse<VipMembership>> grantVip(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, Object> req) {
        var userId = UUID.fromString(req.get("userId").toString());
        var days = Integer.parseInt(req.getOrDefault("days", "30").toString());
        return ResponseEntity.ok(ApiResponse.ok(vipService.grantVip(userId, days, actor.getId())));
    }
}