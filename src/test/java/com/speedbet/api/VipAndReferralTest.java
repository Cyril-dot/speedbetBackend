package com.speedbet.api;

import com.speedbet.api.bet.*;
import com.speedbet.api.referral.*;
import com.speedbet.api.vip.*;
import com.speedbet.api.wallet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VipCashbackTest {

    @Mock VipMembershipRepository membershipRepo;
    @Mock VipCashbackRepository cashbackRepo;
    @Mock VipGiftRepository giftRepo;
    @Mock VipGiveawayRepository giveawayRepo;
    @Mock WalletService walletService;
    @InjectMocks VipService vipService;

    @Test
    void cashback_fires_on10PlusSelectionsWithOneLoss() throws Exception {
        setVipFields();
        var userId = UUID.randomUUID();
        var betId = UUID.randomUUID();

        when(membershipRepo.existsByUserIdAndStatus(userId, "ACTIVE")).thenReturn(true);
        when(membershipRepo.findByUserIdAndStatus(userId, "ACTIVE"))
            .thenReturn(Optional.of(VipMembership.builder().userId(userId)
                .startedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(3600)).build()));
        when(cashbackRepo.existsByBetId(betId)).thenReturn(false);
        when(cashbackRepo.sumByUserSince(any(), any())).thenReturn(BigDecimal.ZERO);

        var selections = new ArrayList<BetSelection>();
        for (int i = 0; i < 9; i++) {
            var s = new BetSelection(); s.setResult("WON"); selections.add(s);
        }
        var lost = new BetSelection(); lost.setResult("LOST"); selections.add(lost);

        var bet = Bet.builder().id(betId).userId(userId)
            .stake(new BigDecimal("20")).status(BetStatus.LOST)
            .placedAt(Instant.now()).build();
        bet.getSelections().addAll(selections);

        vipService.checkCashback(bet);

        verify(cashbackRepo).save(any());
        verify(walletService).credit(eq(userId), any(), eq(TxKind.VIP_CASHBACK), any(), any());
    }

    @Test
    void cashback_doesNotFire_whenNotVip() throws Exception {
        setVipFields();
        var userId = UUID.randomUUID();
        when(membershipRepo.existsByUserIdAndStatus(userId, "ACTIVE")).thenReturn(false);

        var bet = Bet.builder().id(UUID.randomUUID()).userId(userId)
            .stake(new BigDecimal("20")).status(BetStatus.LOST).placedAt(Instant.now()).build();
        vipService.checkCashback(bet);
        verify(cashbackRepo, never()).save(any());
    }

    @Test
    void cashback_doesNotFire_whenTwoLosses() throws Exception {
        setVipFields();
        var userId = UUID.randomUUID();
        when(membershipRepo.existsByUserIdAndStatus(userId, "ACTIVE")).thenReturn(true);
        when(membershipRepo.findByUserIdAndStatus(userId, "ACTIVE"))
            .thenReturn(Optional.of(VipMembership.builder().userId(userId)
                .startedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(3600)).build()));

        var selections = new ArrayList<BetSelection>();
        for (int i = 0; i < 8; i++) { var s = new BetSelection(); s.setResult("WON"); selections.add(s); }
        for (int i = 0; i < 2; i++) { var s = new BetSelection(); s.setResult("LOST"); selections.add(s); }

        var bet = Bet.builder().id(UUID.randomUUID()).userId(userId)
            .stake(new BigDecimal("20")).status(BetStatus.LOST).placedAt(Instant.now()).build();
        bet.getSelections().addAll(selections);

        vipService.checkCashback(bet);
        verify(cashbackRepo, never()).save(any());
    }

    private void setVipFields() throws Exception {
        setField(vipService, "cashbackMinSelections", 10);
        setField(vipService, "cashbackMinStake", new BigDecimal("10"));
        setField(vipService, "cashbackWeeklyCap", new BigDecimal("1000"));
        setField(vipService, "membershipPrice", new BigDecimal("250"));
        setField(vipService, "membershipDays", 30);
    }

    private void setField(Object obj, String name, Object val) throws Exception {
        var f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, val);
    }
}

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock ReferralLinkRepository linkRepo;
    @Mock ReferralRepository referralRepo;
    @Mock WalletService walletService;
    @InjectMocks ReferralService referralService;

    @Test
    void attributeCommission_creditsAdminWallet() {
        var userId = UUID.randomUUID();
        var adminId = UUID.randomUUID();
        var linkId = UUID.randomUUID();

        var link = ReferralLink.builder().id(linkId).adminId(adminId)
            .code("TESTCODE").commissionPercent(new BigDecimal("10")).build();
        var referral = Referral.builder().linkId(linkId).userId(userId)
            .lifetimeStake(BigDecimal.ZERO).lifetimeCommission(BigDecimal.ZERO).build();

        when(referralRepo.findByUserId(userId)).thenReturn(Optional.of(referral));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));

        referralService.attributeCommission(userId, new BigDecimal("100"));

        // 10% of 100 = 10 GHS commission
        verify(walletService).credit(eq(adminId), eq(new BigDecimal("10.0000000000")), any(), any(), any());
        assertThat(referral.getLifetimeStake()).isEqualByComparingTo(new BigDecimal("100"));
    }
}
