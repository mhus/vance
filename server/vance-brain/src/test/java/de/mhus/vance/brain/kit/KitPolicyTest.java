package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.kit.KitPolicyAction;
import de.mhus.vance.api.kit.KitPolicyDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.kit.KitPolicyRuleDto;
import de.mhus.vance.shared.kit.KitHash;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Policy resolution and the write decision — spec:
 * {@code planning/kit-installed-multi.md} §5, §D7.
 */
class KitPolicyTest {

    private static final String HASH_INSTALLED = KitHash.of("as installed");
    private static final String HASH_EDITED = KitHash.of("edited by the user");
    private static final String HASH_INCOMING = KitHash.of("the new kit version");

    // ── rule matching ────────────────────────────────────────────────

    @Test
    void forDocument_noRules_usesDefault() {
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE).build());
        assertThat(policy.forDocument("recipes/analyze.yaml")).isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void defaults_withoutConfigDocument_isKeep() {
        assertThat(KitPolicy.defaults().forDocument("anything.md"))
                .isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void forDocument_lastMatchingRuleWins() {
        // The list reads top-down: default, then exceptions, then
        // exceptions to the exception.
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(
                        documentRule("recipes/*.yaml", KitPolicyAction.KEEP),
                        documentRule("recipes/analyze.yaml", KitPolicyAction.IGNORE)))
                .build());
        assertThat(policy.forDocument("recipes/analyze.yaml")).isEqualTo(KitPolicyAction.IGNORE);
        assertThat(policy.forDocument("recipes/other.yaml")).isEqualTo(KitPolicyAction.KEEP);
        assertThat(policy.forDocument("onboarding.md")).isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void forDocument_singleStarDoesNotCrossPathSegments() {
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(documentRule("skills/*", KitPolicyAction.IGNORE)))
                .build());
        assertThat(policy.forDocument("skills/cve.md")).isEqualTo(KitPolicyAction.IGNORE);
        assertThat(policy.forDocument("skills/cve/SKILL.md")).isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void forDocument_doubleStarCrossesPathSegments() {
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(documentRule("skills/**", KitPolicyAction.IGNORE)))
                .build());
        assertThat(policy.forDocument("skills/cve/SKILL.md")).isEqualTo(KitPolicyAction.IGNORE);
    }

    @Test
    void forSetting_starSpansDots() {
        // Setting keys are flat identifiers that happen to contain dots.
        // `ai.alias.*` obviously means "every alias", including the deep ones.
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(settingRule("ai.alias.*", KitPolicyAction.KEEP)))
                .build());
        assertThat(policy.forSetting("ai.alias.default.fast")).isEqualTo(KitPolicyAction.KEEP);
        assertThat(policy.forSetting("tracing.llm")).isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void forDocument_settingRulesDoNotLeakIntoDocuments() {
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(settingRule("*", KitPolicyAction.IGNORE)))
                .build());
        assertThat(policy.forDocument("onboarding.md")).isEqualTo(KitPolicyAction.OVERWRITE);
    }

    // ── write decision ───────────────────────────────────────────────

    @Test
    void decide_keep_absentArtefact_writes() {
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, false, null, null, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_keep_untouchedArtefact_writes() {
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true,
                HASH_INSTALLED, HASH_INSTALLED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_keep_userEditedArtefact_skips() {
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true,
                HASH_INSTALLED, HASH_EDITED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.SKIP_MODIFIED);
    }

    @Test
    void decide_keep_artefactWeNeverInstalled_skips() {
        // No recorded hash means we cannot prove it is ours — it may
        // belong to the user or to another kit. Not overwriting is the
        // recoverable direction.
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true, null, HASH_EDITED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.SKIP_MODIFIED);
    }

    @Test
    void decide_keep_untrackedButIdenticalToIncoming_writes() {
        // Nothing to protect: what is on disk is exactly what the kit
        // would write. Without this the artefact could never be claimed
        // back — an update that stopped shipping it drops it from the
        // record, and re-shipping it would then be refused forever.
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true, null, HASH_INCOMING, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_keep_encryptedSettingWithoutComparableHash_skips() {
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true, HASH_INSTALLED, null, null))
                .isEqualTo(KitPolicy.Decision.SKIP_MODIFIED);
    }

    @Test
    void decide_overwrite_userEditedArtefact_writesAnyway() {
        assertThat(KitPolicy.decide(KitPolicyAction.OVERWRITE, true,
                HASH_INSTALLED, HASH_EDITED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_ignore_untouchedArtefact_stillSkips() {
        // The difference to keep: ignore freezes, it does not merely protect.
        assertThat(KitPolicy.decide(KitPolicyAction.IGNORE, true,
                HASH_INSTALLED, HASH_INSTALLED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.SKIP_IGNORED);
    }

    @Test
    void decide_ignore_identicalToIncoming_stillSkips() {
        // Even the no-op shortcut does not apply — ignore means the kit
        // stops touching this artefact, full stop.
        assertThat(KitPolicy.decide(KitPolicyAction.IGNORE, true,
                null, HASH_INCOMING, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.SKIP_IGNORED);
    }

    @Test
    void decide_merge_userEditedArtefact_asksForMerge() {
        assertThat(KitPolicy.decide(KitPolicyAction.MERGE, true,
                HASH_INSTALLED, HASH_EDITED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.MERGE);
    }

    @Test
    void decide_merge_untouchedArtefact_writesWithoutMerging() {
        // Nothing diverged, so there is nothing to reconcile — merge only
        // differs from keep once the local copy actually moved.
        assertThat(KitPolicy.decide(KitPolicyAction.MERGE, true,
                HASH_INSTALLED, HASH_INSTALLED, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_merge_absentArtefact_writes() {
        assertThat(KitPolicy.decide(KitPolicyAction.MERGE, false, null, null, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_ignore_absentArtefact_skips() {
        assertThat(KitPolicy.decide(KitPolicyAction.IGNORE, false, null, null, HASH_INCOMING))
                .isEqualTo(KitPolicy.Decision.SKIP_IGNORED);
    }

    // ── policy cascade ───────────────────────────────────────────────

    @Test
    void of_withoutUserConfig_usesTheKitsSuggestion() {
        KitPolicyDto suggested = KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(settingRule("ai.alias.*", KitPolicyAction.IGNORE)))
                .build();

        KitPolicy policy = KitPolicy.of(null, suggested);

        assertThat(policy.forDocument("anything.md")).isEqualTo(KitPolicyAction.OVERWRITE);
        assertThat(policy.forSetting("ai.alias.default.fast")).isEqualTo(KitPolicyAction.IGNORE);
    }

    @Test
    void of_userConfigWins_overTheKitsSuggestion() {
        // Whole-policy precedence: someone who writes a policy has thought
        // about this kit, and mixing in the author's rules would produce
        // behaviour neither of them wrote down.
        KitPolicyDto user = KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.KEEP).build();
        KitPolicyDto suggested = KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE)
                .rules(List.of(settingRule("ai.alias.*", KitPolicyAction.IGNORE)))
                .build();

        KitPolicy policy = KitPolicy.of(user, suggested);

        assertThat(policy.forDocument("anything.md")).isEqualTo(KitPolicyAction.KEEP);
        assertThat(policy.forSetting("ai.alias.default.fast"))
                .as("the author's exception does not survive an explicit user policy")
                .isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void of_neitherSide_fallsBackToKeep() {
        assertThat(KitPolicy.of(null, null).forDocument("anything.md"))
                .isEqualTo(KitPolicyAction.KEEP);
    }

    // ── sibling-kit ownership ────────────────────────────────────────

    @Test
    void decide_keep_contentBelongsToAnotherInstalledKit_writes() {
        // With several kits, "not what I installed" is not the same as
        // "the user edited it" — a sibling kit higher in the layer order
        // may have written it, and freezing that would be wrong.
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true,
                HASH_INSTALLED, HASH_EDITED, HASH_INCOMING, java.util.Set.of(HASH_EDITED)))
                .isEqualTo(KitPolicy.Decision.WRITE);
    }

    @Test
    void decide_keep_contentMatchesNoKit_isStillTreatedAsUserEdit() {
        assertThat(KitPolicy.decide(KitPolicyAction.KEEP, true,
                HASH_INSTALLED, HASH_EDITED, HASH_INCOMING, java.util.Set.of(HASH_INSTALLED)))
                .isEqualTo(KitPolicy.Decision.SKIP_MODIFIED);
    }

    @Test
    void decide_ignore_contentBelongsToAnotherKit_stillSkips() {
        // ignore means "this kit stops touching the artefact" — who wrote
        // what is there now does not enter into it.
        assertThat(KitPolicy.decide(KitPolicyAction.IGNORE, true,
                HASH_INSTALLED, HASH_EDITED, HASH_INCOMING, java.util.Set.of(HASH_EDITED)))
                .isEqualTo(KitPolicy.Decision.SKIP_IGNORED);
    }

    private static KitPolicyRuleDto documentRule(String glob, KitPolicyAction action) {
        return KitPolicyRuleDto.builder().document(glob).action(action).build();
    }

    private static KitPolicyRuleDto settingRule(String glob, KitPolicyAction action) {
        return KitPolicyRuleDto.builder().setting(glob).action(action).build();
    }

    // ──────────────────── the credential gate ────────────────────

    @Test
    void forSecret_gateClosed_isKeepEvenWhenTheDefaultSaysOverwrite() {
        // The whole reason the gate is a separate switch. ODE sources already
        // default to overwrite for documents, and an operator who writes
        // `policy: overwrite` means their files — not "reset every credential
        // on every update".
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .defaultAction(KitPolicyAction.OVERWRITE).build());

        assertThat(policy.forSecret("hrafnagud.mount.apiKey"))
                .isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void forSecret_gateClosed_isKeepEvenWhenARuleMatchesTheKey() {
        // A rule aimed at a family of settings must not reach a secret in
        // that family by accident. Closed means closed; the list is not
        // consulted at all.
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .rules(List.of(settingRule("hrafnagud.*", KitPolicyAction.OVERWRITE)))
                .build());

        assertThat(policy.forSecret("hrafnagud.mount.apiKey"))
                .isEqualTo(KitPolicyAction.KEEP);
        // Same key, asked as an ordinary setting: there the rule does apply.
        assertThat(policy.forSetting("hrafnagud.mount.apiKey"))
                .isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void forSecret_gateOpen_isOverwriteWithoutAnyRule() {
        // One knob for the case it exists for: a host rotated its own keys.
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder().build())
                .withSecretsReplaceable(true);

        assertThat(policy.forSecret("hrafnagud.mount.apiKey"))
                .isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void forSecret_gateOpen_canBeNarrowedPerKey() {
        // The gate opens, the rules refine downward — so one credential that
        // was set by hand can be frozen while the rest follow the host.
        KitPolicy policy = KitPolicy.of(KitPolicyDto.builder()
                .rules(List.of(settingRule("kit.token.*", KitPolicyAction.IGNORE)))
                .build())
                .withSecretsReplaceable(true);

        assertThat(policy.forSecret("hrafnagud.mount.apiKey"))
                .isEqualTo(KitPolicyAction.OVERWRITE);
        assertThat(policy.forSecret("kit.token.hrafnagud"))
                .isEqualTo(KitPolicyAction.IGNORE);
    }

    // ──────────────────── defaults by fetch mechanism ────────────────────

    @Test
    void odeSource_overwritesDocuments() {
        // The host assembles the bundle; publishing a new revision is how it
        // says something changed. keep would fetch the change and discard it.
        assertThat(KitPolicy.defaultsFor(KitSourceType.ODE).forDocument("recipes/x.yaml"))
                .isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void odeSource_keepsSettings() {
        // A setting is what somebody configured for their installation. The
        // first live run skipped centauri.endpoint.<id>.baseUrl for exactly
        // this reason and was right to.
        assertThat(KitPolicy.defaultsFor(KitSourceType.ODE).forSetting("centauri.x.baseUrl"))
                .isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void gitSource_keepsBoth() {
        KitPolicy policy = KitPolicy.defaultsFor(KitSourceType.GIT);
        assertThat(policy.forDocument("recipes/x.yaml")).isEqualTo(KitPolicyAction.KEEP);
        assertThat(policy.forSetting("a.b")).isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void unknownSource_keepsBoth() {
        KitPolicy policy = KitPolicy.defaultsFor(null);
        assertThat(policy.forDocument("recipes/x.yaml")).isEqualTo(KitPolicyAction.KEEP);
        assertThat(policy.forSetting("a.b")).isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void writtenPolicy_appliesToBothClasses() {
        // Somebody who writes a default: has thought about this kit — the
        // split exists only for the case where nobody did.
        KitPolicy policy = KitPolicy.of(
                KitPolicyDto.builder().defaultAction(KitPolicyAction.OVERWRITE).build(),
                null, KitSourceType.ODE);
        assertThat(policy.forDocument("x.md")).isEqualTo(KitPolicyAction.OVERWRITE);
        assertThat(policy.forSetting("a.b")).isEqualTo(KitPolicyAction.OVERWRITE);
    }

    @Test
    void writtenKeep_beatsTheOdeDocumentDefault() {
        KitPolicy policy = KitPolicy.of(
                KitPolicyDto.builder().defaultAction(KitPolicyAction.KEEP).build(),
                null, KitSourceType.ODE);
        assertThat(policy.forDocument("x.md")).isEqualTo(KitPolicyAction.KEEP);
    }
}
