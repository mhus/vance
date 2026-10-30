package de.mhus.vance.shared.user;

import de.mhus.vance.shared.mail.MailOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * E-mail codes: a twelve-digit code mailed to the account's address stands in
 * for a password, and the same mechanism confirms a changed address. Every request issues a new
 * code and voids the one before; the {@code transactionId} is shown in the mail
 * and on the page, so the user can tell which code belongs to which request.
 *
 * <p>The address is not unique in Vance, so a request is addressed by
 * {@code (tenant, username)} and answered with a random {@code transactionId}
 * that the verification must send back. One open code per account and purpose, kept in
 * {@link EmailLoginCodeDocument} (evicted by a TTL index); only an HMAC of it is kept.
 *
 * <p>What the code is protected by: a lifetime, {@link #MAX_ATTEMPTS} attempts
 * per code, the account lockout (failed codes
 * count like failed passwords), and single use — a verified code is consumed
 * atomically. A request for an unknown or ineligible login answers like a real
 * one and mails nothing, so the answer does not tell whether an account exists.
 */
@Slf4j
@Service
public class EmailLoginService {

    public static final int TOKEN_LENGTH = 12;

    /** Verification attempts a single code survives. */
    public static final int MAX_ATTEMPTS = 5;

    private static final String HMAC = "HmacSHA256";

    private final UserService users;
    private final EmailLoginCodeService codes;
    private final MailOutboxService outbox;
    private final SecretKeySpec hmacKey;
    private final Duration lifetime;
    private final SecureRandom random = new SecureRandom();

    public EmailLoginService(
            UserService users,
            EmailLoginCodeService codes,
            MailOutboxService outbox,
            @Value("${vance.encryption.password}") String secret,
            @Value("${vance.auth.email-token.lifetime-minutes:10}") long lifetimeMinutes) {
        this.users = users;
        this.codes = codes;
        this.outbox = outbox;
        this.hmacKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC);
        this.lifetime = Duration.ofMinutes(lifetimeMinutes);
    }

    /** The answer to a request: where to send the code back to, and until when it is good. */
    public record Started(String transactionId, Instant expiresAt) {}

    /** Outcome of {@link #verify}; {@code address} is the one the code went to, set on success only. */
    public record Verification(boolean success, @Nullable String address) {

        static Verification failed() {
            return new Verification(false, null);
        }
    }

    /**
     * Mails a login code to the account. Always answers the same way: when the
     * account does not exist, may not log in, is locked or has no address, the
     * answer is a fresh handle that no code belongs to.
     */
    public Started startLogin(String tenantId, String name) {
        Optional<UserDocument> user = users.findByTenantAndName(tenantId, name).filter(this::mayLogInByMail);
        if (user.isEmpty()) {
            return new Started(newTransactionId(), Instant.now().plus(lifetime));
        }
        return issue(
                user.get(),
                EmailLoginPurpose.LOGIN,
                StringUtils.trimToEmpty(user.get().getEmail()));
    }

    /**
     * Mails a confirmation code to {@code newEmail}; the caller has already
     * authenticated the user.
     *
     * @throws UserService.UserNotFoundException when the account does not exist
     */
    public Started startEmailChange(String tenantId, String name, String newEmail) {
        UserDocument user = users.findByTenantAndName(tenantId, name)
                .orElseThrow(() -> new UserService.UserNotFoundException("User '" + name + "' not found"));
        if (StringUtils.isBlank(newEmail)) {
            throw new IllegalArgumentException("newEmail must not be blank");
        }
        return issue(user, EmailLoginPurpose.EMAIL_CHANGE, newEmail.trim());
    }

    /**
     * Checks a code that was issued for {@code purpose}. A code issued for
     * something else fails untouched — it is neither counted nor consumed, so a
     * confirmation code cannot be spent as a login. On success the code is
     * consumed and the caller acts on {@link Verification#address()}; on any
     * failure there is no reason in the answer.
     */
    public Verification verify(
            String tenantId, String name, EmailLoginPurpose purpose, String transactionId, String token) {
        String code = StringUtils.deleteWhitespace(StringUtils.defaultString(token));
        if (code.length() != TOKEN_LENGTH || !StringUtils.isNumeric(code)) {
            return Verification.failed();
        }
        Optional<UserDocument> found = users.findByTenantAndName(tenantId, name);
        if (found.isEmpty()) {
            return Verification.failed();
        }
        UserDocument user = found.get();
        EmailLoginCodeDocument open = codes.find(tenantId, name, purpose).orElse(null);
        if (open == null || !sameBytes(open.getTransactionId(), transactionId)) {
            return Verification.failed();
        }
        if (Instant.now().isAfter(open.getExpiresAt())) {
            codes.consume(tenantId, name, purpose, open.getTransactionId());
            return Verification.failed();
        }
        if (users.isLocked(user)) {
            return Verification.failed();
        }
        EmailLoginCodeDocument counted = codes.countAttempt(tenantId, name, purpose, open.getTransactionId())
                .orElse(null);
        if (counted == null || counted.getAttempts() > MAX_ATTEMPTS) {
            codes.consume(tenantId, name, purpose, open.getTransactionId());
            return Verification.failed();
        }
        if (!sameBytes(counted.getTokenHash(), hash(counted.getTransactionId(), code))) {
            users.recordFailedLogin(tenantId, name);
            if (counted.getAttempts() >= MAX_ATTEMPTS) {
                codes.consume(tenantId, name, purpose, counted.getTransactionId());
            }
            return Verification.failed();
        }
        if (!codes.consume(tenantId, name, purpose, counted.getTransactionId())) {
            return Verification.failed();
        }
        return new Verification(true, counted.getAddress());
    }

    /**
     * Confirms an address change: checks the code that went to the new address
     * and, if it is good, makes that address the account's. The old address, if
     * there was one, gets a notice — it is the one thing that lets a legitimate
     * owner notice that somebody else changed the sign-in address.
     *
     * @return the new address, empty if the code did not verify
     */
    public Optional<String> confirmEmailChange(String tenantId, String name, String transactionId, String token) {
        Verification verification = verify(tenantId, name, EmailLoginPurpose.EMAIL_CHANGE, transactionId, token);
        String newAddress = verification.address();
        if (!verification.success() || newAddress == null) {
            return Optional.empty();
        }
        String oldAddress = users.findByTenantAndName(tenantId, name)
                .map(UserDocument::getEmail)
                .orElse(null);
        try {
            users.update(tenantId, name, null, newAddress, null, null);
        } catch (UserService.UserNotFoundException e) {
            return Optional.empty();
        }
        if (StringUtils.isNotBlank(oldAddress) && !oldAddress.equalsIgnoreCase(newAddress)) {
            outbox.enqueue(
                    tenantId,
                    oldAddress,
                    "Your e-mail address was changed",
                    "The e-mail address of your account '" + name + "' was just changed."
                            + " Sign-in codes now go to the new address.\n\n"
                            + "If you did not do this, tell your administrator right away.");
        }
        log.info("E-mail address confirmed tenantId='{}' name='{}'", tenantId, name);
        return Optional.of(newAddress);
    }

    private Started issue(UserDocument user, EmailLoginPurpose purpose, String address) {
        Instant now = Instant.now();
        String code = newCode();
        String transactionId = newTransactionId();
        Instant expiresAt = now.plus(lifetime);
        codes.replace(EmailLoginCodeDocument.builder()
                .tenantId(user.getTenantId())
                .userId(user.getName())
                .transactionId(transactionId)
                .tokenHash(hash(transactionId, code))
                .purpose(purpose)
                .address(address)
                .requestedAt(now)
                .expiresAt(expiresAt)
                .build());
        outbox.enqueue(user.getTenantId(), address, subject(purpose), body(purpose, code, transactionId));
        log.info("E-mail code issued tenantId='{}' name='{}' purpose={}", user.getTenantId(), user.getName(), purpose);
        return new Started(transactionId, expiresAt);
    }

    private boolean mayLogInByMail(UserDocument user) {
        return user.getStatus() == UserStatus.ACTIVE
                && user.isLoginEnabled()
                && !user.isServiceAccount()
                // An SSO account signs in through its identity provider, not by mail.
                && user.getExternalIdentity() == null
                && !users.isLocked(user)
                && StringUtils.isNotBlank(user.getEmail());
    }

    private String subject(EmailLoginPurpose purpose) {
        return switch (purpose) {
            case LOGIN -> "Your sign-in code";
            case EMAIL_CHANGE -> "Confirm your new e-mail address";
        };
    }

    private String body(EmailLoginPurpose purpose, String code, String transactionId) {
        String intro =
                switch (purpose) {
                    case LOGIN -> "Your sign-in code:";
                    case EMAIL_CHANGE -> "Your confirmation code for the new e-mail address:";
                };
        return intro + "\n\n    " + group(code) + "\n\nReference: " + transactionId
                + "\n(the sign-in page shows the same reference; use the code only if they match)"
                + "\n\nIt is valid for " + lifetime.toMinutes()
                + " minutes and works once. A newer code makes this one void."
                + " If you did not ask for it, ignore this mail.";
    }

    /** Twelve digits in groups of three, easier to read and to copy. */
    private static String group(String code) {
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(code.charAt(i));
        }
        return grouped.toString();
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    private String newTransactionId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String transactionId, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal((transactionId + ':' + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean sameBytes(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
