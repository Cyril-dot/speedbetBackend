package com.speedbet.api.user;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.Wallet;
import com.speedbet.api.wallet.WalletRepository;
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
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepo;
    private final WalletRepository walletRepo;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepo,
                       WalletRepository walletRepo,
                       PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.walletRepo = walletRepo;
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

        var wallet = Wallet.builder()
                .userId(user.getId())
                .currency("GHS")
                .balance(BigDecimal.ZERO)
                .build();

        walletRepo.save(wallet);
        log.info("register: wallet created for userId='{}'", user.getId());

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