package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.shared.kit.KitException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Checks whether this tenant may have this kit, and still may.
 *
 * <p>Distinct from {@link KitSignatureGate}, which asks whether a kit is
 * genuine. Genuine and permitted are different questions, and the
 * signature only answers the first: a correctly signed kit licensed to
 * someone else is entirely genuine and entirely not yours.
 *
 * <p>Spec: {@code planning/kit-shop.md} §4 E5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitLicenseGate {

    /**
     * Refuse a kit that is not this tenant's, or whose entitlement to
     * updates has run out.
     *
     * <p><b>Only acts on a verified signature.</b> Unsigned, the licence
     * fields are text anyone can edit — enforcing them would stop the
     * honest and inconvenience nobody else, while suggesting a guarantee
     * that is not there. Where they appear without a signature they are
     * logged and ignored.
     *
     * @param descriptor the kit's descriptor, carrying the licence claims
     * @param signature what {@link KitSignatureGate} concluded
     * @param tenantId the tenant installing
     * @param config the source, for the message
     * @param now evaluation time, injected so expiry is testable
     */
    public void enforce(
            KitDescriptorDto descriptor,
            KitSignatureStatus signature,
            String tenantId,
            KitSourceDto config,
            Instant now) {

        boolean claimsLicence = descriptor.getLicensedTo() != null
                || descriptor.getLicenseExpiresAt() != null;
        if (!claimsLicence) return;

        if (signature != KitSignatureStatus.VERIFIED) {
            log.info("KitLicenseGate: '{}' from source '{}' carries licence fields but no"
                            + " verified signature — they are not enforceable and were ignored",
                    descriptor.getName(), config.getId());
            return;
        }

        String licensedTo = descriptor.getLicensedTo();
        if (licensedTo != null && !licensedTo.equals(tenantId)) {
            // The point of binding a delivery to a tenant. Without this check
            // the signature proves only that nobody edited the name — not that
            // the name is yours.
            throw new KitException("kit '" + descriptor.getName() + "' is licensed to '"
                    + licensedTo + "', not to this tenant. A delivery is bound to the tenant"
                    + " it was made for.");
        }

        Instant expires = descriptor.getLicenseExpiresAt();
        if (expires != null && expires.isBefore(now)) {
            // Refusing the install, not touching anything installed: a lapsed
            // licence stops entitling new versions. Removing artefacts from a
            // running system is a different act entirely and is not something
            // a date in a metadata field decides.
            throw new KitException("the licence for kit '" + descriptor.getName()
                    + "' expired on " + expires + ". Kits already installed keep working;"
                    + " this only stops installing or updating from it.");
        }
    }
}
