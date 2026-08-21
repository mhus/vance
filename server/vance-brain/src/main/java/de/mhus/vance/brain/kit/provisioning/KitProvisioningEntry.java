package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.api.kit.KitProvisioningAuthority;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One line of a project's {@code _vance/kits/provisioning.yaml}: a
 * mechanism, where it answers, and how much it is allowed to do.
 *
 * <p>Deliberately not a DTO in {@code vance-api}. {@link #token()} holds
 * a <b>resolved</b> secret — the document carries a
 * {@code {{secret:…}}} reference and this record carries the value — so
 * the type must not be reachable from anything that serialises itself to
 * a client. For the same reason it has no {@code toString}: a record's
 * generated one prints every component, and this one would print the
 * credential into whatever log first mentions the entry.
 *
 * @param type id of the {@link KitProvisioningHandler} that serves this
 *        entry — the open axis
 * @param url where that mechanism answers
 * @param token resolved credential, or null when the entry named none
 * @param authority what the source may do unattended
 * @param params what this project wants from the source, passed through
 *        to it verbatim — „the German variant with the invoicing
 *        module". Free-form because only the far end knows its own
 *        options, and open-ended for the same reason: it says <i>what</i>
 *        is wanted, whereas the identity a request carries (installation,
 *        tenant, project) says who and where and stays a closed set.
 *
 *        <p>Not secret-resolved. A {@code {{secret:…}}} here would be
 *        handed to a third party by convenience; the credential in
 *        {@link #token()} is meant for that party, an arbitrary vault
 *        value is not.
 */
public record KitProvisioningEntry(
        String type,
        String url,
        @Nullable String token,
        KitProvisioningAuthority authority,
        Map<String, Object> params) {

    public KitProvisioningEntry {
        params = params == null ? Map.of() : Map.copyOf(params);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("a provisioning entry needs a type");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("a provisioning entry needs a url");
        }
        if (authority == null) {
            authority = KitProvisioningAuthority.defaultLevel();
        }
    }

    /** Safe to log: names the entry without naming its credential. */
    @Override
    public String toString() {
        return "KitProvisioningEntry[type=" + type + ", url=" + url
                + ", authority=" + authority + ", token=" + (token == null ? "none" : "set")
                + ", params=" + params.keySet() + "]";
    }
}
