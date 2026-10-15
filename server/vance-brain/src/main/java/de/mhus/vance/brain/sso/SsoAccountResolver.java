package de.mhus.vance.brain.sso;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.user.ExternalEmails;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Who a verified external sign-in is, in Vance ({@code planning/sso-login.md}
 * §6.2). In this order:
 *
 * <ol>
 *   <li>An account bound to {@code (issuer, sub)} — that is the person. If it
 *       lives in another tenant than the sign-in points at, refuse: the domain
 *       was moved, which is an admin matter, not a silent move.</li>
 *   <li>An account in the tenant <em>prepared</em> for exactly this address —
 *       bind it. The name does not matter; the admin chose it.</li>
 *   <li>Only with {@code autoCreate} (Workspace domains, never private Gmail):
 *       derive the name from the local part and create the account — unless the
 *       name is taken, by anybody, in any state.</li>
 * </ol>
 *
 * <p>Nothing here ever takes over an account that was not prepared for this
 * sign-in: not a password account, not a passwordless one, not a service
 * account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SsoAccountResolver {

    /** Same rule as the REST user-create DTO ({@code UserCreateRequest}). */
    static final Pattern USER_NAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]*$");

    private final UserService userService;
    private final AuditService auditService;

    /** A verified sign-in, already checked against the domain/consumer rules. */
    public record SignIn(
            String tenant,
            String provider,
            String issuer,
            String subject,
            String email,
            String normalizedEmail,
            @Nullable String name,
            boolean autoCreate) {}

    /** The resolved account, or why there is none. Exactly one is set. */
    public record Outcome(
            @Nullable UserDocument user, @Nullable String rejection) {
        static Outcome ok(UserDocument user) {
            return new Outcome(user, null);
        }

        static Outcome reject(String reason) {
            return new Outcome(null, reason);
        }
    }

    public Outcome resolve(SignIn in) {
        Optional<UserDocument> bound = userService.findByExternalSubject(in.issuer(), in.subject());
        if (bound.isPresent()) {
            UserDocument user = bound.get();
            if (!in.tenant().equals(user.getTenantId())) {
                return Outcome.reject("tenant-moved");
            }
            String usable = unusable(user);
            if (usable != null) {
                return Outcome.reject(usable);
            }
            userService.recordExternalLogin(user.getTenantId(), user.getName(), in.email());
            return Outcome.ok(user);
        }

        Optional<UserDocument> prepared = userService.findPreparedByEmail(in.tenant(), in.normalizedEmail());
        if (prepared.isPresent()) {
            UserDocument candidate = prepared.get();
            String usable = unusable(candidate);
            if (usable != null) {
                return Outcome.reject(usable);
            }
            Optional<UserDocument> linked = userService.linkExternalIdentity(
                    in.tenant(), candidate.getName(), in.issuer(), in.normalizedEmail(), in.subject(), in.email());
            if (linked.isEmpty()) {
                // Prepared for another issuer, or a concurrent first sign-in won.
                return Outcome.reject("not-prepared");
            }
            auditService.authSsoIdentity(in.tenant(), candidate.getName(), "linked", null);
            return Outcome.ok(linked.get());
        }

        if (!in.autoCreate()) {
            return Outcome.reject("not-prepared");
        }
        String name = in.name();
        if (name == null || !USER_NAME.matcher(name).matches()) {
            return Outcome.reject("name-invalid");
        }
        if (userService.existsByTenantAndName(in.tenant(), name)) {
            return Outcome.reject("name-taken");
        }
        UserDocument created;
        try {
            created = userService.createExternal(
                    in.tenant(), name, null, in.provider(), in.issuer(), in.subject(), in.email());
        } catch (UserService.UserAlreadyExistsException | UserService.ReservedNameException e) {
            return Outcome.reject("name-taken");
        } catch (UserService.ExternalIdentityException e) {
            // A concurrent first sign-in of the same person bound the subject a
            // moment ago. The account is created in one write, so nothing is
            // left behind.
            log.trace("SSO auto-create refused tenant='{}' name='{}': {}", in.tenant(), name, e.getMessage());
            return Outcome.reject("identity-taken");
        }
        auditService.authSsoIdentity(in.tenant(), name, "user-created", null);
        return Outcome.ok(created);
    }

    /** Derives the account name for {@code autoCreate} from a normalised address. */
    public static @Nullable String nameFor(String normalizedEmail) {
        if (ExternalEmails.hasPlusTag(normalizedEmail)) {
            return null;
        }
        String local = ExternalEmails.localPart(normalizedEmail);
        if (local.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)
                || !USER_NAME.matcher(local).matches()) {
            return null;
        }
        return local;
    }

    private static @Nullable String unusable(UserDocument user) {
        if (user.isServiceAccount()) {
            return "service-account";
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return "user-not-active";
        }
        if (!user.isLoginEnabled()) {
            return "login-disabled";
        }
        return null;
    }
}
