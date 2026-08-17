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
 * Tenant binding and licence expiry — spec: {@code planning/kit-shop.md}
 * §4 E5.
 */
class KitLicenseGateTest {

    private static final String TENANT = "acme-tenant";
    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");

    private final KitLicenseGate gate = new KitLicenseGate();

    @Test
    void kitLicensedToThisTenant_passes() {
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo(TENANT).build(),
                KitSignatureStatus.VERIFIED, TENANT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void kitLicensedToAnotherTenant_isRefused() {
        // The reason tenant binding exists. A correctly signed kit for
        // someone else is genuine and still not yours — the signature alone
        // would happily verify it here.
        assertThatThrownBy(() -> gate.enforce(
                descriptor().licensedTo("someone-else").build(),
                KitSignatureStatus.VERIFIED, TENANT, source(), NOW))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("someone-else")
                .hasMessageContaining("not to this tenant");
    }

    @Test
    void expiredLicence_isRefused() {
        assertThatThrownBy(() -> gate.enforce(
                descriptor().licensedTo(TENANT)
                        .licenseExpiresAt(NOW.minusSeconds(1)).build(),
                KitSignatureStatus.VERIFIED, TENANT, source(), NOW))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("expired")
                // The message has to say what does NOT happen, or an expiry
                // reads as "your kits are about to disappear".
                .hasMessageContaining("keep working");
    }

    @Test
    void licenceExpiringLater_passes() {
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo(TENANT)
                        .licenseExpiresAt(NOW.plusSeconds(86_400)).build(),
                KitSignatureStatus.VERIFIED, TENANT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void unsignedKitClaimingAnotherTenant_isNotEnforced() {
        // Without a signature the field is text anyone can edit. Enforcing it
        // would stop the honest and inconvenience nobody else, while implying
        // a guarantee that is not there.
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo("someone-else").build(),
                KitSignatureStatus.UNSIGNED, TENANT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void failedSignatureWithExpiredLicence_isNotEnforced() {
        // Same reasoning: a signature that did not verify gives its payload
        // no authority, so neither field can be acted on.
        assertThatCode(() -> gate.enforce(
                descriptor().licensedTo(TENANT)
                        .licenseExpiresAt(NOW.minusSeconds(1)).build(),
                KitSignatureStatus.FAILED, TENANT, source(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void kitWithoutLicenceFields_passesRegardlessOfSignature() {
        // Everything from git. The gate must be invisible for them.
        assertThatCode(() -> gate.enforce(
                descriptor().build(), KitSignatureStatus.UNSIGNED, TENANT, source(), NOW))
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
