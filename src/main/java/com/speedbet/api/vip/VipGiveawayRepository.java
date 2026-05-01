package com.speedbet.api.vip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VipGiveawayRepository extends JpaRepository<VipGiveaway, UUID> {
    List<VipGiveaway> findByWinnerUserIdNotNullOrderByDrawnAtDesc();
}
