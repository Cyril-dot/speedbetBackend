package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AffiliateController {

    private final AffiliateService affiliateService;

    @PostMapping("/payout-request")
    public ResponseEntity<ApiResponse<PayoutRequest>> request(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(affiliateService.requestPayout(user.getId())));
    }

    @GetMapping("/payout-requests")
    public ResponseEntity<ApiResponse<PageResponse<PayoutRequest>>> history(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(affiliateService.getForAdmin(user.getId(), PageRequest.of(page, size)))));
    }

    @GetMapping("/payout-window")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> payoutWindow() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("open", affiliateService.isPayoutWindowOpen())));
    }
}