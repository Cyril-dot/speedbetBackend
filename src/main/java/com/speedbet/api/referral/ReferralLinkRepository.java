package com.speedbet.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralLinkRepository extends JpaRepository<ReferralLink, UUID> {
    List<ReferralLink> findByAdminId(UUID adminId);
    Optional<ReferralLink> findByCode(String code);
    Optional<ReferralLink> findByCodeAndActiveTrue(String code);
}
