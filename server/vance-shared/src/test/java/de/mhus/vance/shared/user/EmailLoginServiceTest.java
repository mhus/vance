package de.mhus.vance.shared.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.mail.MailOutboxService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The code logic of {@link EmailLoginService} against an in-memory stand-in for
 * the stored codes. The atomic Mongo operations behind it
 * ({@link EmailLoginCodeService}) are the E2E lane's concern.
 */
class EmailLoginServiceTest {

    private static final String TENANT = "acme";
    private static final String NAME = "alice";
    private static final EmailLoginPurpose PURPOSE = EmailLoginPurpose.LOGIN;

    private UserService users;
    private EmailLoginCodeService codes;
    private MailOutboxService outbox;
    private UserDocument user;
    private EmailLoginService service;
    /** The stored codes, one per purpose, like the collection. */
    private final Map<EmailLoginPurpose, EmailLoginCodeDocument> stored = new EnumMap<>(EmailLoginPurpose.class);

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        codes = mock(EmailLoginCodeService.class);
        outbox = mock(MailOutboxService.class);
        user = account().email("alice@example.com").build();
        when(users.findByTenantAndName(TENANT, NAME)).thenAnswer(inv -> Optional.of(user));
        when(users.isLocked(any())).thenReturn(false);
        org.mockito.Mockito.doAnswer(inv -> {
                    EmailLoginCodeDocument code = inv.getArgument(0);
                    stored.put(code.getPurpose(), code);
                    return null;
                })
                .when(codes)
                .replace(any());
        when(codes.find(eq(TENANT), eq(NAME), any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<EmailLoginPurpose>getArgument(2))));
        when(codes.countAttempt(eq(TENANT), eq(NAME), any(), anyString())).thenAnswer(inv -> {
            EmailLoginCodeDocument open = stored.get(inv.<EmailLoginPurpose>getArgument(2));
            if (open == null || !open.getTransactionId().equals(inv.getArgument(3))) {
                return Optional.empty();
            }
            open.setAttempts(open.getAttempts() + 1);
            return Optional.of(open);
        });
        when(codes.consume(eq(TENANT), eq(NAME), any(), anyString())).thenAnswer(inv -> {
            EmailLoginCodeDocument open = stored.get(inv.<EmailLoginPurpose>getArgument(2));
            if (open == null || !open.getTransactionId().equals(inv.getArgument(3))) {
                return false;
            }
            stored.remove(open.getPurpose());
            return true;
        });
        service = new EmailLoginService(users, codes, outbox, "test-secret", 10);
    }

    @Test
    void aLoginCodeIsMailedAndVerifiesOnce() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);
        String code = mailedCode();

        EmailLoginService.Verification first = service.verify(TENANT, NAME, PURPOSE, started.transactionId(), code);
        EmailLoginService.Verification second = service.verify(TENANT, NAME, PURPOSE, started.transactionId(), code);

        assertThat(code).matches("\\d{12}");
        assertThat(first.success()).isTrue();
        assertThat(first.address()).isEqualTo("alice@example.com");
        assertThat(second.success()).isFalse();
    }

    @Test
    void theCodeIsStoredHashedNotInTheClear() {
        service.startLogin(TENANT, NAME);

        assertThat(stored.get(PURPOSE).getTokenHash()).hasSize(64).doesNotContain(mailedCode());
    }

    @Test
    void theCodeIsAcceptedWithTheSpacesOfTheMail() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);
        String code = mailedCode();
        String spaced = code.substring(0, 3) + " " + code.substring(3, 6) + " " + code.substring(6, 9) + " "
                + code.substring(9);

        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), spaced)
                        .success())
                .isTrue();
    }

    @Test
    void aWrongCodeFailsAndCountsAsAFailedLogin() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);
        String wrong = otherCode(mailedCode());

        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), wrong)
                        .success())
                .isFalse();

        verify(users).recordFailedLogin(TENANT, NAME);
        assertThat(stored.get(PURPOSE)).isNotNull();
    }

    @Test
    void theCodeDiesAfterTheLastAllowedAttempt() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);
        String code = mailedCode();
        String wrong = otherCode(code);

        for (int i = 0; i < EmailLoginService.MAX_ATTEMPTS; i++) {
            service.verify(TENANT, NAME, PURPOSE, started.transactionId(), wrong);
        }

        assertThat(stored.get(PURPOSE)).isNull();
        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), code)
                        .success())
                .isFalse();
    }

    @Test
    void anotherTransactionIdDoesNotVerify() {
        service.startLogin(TENANT, NAME);

        assertThat(service.verify(TENANT, NAME, PURPOSE, "someone-elses", mailedCode())
                        .success())
                .isFalse();
        verify(codes, never()).countAttempt(anyString(), anyString(), any(), anyString());
    }

    @Test
    void anExpiredCodeFailsAndIsRemoved() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);
        String code = mailedCode();
        stored.get(PURPOSE).setExpiresAt(Instant.now().minusSeconds(1));

        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), code)
                        .success())
                .isFalse();
        assertThat(stored.get(PURPOSE)).isNull();
    }

    @Test
    void aMalformedCodeFailsWithoutCostingAnAttempt() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);

        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), "12345")
                        .success())
                .isFalse();
        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), "12345678901x")
                        .success())
                .isFalse();
        verify(codes, never()).countAttempt(anyString(), anyString(), any(), anyString());
    }

    @Test
    void aLockedAccountCannotVerify() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);
        String code = mailedCode();
        when(users.isLocked(any())).thenReturn(true);

        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), code)
                        .success())
                .isFalse();
    }

    @Test
    void askingAgainIssuesANewCodeAndVoidsTheOldOne() {
        EmailLoginService.Started first = service.startLogin(TENANT, NAME);
        String oldCode = mailedCode();

        EmailLoginService.Started second = service.startLogin(TENANT, NAME);

        assertThat(second.transactionId()).isNotEqualTo(first.transactionId());
        verify(outbox, times(2)).enqueue(anyString(), anyString(), anyString(), anyString());
        assertThat(service.verify(TENANT, NAME, PURPOSE, first.transactionId(), oldCode)
                        .success())
                .isFalse();
    }

    @Test
    void theMailNamesTheTransactionIdSoTheUserCanMatchIt() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(anyString(), anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("Reference: " + started.transactionId());
    }

    @Test
    void anUnknownAccountGetsAnAnswerLikeARealOneAndNoMail() {
        when(users.findByTenantAndName(TENANT, "nobody")).thenReturn(Optional.empty());

        EmailLoginService.Started started = service.startLogin(TENANT, "nobody");

        assertThat(started.transactionId()).isNotBlank();
        assertThat(started.expiresAt()).isAfter(Instant.now());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void accountsThatMayNotLogInGetNoMail() {
        UserDocument noMail = account().build();
        UserDocument disabled =
                account().email("a@b.de").status(UserStatus.DISABLED).build();
        UserDocument blocked = account().email("a@b.de").loginEnabled(false).build();
        UserDocument serviceAccount =
                account().email("a@b.de").serviceAccount(true).build();

        for (UserDocument candidate : new UserDocument[] {noMail, disabled, blocked, serviceAccount}) {
            user = candidate;
            assertThat(service.startLogin(TENANT, NAME).transactionId()).isNotBlank();
        }

        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aLockedAccountGetsNoLoginMail() {
        when(users.isLocked(any())).thenReturn(true);

        service.startLogin(TENANT, NAME);

        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anEmailChangeCodeGoesToTheNewAddressAndCarriesItBack() {
        EmailLoginService.Started started = service.startEmailChange(TENANT, NAME, " new@example.com ");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(TENANT), to.capture(), anyString(), anyString());
        EmailLoginService.Verification verification =
                service.verify(TENANT, NAME, EmailLoginPurpose.EMAIL_CHANGE, started.transactionId(), mailedCode());

        assertThat(to.getValue()).isEqualTo("new@example.com");
        assertThat(verification.success()).isTrue();
        assertThat(verification.address()).isEqualTo("new@example.com");
    }

    @Test
    void aLoginRequestDoesNotDisturbAPendingEmailChange() {
        EmailLoginService.Started change = service.startEmailChange(TENANT, NAME, "new@example.com");
        String changeCode = mailedCode();

        service.startLogin(TENANT, NAME);

        assertThat(service.verify(TENANT, NAME, EmailLoginPurpose.EMAIL_CHANGE, change.transactionId(), changeCode)
                        .success())
                .isTrue();
    }

    @Test
    void aCodeIssuedForAnotherPurposeIsNeitherCountedNorConsumed() {
        EmailLoginService.Started started = service.startEmailChange(TENANT, NAME, "new@example.com");
        String code = mailedCode();

        assertThat(service.verify(TENANT, NAME, PURPOSE, started.transactionId(), code)
                        .success())
                .isFalse();

        verify(codes, never()).countAttempt(anyString(), anyString(), any(), anyString());
        assertThat(stored.get(EmailLoginPurpose.EMAIL_CHANGE)).isNotNull();
        assertThat(service.verify(TENANT, NAME, EmailLoginPurpose.EMAIL_CHANGE, started.transactionId(), code)
                        .success())
                .isTrue();
    }

    @Test
    void anSsoAccountGetsNoLoginMail() {
        user = account()
                .email("a@b.de")
                .externalIdentity(ExternalIdentity.builder()
                        .issuer(ExternalIdentity.GOOGLE_ISSUER)
                        .build())
                .build();

        service.startLogin(TENANT, NAME);

        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void confirmingAnEmailChangeSwitchesTheAddressAndNotifiesTheOldOne() {
        EmailLoginService.Started started = service.startEmailChange(TENANT, NAME, "new@example.com");
        String code = mailedCode();

        Optional<String> confirmed = service.confirmEmailChange(TENANT, NAME, started.transactionId(), code);

        assertThat(confirmed).contains("new@example.com");
        verify(users).update(TENANT, NAME, null, "new@example.com", null, null);
        verify(outbox).enqueue(eq(TENANT), eq("alice@example.com"), eq("Your e-mail address was changed"), anyString());
    }

    @Test
    void aWrongCodeLeavesTheAddressAlone() {
        EmailLoginService.Started started = service.startEmailChange(TENANT, NAME, "new@example.com");
        String wrong = otherCode(mailedCode());

        assertThat(service.confirmEmailChange(TENANT, NAME, started.transactionId(), wrong))
                .isEmpty();

        verify(users, never()).update(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aLoginCodeDoesNotConfirmAnEmailChange() {
        EmailLoginService.Started started = service.startLogin(TENANT, NAME);

        assertThat(service.confirmEmailChange(TENANT, NAME, started.transactionId(), mailedCode()))
                .isEmpty();

        verify(users, never()).update(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void anAccountWithoutAnOldAddressGetsNoNotice() {
        user = account().build();
        EmailLoginService.Started started = service.startEmailChange(TENANT, NAME, "new@example.com");

        service.confirmEmailChange(TENANT, NAME, started.transactionId(), mailedCode());

        verify(outbox, times(1)).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anEmailChangeNeedsAnAddress() {
        assertThatThrownBy(() -> service.startEmailChange(TENANT, NAME, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmailChangeForAnUnknownAccountIsAnError() {
        when(users.findByTenantAndName(TENANT, "nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startEmailChange(TENANT, "nobody", "x@y.de"))
                .isInstanceOf(UserService.UserNotFoundException.class);
    }

    /** A builder with the state a fresh account has; the builder alone would leave the status unset. */
    private static UserDocument.UserDocumentBuilder account() {
        return UserDocument.builder().tenantId(TENANT).name(NAME).status(UserStatus.ACTIVE);
    }

    /** The code as the user would read it from the last queued mail. */
    private String mailedCode() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbox, org.mockito.Mockito.atLeastOnce())
                .enqueue(anyString(), anyString(), anyString(), body.capture());
        Matcher digits = Pattern.compile("(\\d{3}) (\\d{3}) (\\d{3}) (\\d{3})")
                .matcher(body.getAllValues().get(body.getAllValues().size() - 1));
        assertThat(digits.find()).isTrue();
        return digits.group(1) + digits.group(2) + digits.group(3) + digits.group(4);
    }

    private static String otherCode(String code) {
        return (code.charAt(0) == '0' ? "1" : "0") + code.substring(1);
    }
}
