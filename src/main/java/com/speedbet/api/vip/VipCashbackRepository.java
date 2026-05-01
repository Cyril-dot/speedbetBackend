package com.speedbet.api.vip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface VipCashbackRepository extends JpaRepository<VipCashback, UUID> {
    boolean existsByBetId(UUID betId);

    @Query("SELECT COALESCE(SUM(v.amount),0) FROM VipCashback v WHERE v.userId = :userId AND v.createdAt >= :since")
    BigDecimal sumByUserSince(UUID userId, Instant since);
}
