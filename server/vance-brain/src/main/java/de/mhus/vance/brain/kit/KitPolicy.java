package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitPolicyAction;
import de.mhus.vance.api.kit.KitPolicyDto;
import de.mhus.vance.api.kit.KitPolicyRuleDto;
import de.mhus.vance.api.kit.KitSourceType;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resolved update policy of one installed kit — the rule list from the
 * user's config document, ready to answer "may I write this artefact".
 *
 * <p>Applies to tracked installs only. {@code apply} has no record and
 * therefore no hashes to compare against, so it keeps overwriting
 * unconditionally; a splat that silently skipped half its files because
 * of an invisible policy would simply be broken.
 *
 * <p>Spec: {@code planning/kit-installed-multi.md} §5.
 */
public final class KitPolicy {

    /** What the installer should do with one artefact. */
    public enum Decision {
        /** Write it. */
        WRITE,
        /** Leave it — the user changed it since the install ({@code keep}). */
        SKIP_MODIFIED,
        /** Leave it — the artefact is frozen ({@code ignore}). */
        SKIP_IGNORED,
        /**
         * The user changed it and the policy says reconcile rather than
         * step aside ({@code merge}). The installer attempts a three-way
         * merge and falls back to {@link #SKIP_MODIFIED} when it has no
         * common ancestor to merge against.
         */
        MERGE
    }

    /**
     * Two defaults, because documents and settings are different kinds of
     * thing. A document in a kit is <em>delivered material</em> — the author
     * ships a newer one and means it. A setting is a <em>configured value</em>,
     * and the person who configured it is usually right about their own
     * installation.
     *
     * <p>They are only ever different when nobody wrote a policy: an explicit
     * {@code default:} from the user or the kit author applies to both, because
     * somebody who writes one has thought about this kit.
     */
    private final KitPolicyAction documentDefault;
    private final KitPolicyAction settingDefault;
    private final List<KitPolicyRuleDto> rules;

    /**
     * Whether an existing credential may be replaced at all — the gate
     * behind {@code overwriteSecrets} in the config document.
     *
     * <p>Not a third default action but a switch, because the two states
     * are not symmetric. Closed, <em>nothing</em> replaces a credential and
     * the rule list is not consulted: an {@code overwrite} rule someone
     * wrote for a nearby setting key must not reach a secret by accident.
     * Open, the rules apply as usual and can only narrow.
     */
    private final boolean secretsReplaceable;

    private KitPolicy(
            KitPolicyAction documentDefault,
            KitPolicyAction settingDefault,
            List<KitPolicyRuleDto> rules) {
        this(documentDefault, settingDefault, rules, false);
    }

    private KitPolicy(
            KitPolicyAction documentDefault,
            KitPolicyAction settingDefault,
            List<KitPolicyRuleDto> rules,
            boolean secretsReplaceable) {
        this.documentDefault = documentDefault;
        this.settingDefault = settingDefault;
        this.rules = rules;
        this.secretsReplaceable = secretsReplaceable;
    }

    /**
     * The same policy with the credential gate opened.
     *
     * <p>Separate from the {@code of(...)} cascade on purpose: the gate has
     * no counterpart in {@code kit.yaml} and therefore nothing to cascade
     * with. It comes from the user's config document or not at all.
     */
    public KitPolicy withSecretsReplaceable(boolean replaceable) {
        return new KitPolicy(documentDefault, settingDefault, rules, replaceable);
    }

    public static KitPolicy of(@Nullable KitPolicyDto dto) {
        if (dto == null) return defaults();
        KitPolicyAction written =
                dto.getDefaultAction() == null ? KitPolicyAction.KEEP : dto.getDefaultAction();
        return new KitPolicy(written, written,
                dto.getRules() == null ? List.of() : List.copyOf(dto.getRules()));
    }

    /**
     * Resolve the policy cascade: what the user configured, else what the
     * kit's author suggests, else the default.
     *
     * <p>Whole-policy precedence rather than a per-rule merge. A user who
     * writes a policy has thought about this kit, and silently mixing the
     * author's rules into theirs would produce behaviour neither of them
     * wrote down.
     */
    public static KitPolicy of(
            @Nullable KitPolicyDto userConfigured, @Nullable KitPolicyDto kitSuggested) {
        return of(userConfigured, kitSuggested, null);
    }

    /**
     * Same cascade, with the fetch mechanism as the floor: user config, else
     * the kit author's suggestion, else what that kind of source implies
     * ({@link #defaultsFor}).
     */
    public static KitPolicy of(
            @Nullable KitPolicyDto userConfigured,
            @Nullable KitPolicyDto kitSuggested,
            @Nullable KitSourceType sourceType) {
        KitPolicyDto written = userConfigured != null ? userConfigured : kitSuggested;
        return written != null ? of(written) : defaultsFor(sourceType);
    }

    /** The policy in force when a kit has no config document — the common case. */
    public static KitPolicy defaults() {
        return new KitPolicy(KitPolicyAction.KEEP, KitPolicyAction.KEEP, List.of());
    }

    /**
     * What applies when nobody wrote a policy, given how the kit was fetched.
     *
     * <p>{@code ODE} sources get {@code overwrite} for <b>documents</b>: the
     * host assembles the bundle and publishing a new revision is how it says
     * something changed. Leaving those at {@code keep} would mean fetching a
     * change and then discarding it — the mechanism would look like it works
     * and quietly do nothing.
     *
     * <p>Their <b>settings</b> stay {@code keep}, and that is not timidity. A
     * setting is what somebody configured for their installation; the first
     * live run of this feature skipped
     * {@code centauri.endpoint.<id>.baseUrl} for exactly that reason and was
     * right to. Credentials are protected one step further still — see
     * {@link #forSecret}, which answers {@code keep} for every key until the
     * config document opens the gate, whatever the rules say.
     */
    public static KitPolicy defaultsFor(@Nullable KitSourceType type) {
        return type == KitSourceType.ODE
                ? new KitPolicy(KitPolicyAction.OVERWRITE, KitPolicyAction.KEEP, List.of())
                : defaults();
    }

    /**
     * Action for a document path. Last matching rule wins, so the list
     * reads top-down from general to specific.
     */
    public KitPolicyAction forDocument(String path) {
        KitPolicyAction action = documentDefault;
        for (KitPolicyRuleDto rule : rules) {
            if (rule.getDocument() == null) continue;
            if (KitGlob.matchesPath(rule.getDocument(), path)) action = rule.getAction();
        }
        return action;
    }

    /** Action for a project setting key. Last matching rule wins. */
    public KitPolicyAction forSetting(String key) {
        KitPolicyAction action = settingDefault;
        for (KitPolicyRuleDto rule : rules) {
            if (rule.getSetting() == null) continue;
            if (KitGlob.matchesKey(rule.getSetting(), key)) action = rule.getAction();
        }
        return action;
    }

    /**
     * Action for a setting key of an <b>encrypted</b> type.
     *
     * <p>Its own method rather than a branch inside {@link #forSetting},
     * because the question is a different one. For an ordinary setting the
     * policy compares hashes and decides who last wrote the value; for a
     * credential there is no hash, so the only honest answer is whatever
     * the operator wrote down in advance.
     *
     * <p>With the gate closed this is {@code keep} unconditionally — the
     * rule list is deliberately not consulted, so the answer cannot be
     * changed by a glob aimed at something else. With it open the rules
     * apply as usual and can freeze individual keys again.
     *
     * <p>Note what this never returns on its own: {@code overwrite} for a
     * credential the project does not have yet is not needed — a setting
     * that does not exist is written regardless of action.
     */
    public KitPolicyAction forSecret(String key) {
        if (!secretsReplaceable) return KitPolicyAction.KEEP;
        KitPolicyAction action = KitPolicyAction.OVERWRITE;
        for (KitPolicyRuleDto rule : rules) {
            if (rule.getSetting() == null) continue;
            if (KitGlob.matchesKey(rule.getSetting(), key)) action = rule.getAction();
        }
        return action;
    }

    /**
     * Turn an action plus the artefact's current state into a decision.
     *
     * @param action the action this artefact resolved to
     * @param exists whether the artefact is present in the project
     * @param recordedHash hash stored at install time; null when the kit
     *        never installed this artefact, or when it is an encrypted
     *        setting whose ciphertext cannot be compared
     * @param currentHash hash of what is in the project right now; null
     *        when it cannot be computed (same reason)
     * @param incomingHash hash of what the kit wants to write; null when
     *        it cannot be computed
     * @return what the installer should do
     */
    public static Decision decide(KitPolicyAction action, boolean exists,
            @Nullable String recordedHash, @Nullable String currentHash,
            @Nullable String incomingHash) {
        return decide(action, exists, recordedHash, currentHash, incomingHash, Set.of());
    }

    /**
     * As above, but told which hashes other installed kits recorded.
     *
     * <p>This is what keeps "the user edited it" apart from "another kit
     * wrote it". With several kits in a project the two look identical
     * from one kit's record — the content simply is not what that kit
     * installed — and treating a sibling kit's file as a precious user
     * edit would freeze it forever.
     *
     * @param otherKitHashes hashes recorded by the project's other install
     *        records for this same artefact
     */
    public static Decision decide(KitPolicyAction action, boolean exists,
            @Nullable String recordedHash, @Nullable String currentHash,
            @Nullable String incomingHash, Set<String> otherKitHashes) {
        if (action == KitPolicyAction.IGNORE) return Decision.SKIP_IGNORED;
        if (action == KitPolicyAction.OVERWRITE) return Decision.WRITE;
        // KEEP and MERGE agree on everything except what to do about a
        // divergent local edit — so share the path up to that point.
        if (!exists) return Decision.WRITE;
        // Already exactly what the kit would write: there is nothing to
        // protect, so this is not a conflict. Writing is a content no-op and
        // re-establishes ownership — without this, an artefact that dropped
        // out of the record (an update that no longer shipped it, an
        // uninstall without prune) could never be claimed back even though
        // it is byte-identical to the kit's version.
        if (currentHash != null && currentHash.equals(incomingHash)) return Decision.WRITE;
        // Not ours, but demonstrably some other kit's — nothing of the
        // user's is at stake, so the layer order decides, and the layer
        // order already put us here.
        if (currentHash != null && otherKitHashes.contains(currentHash)) return Decision.WRITE;
        // No hash to compare means we cannot prove the artefact is still
        // ours: either another kit or the user created it, or it is an
        // encrypted setting. Not overwriting is the recoverable direction.
        if (recordedHash == null || currentHash == null) return diverged(action);
        return recordedHash.equals(currentHash) ? Decision.WRITE : diverged(action);
    }

    /** What to do once we know the local copy diverged from what we installed. */
    private static Decision diverged(KitPolicyAction action) {
        return action == KitPolicyAction.MERGE ? Decision.MERGE : Decision.SKIP_MODIFIED;
    }
}
