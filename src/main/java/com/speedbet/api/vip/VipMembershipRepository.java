package com.speedbet.api.vip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VipMembershipRepository extends JpaRepository<VipMembership, UUID> {
    Optional<VipMembership> findByUserIdAndStatus(UUID userId, String status);

    @Query("SELECT v FROM VipMembership v WHERE v.status = 'ACTIVE' AND v.expiresAt < :now")
    List<VipMembership> findExpired(Instant now);

    @Query("SELECT v FROM VipMembership v WHERE v.status = 'ACTIVE'")
    List<VipMembership> findAllActive();

    boolean existsByUserIdAndStatus(UUID userId, String status);
}
