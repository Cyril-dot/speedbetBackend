package com.speedbet.api.payment.paystack;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import jakarta.servlet.http.HttpServletRequest;
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

        var amountPesewas = amount.multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64).intValue();

        @SuppressWarnings("unchecked")
        var response = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/transaction/initialize")
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "email", user.getEmail(),
                        "amount", amountPesewas,
                        "currency", "GHS",
                        "callback_url", frontendUrl + "/app/wallet?payment=success",
                        "metadata", Map.of("userId", user.getId().toString())
                ))
                .retrieve().bodyToMono(Map.class)
                .onErrorReturn(Map.of("status", false, "message", "Paystack unavailable"))
                .block();

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/api/webhooks/paystack")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        // Read raw body bytes to ensure signature verification uses exact bytes Paystack sent
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Paystack webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Paystack webhook: missing x-paystack-signature header");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("Paystack webhook: invalid signature received");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            var payload = new String(rawBody, StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(payload, Map.class);
            var eventType = event.get("event").toString();

            log.info("Paystack webhook received: event={}", eventType);

            if ("charge.success".equals(eventType)) {

                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) event.get("data");

                @SuppressWarnings("unchecked")
                var metadata = (Map<String, Object>) data.get("metadata");

                if (metadata == null || metadata.get("userId") == null) {
                    log.error("Paystack webhook: missing userId in metadata, ref={}", data.get("reference"));
                    return ResponseEntity.status(400).body("Missing userId in metadata");
                }

                var userId = UUID.fromString(metadata.get("userId").toString());
                var ref = data.get("reference").toString();
                var amountPesewas = Long.parseLong(data.get("amount").toString());
                var amount = BigDecimal.valueOf(amountPesewas).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

                log.info("Paystack webhook: processing deposit GHS {} for userId={} ref={}", amount, userId, ref);

                try {
                    walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                            Map.of("provider", "paystack", "reference", ref));
                    log.info("Paystack webhook: deposit GHS {} credited to userId={} ref={}", amount, userId, ref);
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        log.warn("Paystack webhook: duplicate ref={} already processed — skipping", ref);
                        return ResponseEntity.ok("Already processed");
                    }
                    throw ex;
                }

            } else {
                log.info("Paystack webhook: ignoring event={}", eventType);
            }

        } catch (ApiException e) {
            log.error("Paystack webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Paystack webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // Verify using raw bytes — avoids any charset/encoding mismatch
    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var hash = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return hash.equals(signature);
        } catch (Exception e) {
            log.error("Paystack webhook: signature verification error", e);
            return false;
        }
    }
}