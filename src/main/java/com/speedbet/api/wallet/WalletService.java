package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepo;
    private final TransactionRepository txRepo;
    private final EntityManager em;

    public Wallet getWallet(UUID userId) {
        return walletRepo.findByUserId(userId)
            .orElseThrow(() -> ApiException.notFound("Wallet not found"));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Transaction debit(UUID userId, BigDecimal amount, TxKind kind,
                              String providerRef, Map<String, Object> metadata) {
        var wallet = em.find(Wallet.class,
            walletRepo.findByUserId(userId).orElseThrow().getId(),
            LockModeType.PESSIMISTIC_WRITE);

        if (wallet.getBalance().compareTo(amount) < 0)
            throw ApiException.unprocessable("Insufficient balance");

        wallet.setBalance(wallet.getBalance().subtract(amount, MathContext.DECIMAL64));

        return txRepo.save(Transaction.builder()
            .walletId(wallet.getId())
            .kind(kind)
            .amount(amount.negate())
            .balanceAfter(wallet.getBalance())
            .providerRef(providerRef)
            .metadata(metadata)
            .build());
    }

    @Transactional
    public Transaction credit(UUID userId, BigDecimal amount, TxKind kind,
                               String providerRef, Map<String, Object> metadata) {
        // Idempotency check
        if (providerRef != null && txRepo.existsByProviderRef(providerRef))
            throw ApiException.conflict("Transaction already processed: " + providerRef);

        var wallet = walletRepo.findByUserId(userId)
            .orElseThrow(() -> ApiException.notFound("Wallet not found"));
        wallet.setBalance(wallet.getBalance().add(amount, MathContext.DECIMAL64));
        walletRepo.save(wallet);

        return txRepo.save(Transaction.builder()
            .walletId(wallet.getId())
            .kind(kind)
            .amount(amount)
            .balanceAfter(wallet.getBalance())
            .providerRef(providerRef)
            .metadata(metadata)
            .build());
    }

    public Page<Transaction> getTransactions(UUID userId, Pageable pageable) {
        var wallet = getWallet(userId);
        return txRepo.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
    }
}
