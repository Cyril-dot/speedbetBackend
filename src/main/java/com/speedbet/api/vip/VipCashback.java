package com.speedbet.api.vip;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vip_cashbacks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VipCashback {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bet_id", unique = true, nullable = false)
    private UUID betId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
