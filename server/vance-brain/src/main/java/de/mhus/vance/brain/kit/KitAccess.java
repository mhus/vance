package de.mhus.vance.brain.kit;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Who is fetching a kit, with what, and for what.
 *
 * <p>Replaces the bare {@code (tenantId, token)} pair that used to travel
 * through the resolve chain. It is one parameter fewer at every call site,
 * and it carries the piece that pair could not: which store account this
 * installation is signed in to.
 *
 * <p>That account is what {@link KitLicenseGate} compares a delivered
 * kit's {@code licensedTo} against. It is deliberately not the tenant —
 * a purchase belongs to a person, who may be signed in on several brains,
 * and a tenant name is a local label anyone can choose. Spec:
 * {@code planning/kit-store.md} §3 S2.
 *
 * @param tenantId whose source configuration applies — a url may be
 *        reachable for one tenant and unconfigured for another
 * @param projectId which project the kit is being fetched for, or null
 *        on paths that are not installing into one (export, tests). A
 *        source that assembles per project needs it, and a source that
 *        merely serves files can log it; either way it is part of „who
 *        is fetching" and not of the credential.
 * @param token credential for the fetch: a bearer token for a library, a
 *        personal access token for a private git repository
 * @param storeAccount the linked store account, or null when this
 *        installation is signed in to none
 * @param params what the operator asked this source for, from the
 *        provisioning entry — empty on a hand-typed install. Free-form
 *        because only the far end knows its own options; it is
 *        <b>configuration, not identity</b>, which is why it may grow
 *        while the identity field set stays closed. Sent to a third
 *        party, so {@code {{secret:…}}} references in it are
 *        deliberately <b>not</b> resolved: the credential is meant for
 *        that party, an arbitrary vault value is not.
 */
public record KitAccess(
        String tenantId,
        @Nullable String projectId,
        @Nullable String token,
        @Nullable String storeAccount,
        Map<String, Object> params) {

    public KitAccess {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** For paths that never touch a remote source — export, local folders, tests. */
    public static KitAccess of(String tenantId) {
        return new KitAccess(tenantId, null, null, null, Map.of());
    }

    /** Same access, different credential — used where a caller supplies its own token. */
    public KitAccess withToken(@Nullable String other) {
        return new KitAccess(tenantId, projectId, other, storeAccount, params);
    }

    /** Same access, aimed at a project — for callers that learn it separately. */
    public KitAccess forProject(@Nullable String other) {
        return new KitAccess(tenantId, other, token, storeAccount, params);
    }

    /** Same access, carrying what a provisioning entry asked for. */
    public KitAccess withParams(@Nullable Map<String, Object> other) {
        return new KitAccess(tenantId, projectId, token, storeAccount,
                other == null ? Map.of() : other);
    }
}
