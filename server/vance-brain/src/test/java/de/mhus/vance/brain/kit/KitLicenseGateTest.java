package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Account binding and licence expiry — spec:
 * {@code planning/kit-store.md} §3 S2, {@code planning/kit-shop.md} §4 E5.
 */
class KitLicenseGateTest {

    /** The store account this installation is signed in to. */
    private static final String ACCOUNT = "acc_7f3k9m2p4q";
    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");

    private final KitLicenseGate gate = new KitLicenseGate();

    @Test
    void kitLicensedToTheLinkedAccount_passes() {
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo(ACCOUNT).build(),
                KitSignatureStatus.VERIFIED, ACCOUNT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void kitLicensedToAnotherAccount_isRefused() {
        // The reason account binding exists. A correctly signed kit for
        // someone else is genuine and still not yours — the signature alone
        // would happily verify it here.
        assertThatThrownBy(() -> gate.enforce(
                descriptor().licensedTo("acc_someoneelse").build(),
                KitSignatureStatus.VERIFIED, ACCOUNT, source(), NOW))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("acc_someoneelse")
                .hasMessageContaining("signed in as '" + ACCOUNT + "'");
    }

    @Test
    void licensedKitOnAnUnlinkedInstallation_isRefusedAndSaysSo() {
        // The likely case in practice: someone copies a purchased kit to a
        // brain that was never signed in. The message has to name the cause,
        // because "licensed to acc_x" alone reads as a defect rather than as
        // "sign in first".
        assertThatThrownBy(() -> gate.enforce(
                descriptor().licensedTo("acc_owner0001").build(),
                KitSignatureStatus.VERIFIED, null, source(), NOW))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not signed in to any store account");
    }

    // No "same account on a second brain" case here. Under the old tenant
    // binding that was the interesting one; now the gate takes no tenant at
    // all, so it cannot be expressed at this level and any test claiming to
    // cover it would only repeat the first one above under a name that
    // promises more. What binding to the account buys is visible in the
    // signature, not in an extra assertion.

    @Test
    void expiredLicence_installsAnyway() {
        // The library decides entitlement — it knows publication dates and
        // deliberately keeps serving what was already paid for, so a buyer
        // who reinstalls a machine does not lose a version they may run.
        // Refusing here would overrule that with less information and make
        // an HTTP 200 download uninstallable.
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo(ACCOUNT)
                        .licenseExpiresAt(NOW.minusSeconds(1)).build(),
                KitSignatureStatus.VERIFIED, ACCOUNT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void licenceExpiringLater_passes() {
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo(ACCOUNT)
                        .licenseExpiresAt(NOW.plusSeconds(86_400)).build(),
                KitSignatureStatus.VERIFIED, ACCOUNT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void unsignedKitClaimingAnotherAccount_isNotEnforced() {
        // Without a signature the field is text anyone can edit. Enforcing it
        // would stop the honest and inconvenience nobody else, while implying
        // a guarantee that is not there.
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo("acc_someoneelse").build(),
                KitSignatureStatus.UNSIGNED, ACCOUNT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void failedSignatureWithForeignAccount_isNotEnforced() {
        // A signature that did not verify gives its payload no authority, so
        // the account field cannot be acted on either way.
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo("acc_someoneelse").build(),
                KitSignatureStatus.FAILED, ACCOUNT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void kitWithoutLicenceFields_passesRegardlessOfSignature() {
        // Everything from git. The gate must be invisible for them — including
        // on an installation that is signed in to no store at all.
        assertThatCode(() -> gate.enforce(
                descriptor().build(), KitSignatureStatus.UNSIGNED, null, source(), NOW))
                .doesNotThrowAnyException();
    }

    private static KitDescriptorDto.KitDescriptorDtoBuilder descriptor() {
        return KitDescriptorDto.builder().name("bought-kit").description("a purchased kit");
    }

    private static KitSourceDto source() {
        return KitSourceDto.builder()
                .id("vancetope-library")
                .type(KitSourceType.LIBRARY)
                .url("https://library.vancetope.com")
                .build();
    }
}
