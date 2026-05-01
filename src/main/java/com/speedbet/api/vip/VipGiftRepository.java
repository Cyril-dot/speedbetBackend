package com.speedbet.api.vip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VipGiftRepository extends JpaRepository<VipGift, UUID> {
    List<VipGift> findByUserIdOrderByIssuedAtDesc(UUID userId);
    List<VipGift> findByUserIdAndConsumedAtIsNull(UUID userId);
}
