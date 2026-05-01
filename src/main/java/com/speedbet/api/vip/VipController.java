package com.speedbet.api.vip;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/vip")
@RequiredArgsConstructor
public class VipController {

    private final VipService vipService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<VipService.VipStatusDto>> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(vipService.getStatus(user.getId())));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<VipMembership>> subscribe(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {
        boolean autoRenew = req != null && Boolean.TRUE.equals(req.get("autoRenew"));
        return ResponseEntity.ok(ApiResponse.ok(vipService.subscribe(user.getId(), autoRenew),
            "VIP membership activated! Welcome to the club."));
    }

    @GetMapping("/gifts")
    public ResponseEntity<ApiResponse<List<VipGift>>> gifts(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(vipService.getGifts(user.getId())));
    }

    @PostMapping("/gifts/{id}/consume")
    public ResponseEntity<ApiResponse<VipGift>> consume(
            @AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(vipService.consumeGift(user.getId(), id)));
    }
}
