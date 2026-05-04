package com.speedbet.api.user;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.Wallet;
import com.speedbet.api.wallet.WalletRepository;
import com.speedbet.api.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // ── Welcome-bonus config ──────────────────────────────────────────────────
    private record WelcomeBonus(String currency, BigDecimal amount) {}

    private static final Map<String, WelcomeBonus> WELCOME_BONUSES = Map.of(
            "GH", new WelcomeBonus("GHS", BigDecimal.valueOf(100)),   // Ghana  → 100 GHS
            "NG", new WelcomeBonus("NGN", BigDecimal.valueOf(9_000)), // Nigeria → 9 000 NGN
            "US", new WelcomeBonus("USD", BigDecimal.valueOf(50))     // USD countries → $50
    );

    /** Default when no country-specific bonus is configured. */
    private static final WelcomeBonus DEFAULT_BONUS = new WelcomeBonus("GHS", BigDecimal.ZERO);

    private WelcomeBonus resolveWelcomeBonus(String countryCode) {
        if (countryCode == null) return DEFAULT_BONUS;
        return WELCOME_BONUSES.getOrDefault(countryCode.toUpperCase(), DEFAULT_BONUS);
    }
    // ─────────────────────────────────────────────────────────────────────────

    private final UserRepository userRepo;
    private final WalletRepository walletRepo;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepo,
                       WalletRepository walletRepo,
                       WalletService walletService,
                       PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.walletRepo = walletRepo;
        this.walletService = walletService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("loadUserByUsername: looking up '{}'", email);
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    @Transactional
    public User register(String email, String password, String firstName, String lastName,
                         String phone, String country, UUID referredViaLinkId) {
        log.info("register: attempt for email='{}'", email);

        if (userRepo.existsByEmail(email)) {
            log.warn("register: email '{}' already registered", email);
            throw ApiException.conflict("Email already registered");
        }

        var now = LocalDateTime.now();

        var user = User.builder()
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phone(phone)
                .country(country)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .referredViaLinkId(referredViaLinkId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        log.debug("register: createdAt={} updatedAt={}", user.getCreatedAt(), user.getUpdatedAt());
        log.debug("register: saving user entity for '{}'", email);
        user = userRepo.save(user);
        log.info("register: user saved id='{}' email='{}'", user.getId(), email);

        // ── Resolve welcome bonus before creating the wallet ─────────────────
        var bonus = resolveWelcomeBonus(country);
        log.info("register: welcome bonus for country='{}' → {} {}", country, bonus.amount(), bonus.currency());

        var wallet = Wallet.builder()
                .userId(user.getId())
                .currency(bonus.currency())   // wallet currency matches bonus currency
                .balance(BigDecimal.ZERO)
                .build();

        walletRepo.save(wallet);
        log.info("register: wallet created for userId='{}'", user.getId());

        // Credit the welcome bonus only when there is an actual amount
        if (bonus.amount().compareTo(BigDecimal.ZERO) > 0) {
            walletService.credit(
                    user.getId(),
                    bonus.amount(),
                    TxKind.WELCOME_BONUS,                     // use whatever TxKind constant fits
                    "WELCOME_BONUS_" + user.getId(),   // stable, idempotent providerRef
                    Map.of("reason", "welcome_bonus", "country", country)
            );
            log.info("register: welcome bonus of {} {} credited to userId='{}'",
                    bonus.amount(), bonus.currency(), user.getId());
        }
        // ─────────────────────────────────────────────────────────────────────

        return user;
    }

    @Transactional
    public User createAdmin(String email, String password, String firstName, String lastName,
                            UUID createdByAdminId) {
        log.info("createAdmin: attempt for email='{}'", email);

        if (userRepo.existsByEmail(email)) {
            log.warn("createAdmin: email '{}' already registered", email);
            throw ApiException.conflict("Email already registered");
        }

        var now = LocalDateTime.now();

        var user = User.builder()
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .createdByAdminId(createdByAdminId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        log.debug("createAdmin: createdAt={} updatedAt={}", user.getCreatedAt(), user.getUpdatedAt());
        log.debug("createAdmin: saving admin entity for '{}'", email);
        user = userRepo.save(user);
        log.info("createAdmin: admin saved id='{}' email='{}'", user.getId(), email);

        walletRepo.save(Wallet.builder()
                .userId(user.getId())
                .currency("GHS")
                .balance(BigDecimal.ZERO)
                .build());
        log.info("createAdmin: wallet created for adminId='{}'", user.getId());

        return user;
    }

    @Transactional
    public User updateProfile(UUID userId, String firstName, String lastName,
                              String phone, String country, String themePreference) {
        log.info("updateProfile: userId='{}'", userId);
        var user = getById(userId);

        if (firstName != null) user.setFirstName(firstName);
        if (lastName != null) user.setLastName(lastName);
        if (phone != null) user.setPhone(phone);
        if (country != null) user.setCountry(country);
        if (themePreference != null) user.setThemePreference(themePreference);

        user.setUpdatedAt(LocalDateTime.now());

        var saved = userRepo.save(user);
        log.info("updateProfile: saved userId='{}'", userId);
        return saved;
    }

    public User getById(UUID id) {
        log.debug("getById: id='{}'", id);
        return userRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public User getByEmail(String email) {
        log.debug("getByEmail: email='{}'", email);
        return userRepo.findByEmail(email)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public User save(User user) {
        log.debug("save: userId='{}'", user.getId());
        return userRepo.save(user);
    }

    @Transactional
    public void updatePassword(User user, String newPassword) {
        log.info("updatePassword: userId='{}'", user.getId());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.save(user);
        log.info("updatePassword: done for userId='{}'", user.getId());
    }

    public boolean checkPassword(User user, String password) {
        return passwordEncoder.matches(password, user.getPasswordHash());
    }
}