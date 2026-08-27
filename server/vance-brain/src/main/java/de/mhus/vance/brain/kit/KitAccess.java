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
 * @param installId id of the install record this fetch refreshes, or
 *        null on first contact. Resolvable only from a <em>previous</em>
 *        installation: the id of a new one is derived from the kit name
 *        in the descriptor, which is exactly what is about to be
 *        downloaded — so „the id of this install" is circular and „the id
 *        of the last one" is the honest field. Its absence therefore
 *        means „never installed here", which is what a host wants to
 *        know.
 * @param actor username on whose behalf this fetch happens, or null for
 *        internal work with no person behind it (bootstrap, a scheduled
 *        reapply). Needed because one source type is <em>inside</em> this
 *        deployment: a {@link de.mhus.vance.api.kit.KitSourceType#PROJECT}
 *        source hands over another project's documents and settings, and
 *        whether that is allowed is a question about a person, not about a
 *        credential. Every other type answers it with a token.
 *
 *        <p>A null actor resolves to {@link
 *        de.mhus.vance.shared.permission.SecurityContext#SYSTEM} via
 *        {@code SecurityContextFactory.forToolSubject} — the established
 *        convention for exactly this case, and worth stating plainly: an
 *        internal path is trusted, so it may read any project. Nothing on a
 *        user-facing surface reaches the loader without an actor.
 * @param provisioningStamp opaque token describing what the source said
 *        it would hand over, folded together with the params it was asked
 *        for — stored on the record so a later check can ask „different
 *        now?" without downloading. Null on paths that have nothing to
 *        compare, which is most of them.
 * @param copySecrets whether the source may hand over the credentials its
 *        manifest declares. Only a {@link
 *        de.mhus.vance.api.kit.KitSourceType#PROJECT} source consults it —
 *        for every other type the answer is the vault passphrase, and there
 *        is nothing left to decide here.
 *
 *        <p><b>Default on</b>, which is the opposite of the project-copy
 *        default and deliberately so: a copy sweeps whatever a project
 *        happens to hold, while a kit manifest's {@code settings:} list is an
 *        author's explicit statement that this key is part of the kit. A kit
 *        that declares its SMTP password means to ship it, and dropping it
 *        silently leaves an installation that does not work with nobody able
 *        to say why.
 */
public record KitAccess(
        String tenantId,
        @Nullable String projectId,
        @Nullable String token,
        @Nullable String storeAccount,
        Map<String, Object> params,
        @Nullable String installId,
        @Nullable String provisioningStamp,
        @Nullable String actor,
        boolean copySecrets) {

    public KitAccess {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** For paths that never touch a remote source — export, local folders, tests. */
    public static KitAccess of(String tenantId) {
        return of(tenantId, null);
    }

    /**
     * A fetch for one project, with everything optional left out.
     *
     * <p>The canonical way to build one: the positional constructor has
     * grown past the point where a reader can tell the nullable strings
     * apart, and every new field would otherwise touch every call site.
     * Add what is needed with the {@code with…} methods.
     */
    public static KitAccess of(String tenantId, @Nullable String projectId) {
        return new KitAccess(tenantId, projectId, null, null, Map.of(), null, null, null, true);
    }

    /** Same access, different credential — used where a caller supplies its own token. */
    public KitAccess withToken(@Nullable String other) {
        return new KitAccess(tenantId, projectId, other, storeAccount, params, installId,
                provisioningStamp, actor, copySecrets);
    }

    /** Same access, aimed at a project — for callers that learn it separately. */
    public KitAccess forProject(@Nullable String other) {
        return new KitAccess(tenantId, other, token, storeAccount, params, installId,
                provisioningStamp, actor, copySecrets);
    }

    /** Same access, carrying what a provisioning entry asked for. */
    public KitAccess withParams(@Nullable Map<String, Object> other) {
        return new KitAccess(tenantId, projectId, token, storeAccount,
                other == null ? Map.of() : other, installId, provisioningStamp, actor,
                copySecrets);
    }

    /** Same access, told which existing installation it refreshes. */
    public KitAccess withInstallId(@Nullable String other) {
        return new KitAccess(tenantId, projectId, token, storeAccount, params, other,
                provisioningStamp, actor, copySecrets);
    }

    /** Same access, signed in to a store account. */
    public KitAccess withStoreAccount(@Nullable String other) {
        return new KitAccess(tenantId, projectId, token, other, params, installId,
                provisioningStamp, actor, copySecrets);
    }

    /** Same access, carrying what the record should remember for the next check. */
    public KitAccess withProvisioningStamp(@Nullable String other) {
        return new KitAccess(tenantId, projectId, token, storeAccount, params, installId, other,
                actor, copySecrets);
    }

    /** Same access, naming the person on whose behalf it happens. */
    public KitAccess withActor(@Nullable String other) {
        return new KitAccess(tenantId, projectId, token, storeAccount, params, installId,
                provisioningStamp, other, copySecrets);
    }

    /** Same access, told whether the source may hand over its credentials. */
    public KitAccess withCopySecrets(boolean other) {
        return new KitAccess(tenantId, projectId, token, storeAccount, params, installId,
                provisioningStamp, actor, other);
    }
}
