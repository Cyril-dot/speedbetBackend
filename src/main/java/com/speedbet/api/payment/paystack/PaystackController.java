package com.speedbet.api.payment.paystack;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaystackController {

    private final WalletService walletService;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.paystack.secret-key}") private String secretKey;
    @Value("${app.paystack.base-url}") private String baseUrl;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}") private String frontendUrl;

    @PostMapping("/api/wallet/deposit/paystack/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        // Amount in pesewas (kobo equivalent)
        var amountKobo = amount.multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64).intValue();

        @SuppressWarnings("unchecked")
        var response = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/transaction/initialize")
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "email", user.getEmail(),
                        "amount", amountKobo,
                        "currency", "GHS",
                        "callback_url", frontendUrl + "/app/wallet",
                        "metadata", Map.of("userId", user.getId().toString())
                ))
                .retrieve().bodyToMono(Map.class)
                .onErrorReturn(Map.of("status", false, "message", "Paystack unavailable"))
                .block();

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/api/webhooks/paystack")
    public ResponseEntity<String> webhook(
            @RequestHeader("x-paystack-signature") String signature,
            @RequestBody String payload) {

        if (!verifySignature(payload, signature)) {
            log.warn("Invalid Paystack webhook signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            var event = new com.fasterxml.jackson.databind.ObjectMapper().readValue(payload, Map.class);
            var eventType = event.get("event").toString();

            if ("charge.success".equals(eventType)) {
                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) event.get("data");
                @SuppressWarnings("unchecked")
                var metadata = (Map<String, Object>) data.get("metadata");
                var userId = UUID.fromString(metadata.get("userId").toString());
                var amountKobo = Long.parseLong(data.get("amount").toString());
                var amount = BigDecimal.valueOf(amountKobo).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
                var ref = data.get("reference").toString();

                walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                        Map.of("provider", "paystack", "reference", ref));
                log.info("Paystack deposit GHS {} for user {}", amount, userId);
            }
        } catch (Exception e) {
            log.error("Paystack webhook processing error", e);
        }
        return ResponseEntity.ok("OK");
    }

    private boolean verifySignature(String payload, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var hash = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return hash.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }
}