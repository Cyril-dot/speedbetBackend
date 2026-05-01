package com.speedbet.api.vip;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "vip_gifts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VipGift {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    @Builder.Default
    @Column(name = "issued_at")
    private Instant issuedAt = Instant.now();

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}