package com.speedbet.api.vip;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vip_memberships")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VipMembership {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(name = "auto_renew")
    private boolean autoRenew = false;

    @Column(name = "price_paid", precision = 19, scale = 4)
    private BigDecimal pricePaid;

    @Builder.Default
    @Column
    private String currency = "GHS";

    @Builder.Default
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "activated_via")
    private String activatedVia;
}