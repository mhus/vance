package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.shared.kit.KitException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Checks whether this installation may have this kit, and still may.
 *
 * <p>Distinct from {@link KitSignatureGate}, which asks whether a kit is
 * genuine. Genuine and permitted are different questions, and the
 * signature only answers the first: a correctly signed kit licensed to
 * someone else is entirely genuine and entirely not yours.
 *
 * <p>Spec: {@code planning/kit-store.md} §3 S2, {@code planning/kit-shop.md} §4 E5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitLicenseGate {

    /**
     * Refuse a kit that is not this account's, or whose entitlement to
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
     * @param linkedAccount the store account this installation is signed
     *        in to, or null when it is signed in to none
     * @param config the source, for the message
     * @param now evaluation time, injected so expiry is testable
     */
    public void enforce(
            KitDescriptorDto descriptor,
            KitSignatureStatus signature,
            @Nullable String linkedAccount,
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
        if (licensedTo != null && !licensedTo.equals(linkedAccount)) {
            // The point of binding a delivery to an account. Without this
            // check the signature proves only that nobody edited the name —
            // not that the name is yours.
            //
            // It names the buyer, not the installation: a purchase belongs
            // to a person who may be signed in on several brains, and it has
            // to work on all of them. That the comparison is against an
            // authenticated account rather than a self-chosen tenant name is
            // what gives it any force at all.
            throw new KitException("kit '" + descriptor.getName() + "' is licensed to '"
                    + licensedTo + "', and this installation is "
                    + (linkedAccount == null
                            ? "not signed in to any store account"
                            : "signed in as '" + linkedAccount + "'")
                    + ". A delivery is bound to the account it was made for.");
        }

        Instant expires = descriptor.getLicenseExpiresAt();
        if (expires != null && expires.isBefore(now)) {
            // Noted, not enforced — and that is a decision, not an omission.
            //
            // Expiry ends entitlement to versions published *after* it, and
            // the library is the side that can tell: it knows publication
            // dates, this side sees only a date in a file. It deliberately
            // keeps serving what was already paid for, so that reinstalling a
            // machine does not cost anyone a version they may run. Refusing
            // here would overrule that with less information and make the
            // library's own guarantee unreachable — an HTTP 200 download that
            // cannot be installed.
            //
            // What a lapsed licence does mean is visible in the UI, on the
            // kit card, where it belongs.
            log.info("KitLicenseGate: licence for '{}' expired on {} — installing anyway,"
                            + " the library decides entitlement and it served this version",
                    descriptor.getName(), expires);
        }
    }
}
