package com.speedbet.api.wallet;

public enum TxKind {
    DEPOSIT,
    WITHDRAW,
    WITHDRAW_HOLD,      // funds held pending withdrawal approval
    WITHDRAW_RELEASE,   // held funds released (approved or rejected)
    BET_STAKE,
    BET_WIN,
    REFERRAL_COMMISSION,
    PAYOUT,
    ADJUSTMENT,
    VIP_CASHBACK,
    VIP_MEMBERSHIP
}