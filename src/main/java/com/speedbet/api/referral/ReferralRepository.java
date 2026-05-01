package com.speedbet.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {
    Optional<Referral> findByUserId(UUID userId);
    List<Referral> findByLinkId(UUID linkId);

    @Query("SELECT r FROM Referral r WHERE r.linkId IN " +
           "(SELECT l.id FROM ReferralLink l WHERE l.adminId = :adminId)")
    List<Referral> findByAdminId(UUID adminId);

    @Query("SELECT COALESCE(SUM(r.lifetimeCommission),0) FROM Referral r WHERE r.linkId IN " +
           "(SELECT l.id FROM ReferralLink l WHERE l.adminId = :adminId)")
    BigDecimal totalCommissionByAdmin(UUID adminId);
}
