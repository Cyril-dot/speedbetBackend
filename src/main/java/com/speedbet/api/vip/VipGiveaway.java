package com.speedbet.api.vip;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vip_giveaways")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VipGiveaway {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "week_start", nullable = false)
    private Instant weekStart;

    @Column(name = "prize_label", nullable = false)
    private String prizeLabel;

    @Column(name = "prize_amount", precision = 19, scale = 4)
    private BigDecimal prizeAmount;

    private String kind;

    @Column(name = "winner_user_id")
    private UUID winnerUserId;

    @Column(name = "drawn_at")
    private Instant drawnAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;
}
