package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.tools.budget.ToolBudget;
import de.mhus.vance.brain.tools.budget.ToolTriage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link ContextToolsApi#classify} — verifies the
 * primary/deferred bucket split that drives the per-turn tool manifest.
 *
 * <p>Apply order under test (§14.2):
 * Remove → Add (force-primary) → Defer (force-deferred). Tool's own
 * {@link de.mhus.vance.toolpack.Tool#deferred()} default is the
 * tie-breaker when no explicit add/defer applies.
 */
class ContextToolsApiClassifyTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant", "project", "session", "process", "user");

    @Test
    void emptyBase_returnsEmptyClassification() {
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of(), RecipeResolver.ToolFilter.EMPTY, Set.of());

        assertThat(c.allowed()).isEmpty();
        assertThat(c.primary()).isEmpty();
        assertThat(c.deferred()).isEmpty();
        assertThat(c.activatedDeferred()).isEmpty();
    }

    @Test
    void noFilter_classifiesByToolDeferredFlag() {
        stubResolve("primary_tool", false);
        stubResolve("deferred_tool", true);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("primary_tool", "deferred_tool"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of());

        assertThat(c.primary()).containsExactly("primary_tool");
        assertThat(c.deferred()).containsExactly("deferred_tool");
    }

    @Test
    void filterAdd_promotesDeferredDefaultToPrimary() {
        stubResolve("kit_install", true); // defaults to deferred
        stubResolve("doc_read", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("kit_install"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("kit_install", "doc_read"), filter, Set.of());

        assertThat(c.primary()).containsExactlyInAnyOrder("kit_install", "doc_read");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void filterDefer_demotesPrimaryDefaultToDeferred() {
        stubResolve("doc_read", false);
        stubResolve("doc_list", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of(), List.of("doc_read"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_read", "doc_list"), filter, Set.of());

        assertThat(c.primary()).containsExactly("doc_list");
        assertThat(c.deferred()).containsExactly("doc_read");
    }

    @Test
    void filterRemove_dropsTool_fromAllowedAndPrimary() {
        stubResolve("doc_edit", false);
        stubResolve("doc_read", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of("doc_edit"), List.of(), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_edit", "doc_read"), filter, Set.of());

        assertThat(c.allowed()).containsExactly("doc_read");
        assertThat(c.primary()).containsExactly("doc_read");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void addOverridesDefer_whenSameToolInBoth() {
        stubResolve("foo", false);
        // Explicit allowedToolsAdd wins so a recipe can defer a label
        // cluster (@side-effect) but still promote one tool by name.
        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("foo"), List.of("foo"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("foo"), filter, Set.of());

        assertThat(c.primary()).containsExactly("foo");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void activatedDeferred_isFiltered_toDeferredBucketIntersection() {
        stubResolve("kit_install", true);
        stubResolve("doc_read", false);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("kit_install", "doc_read"),
                RecipeResolver.ToolFilter.EMPTY,
                // Activations referencing tools not in the deferred bucket
                // are silently dropped — a stale activation for an
                // already-promoted tool shouldn't pollute the manifest.
                Set.of("kit_install", "doc_read", "stale_unknown"));

        assertThat(c.activatedDeferred()).containsExactly("kit_install");
    }

    @Test
    void visibleTools_areSortedAlphabetically_forCacheStability() {
        stubResolve("zeta", false);
        stubResolve("alpha", false);
        stubResolve("mu", true);
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("zeta"), resolved("alpha"), resolved("mu")));

        ContextToolsApi api = new ContextToolsApi(
                dispatcher, ctx,
                /*allowed*/ Set.of("zeta", "alpha", "mu"),
                /*primary*/ Set.of("zeta", "alpha"),
                /*deferred*/ Set.of("mu"),
                /*activatedDeferred*/ Set.of("mu"),
                ToolInvocationListener.NOOP);

        List<de.mhus.vance.api.tools.ToolSpec> visible = api.listPrimary();
        assertThat(visible).extracting("name")
                .containsExactly("alpha", "mu", "zeta");
    }

    @Test
    void profileGate_dropsToolWhenProfileNotAllowed() {
        stubResolve("client_file_read", false, Set.of("user", "mobile"));
        stubResolve("doc_read", false, Set.of()); // unrestricted

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("client_file_read", "doc_read"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of(),
                "eddie");

        assertThat(c.allowed()).containsExactly("doc_read");
        assertThat(c.primary()).containsExactly("doc_read");
    }

    @Test
    void profileGate_keepsToolWhenProfileAllowed() {
        stubResolve("client_file_read", false, Set.of("user", "mobile"));

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("client_file_read"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of(),
                "user");

        assertThat(c.primary()).containsExactly("client_file_read");
    }

    @Test
    void profileGate_emptyAllowedProfilesMeansUnrestricted() {
        stubResolve("doc_read", false, Set.of());

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_read"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of(),
                "eddie");

        assertThat(c.primary()).containsExactly("doc_read");
    }

    @Test
    void profileGate_nullProfileSkipsFilterEntirely() {
        // Legacy callers passing the 5-arg classify get null-profile;
        // even tools with a non-empty allowedForProfile must stay in.
        stubResolve("client_file_read", false, Set.of("user", "mobile"));

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("client_file_read"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of());

        assertThat(c.primary()).containsExactly("client_file_read");
    }

    // ─── capability floor (MANDATORY_TOOLS) ───

    @Test
    void mandatoryTools_membershipIsPinned() {
        // Extending the floor is a policy decision, not a drive-by edit:
        // every member is force-primary for every engine and cannot be
        // configured away. Change this list only deliberately.
        assertThat(ContextToolsApi.MANDATORY_TOOLS)
                .containsExactlyInAnyOrder("tool_list", "tool_description");
    }

    @Test
    void mandatoryTools_surviveFilterRemove() {
        stubResolve("tool_list", false);
        stubResolve("tool_description", false);
        stubResolve("doc_read", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of("tool_list", "tool_description"), List.of(), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("tool_list", "tool_description", "doc_read"), filter, Set.of());

        assertThat(c.allowed()).contains("tool_list", "tool_description");
        assertThat(c.primary()).contains("tool_list", "tool_description");
    }

    @Test
    void mandatoryTools_cannotBeDeferred() {
        stubResolve("tool_list", true); // even a deferred default is overridden
        stubResolve("tool_description", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of(), List.of("tool_list", "tool_description"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("tool_list", "tool_description"), filter, Set.of());

        assertThat(c.primary()).containsExactlyInAnyOrder("tool_list", "tool_description");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void mandatoryTools_areAddedToAnEngineBaseThatOmitsThem() {
        // The failure mode the floor exists for: a new engine's allow-set
        // forgets the discovery pair and the model quietly answers
        // "I can't do that" instead of looking.
        stubResolve("tool_list", false);
        stubResolve("tool_description", false);
        stubResolve("doc_write", false);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_write"),
                RecipeResolver.ToolFilter.EMPTY, Set.of());

        assertThat(c.allowed()).containsExactlyInAnyOrder(
                "doc_write", "tool_list", "tool_description");
        assertThat(c.primary()).contains("tool_list", "tool_description");
    }

    @Test
    void mandatoryTools_areSkippedWhenNotDispatchableInThisContext() {
        // Foot-side / stripped-down dispatchers may not carry them at
        // all — the floor must not invent names the dispatcher can't
        // resolve (that would hard-fail the manifest builder).
        stubResolve("doc_write", false);
        when(dispatcher.resolve(eq("tool_list"), any())).thenReturn(Optional.empty());
        when(dispatcher.resolve(eq("tool_description"), any())).thenReturn(Optional.empty());

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_write"),
                RecipeResolver.ToolFilter.EMPTY, Set.of());

        assertThat(c.allowed()).containsExactly("doc_write");
    }

    @Test
    void mandatoryTools_surviveTheProfileGate() {
        // A profile-gated floor tool would still be a configured-away
        // floor. Gate runs before, floor is applied after.
        stubResolve("tool_list", false, Set.of("user"));
        stubResolve("tool_description", false, Set.of("user"));

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("tool_list", "tool_description"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of(),
                "eddie");

        assertThat(c.primary()).containsExactlyInAnyOrder("tool_list", "tool_description");
    }

    // ─── per-turn additive allow (label selectors over client packs) ───

    @Test
    void filterAdd_admitsToolOutsideBase_keepingItsDeferredDefault() {
        // `@browser` expanded to a client-registered MCP pack tool: it
        // cannot be in the spawn-frozen base (session-scoped), so the add
        // list has to widen the allow-set. Its own defaultDeferred keeps
        // the 29-tool pack out of every turn's manifest.
        stubResolve("doc_read", false);
        stubResolve("chrome__navigate_page", true);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("chrome__navigate_page"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read"), filter, Set.of());

        assertThat(c.allowed()).contains("chrome__navigate_page");
        assertThat(c.deferred()).containsExactly("chrome__navigate_page");
        assertThat(c.primary()).contains("doc_read").doesNotContain("chrome__navigate_page");
    }

    @Test
    void filterAdd_admitsToolOutsideBase_asPrimaryWhenNotDeferredByDefault() {
        stubResolve("doc_read", false);
        stubResolve("chrome__take_snapshot", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("chrome__take_snapshot"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read"), filter, Set.of());

        assertThat(c.primary()).contains("chrome__take_snapshot");
    }

    @Test
    void filterAdd_ignoresNameTheDispatcherCannotResolve() {
        // Foot disconnected / pack unloaded: a stale expanded name must
        // not enter the allow-set, or the manifest builder hard-fails.
        stubResolve("doc_read", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("chrome__gone"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read"), filter, Set.of());

        assertThat(c.allowed()).doesNotContain("chrome__gone");
    }

    @Test
    void filterAdd_admittedTool_stillDroppedByRemove() {
        stubResolve("doc_read", false);
        stubResolve("chrome__navigate_page", true);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of("chrome__navigate_page"), List.of("chrome__navigate_page"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read"), filter, Set.of());

        assertThat(c.allowed()).doesNotContain("chrome__navigate_page");
    }

    @Test
    void filterAdd_admittedTool_stillSubjectToProfileGate() {
        // Widening the allow-set must not bypass the gates that run on
        // base — otherwise a recipe add could smuggle in a tool the
        // bound profile is not allowed to route.
        stubResolve("doc_read", false, Set.of());
        stubResolve("chrome__navigate_page", true, Set.of("user"));

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("chrome__navigate_page"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read"), filter, Set.of(), "eddie");

        assertThat(c.allowed()).doesNotContain("chrome__navigate_page");
    }

    @Test
    void filterAdd_onToolAlreadyInBase_stillForcesPrimary() {
        // Regression guard for the pre-existing meaning of add: promoting
        // an already-allowed deferred tool must keep working.
        stubResolve("kit_install", true);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("kit_install"), List.of("kit_install"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("kit_install"), filter, Set.of());

        assertThat(c.primary()).containsExactly("kit_install");
        assertThat(c.deferred()).isEmpty();
    }

    // ─── tool-surface budget (planning/tool-surface-budget.md) ───

    @Test
    void budget_demotesWholeFamiliesUntilTheSurfaceFits() {
        stubResolve("tool_list", false);
        stubResolve("tool_description", false);
        stubResolve("doc_read", false);
        stubResolve("slack_rest__a", false);
        stubResolve("slack_rest__b", false);

        // 4 slots for classified tools; the floor takes 2, doc_read fits,
        // the 2-tool pack does not.
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("tool_list", "tool_description", "doc_read",
                        "slack_rest__a", "slack_rest__b"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                new ToolBudget(5, 1), null);

        assertThat(c.primary()).containsExactlyInAnyOrder(
                "tool_list", "tool_description", "doc_read");
        assertThat(c.deferred()).containsExactlyInAnyOrder("slack_rest__a", "slack_rest__b");
        // Demotion never narrows what the engine may invoke — the tools
        // stay reachable via tool_list / a direct call.
        assertThat(c.allowed()).contains("slack_rest__a", "slack_rest__b");
    }

    @Test
    void budget_thatFits_changesNothing() {
        stubResolve("doc_read", false);
        stubResolve("doc_write", false);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read", "doc_write"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                new ToolBudget(50, 1), null);

        assertThat(c.primary()).containsExactlyInAnyOrder("doc_read", "doc_write");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void budget_neverDemotesTheMandatoryFloor() {
        stubResolve("tool_list", false);
        stubResolve("tool_description", false);
        stubResolve("doc_read", false);
        stubResolve("doc_write", false);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("tool_list", "tool_description", "doc_read", "doc_write"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                new ToolBudget(2, 0), null);

        assertThat(c.primary()).containsExactlyInAnyOrder("tool_list", "tool_description");
        assertThat(c.deferred()).contains("doc_read", "doc_write");
    }

    @Test
    void budget_honoursRecipeKeepAndDropFirst() {
        stubResolve("doc_read", false);
        stubResolve("slack_rest__a", false);

        // The recipe says the connector matters here and doc_* does not —
        // the derived order (built-ins over packs) is overruled.
        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of(), List.of(),
                List.of("slack_rest__a"), List.of("doc_read"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read", "slack_rest__a"),
                filter, Set.of(), null, null,
                new ToolBudget(1, 0), null);

        assertThat(c.primary()).containsExactly("slack_rest__a");
        assertThat(c.deferred()).containsExactly("doc_read");
    }

    @Test
    void budget_keepsAnActivatedDeferredToolOverAnUntouchedBuiltin() {
        stubResolve("doc_read", false);
        stubResolve("slack_rest__a", true);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read", "slack_rest__a"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of("slack_rest__a"), null, null,
                new ToolBudget(1, 0), null);

        assertThat(c.activatedDeferred()).containsExactly("slack_rest__a");
        assertThat(c.primary()).isEmpty();
    }

    @Test
    void budget_onUnrestrictedEngine_onlyMaterialisesWhenItOverflows() {
        // Ford-style: no allow-set, no filter. The cheap path (empty
        // classification, per-tool primary()) must survive an ample budget.
        // Two different families, so the "whole family or nothing" rule
        // still leaves exactly one survivor at a limit of one.
        when(dispatcher.resolvePrimary(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("whoami")));
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("whoami")));

        ContextToolsApi.Classification ample = ContextToolsApi.classify(
                dispatcher, ctx, Set.of(), RecipeResolver.ToolFilter.EMPTY,
                Set.of(), null, null, new ToolBudget(50, 1), null);

        assertThat(ample.primary()).isEmpty();
        assertThat(ample.deferred()).isEmpty();

        ContextToolsApi.Classification tight = ContextToolsApi.classify(
                dispatcher, ctx, Set.of(), RecipeResolver.ToolFilter.EMPTY,
                Set.of(), null, null, new ToolBudget(1, 0), null);

        assertThat(tight.primary()).hasSize(1);
        assertThat(tight.deferred()).hasSize(1);
    }

    @Test
    void budget_onUnrestrictedEngine_stillHonoursARankingOnlyRecipe() {
        // The exact combination a ranking-only recipe produces: no allow-set
        // (Ford-style engine) and no visibility overlay, but keep/dropFirst
        // set. Those carry no visibility effect by design, so this is the
        // *normal* shape for such a recipe — not an edge case. Ignoring the
        // hints here would silently discard the author's only statement
        // about what to give up, on the widest surface there is.
        when(dispatcher.resolvePrimary(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("slack_rest__a")));
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("slack_rest__a")));

        RecipeResolver.ToolFilter rankingOnly = new RecipeResolver.ToolFilter(
                List.of(), List.of(), List.of(),
                List.of("slack_rest__a"), List.of("doc_read"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of(), rankingOnly,
                Set.of(), null, null, new ToolBudget(1, 0), null);

        // Without the hints the derived order (built-in over pack) would
        // have kept doc_read instead.
        assertThat(c.primary()).containsExactly("slack_rest__a");
        assertThat(c.deferred()).containsExactly("doc_read");
    }

    @Test
    void budget_withoutALimit_behavesLikeNoBudget() {
        stubResolve("doc_read", false);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                ToolBudget.UNLIMITED, null);

        assertThat(c.primary()).containsExactly("doc_read");
    }

    @Test
    void budget_familyHintsFromDeploymentApply() {
        stubResolve("doc_read", false);
        stubResolve("gmail_rest__a", false);

        ToolTriage.Hints familyHints = new ToolTriage.Hints(
                Set.of(), Set.of(), Set.of(), Set.of("gmail_rest"), Set.of("doc"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("doc_read", "gmail_rest__a"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                new ToolBudget(1, 0), familyHints);

        assertThat(c.primary()).containsExactly("gmail_rest__a");
    }

    // ─── budget-aware withAdditional (skill tools after the cut) ───

    @Test
    void withAdditional_refitsToTheBudget_insteadOfOverflowing() {
        // Skill tools land in primary *after* classify already fitted the
        // surface. Without the re-fit the manifest would exceed the cap
        // and the provider would answer 400 — the very failure the budget
        // exists to prevent.
        stubResolve("doc_read", false);
        stubResolve("doc_write", false);
        stubResolve("skill_alpha", false);
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("doc_write"), resolved("skill_alpha")));

        ContextToolsApi base = new ContextToolsApi(
                dispatcher, ctx,
                /*allowed*/ Set.of("doc_read", "doc_write"),
                /*primary*/ Set.of("doc_read", "doc_write"),
                /*deferred*/ Set.of(),
                /*activatedDeferred*/ Set.of(),
                ToolInvocationListener.NOOP)
                .withBudget(new ToolBudget(2, 0), null);

        ContextToolsApi withSkill = base.withAdditional(Set.of("skill_alpha"));

        assertThat(withSkill.primary()).hasSizeLessThanOrEqualTo(2);
        // The skill's tool ranks as "keep" — it is explicitly active.
        assertThat(withSkill.primary()).contains("skill_alpha");
        // Nothing is lost: the displaced tool is still invocable.
        assertThat(withSkill.invocableToolNames()).contains("doc_read", "doc_write");
    }

    @Test
    void withAdditional_withoutABudget_keepsTheOldBehaviour() {
        stubResolve("doc_read", false);
        stubResolve("skill_alpha", false);

        ContextToolsApi base = new ContextToolsApi(
                dispatcher, ctx,
                Set.of("doc_read"), Set.of("doc_read"), Set.of(), Set.of(),
                ToolInvocationListener.NOOP);

        ContextToolsApi withSkill = base.withAdditional(Set.of("skill_alpha"));

        assertThat(withSkill.primary()).containsExactlyInAnyOrder("doc_read", "skill_alpha");
    }

    @Test
    void withAdditional_thatStillFits_demotesNothing() {
        stubResolve("doc_read", false);
        stubResolve("skill_alpha", false);

        ContextToolsApi base = new ContextToolsApi(
                dispatcher, ctx,
                Set.of("doc_read"), Set.of("doc_read"), Set.of(), Set.of(),
                ToolInvocationListener.NOOP)
                .withBudget(new ToolBudget(50, 1), null);

        ContextToolsApi withSkill = base.withAdditional(Set.of("skill_alpha"));

        assertThat(withSkill.primary()).containsExactlyInAnyOrder("doc_read", "skill_alpha");
        assertThat(withSkill.deferred()).isEmpty();
    }

    @Test
    void withBudget_isANoOpWithoutALimit() {
        stubResolve("doc_read", false);
        stubResolve("skill_alpha", false);

        ContextToolsApi base = new ContextToolsApi(
                dispatcher, ctx,
                Set.of("doc_read"), Set.of("doc_read"), Set.of(), Set.of(),
                ToolInvocationListener.NOOP)
                .withBudget(ToolBudget.UNLIMITED, null);

        assertThat(base.withAdditional(Set.of("skill_alpha")).primary())
                .containsExactlyInAnyOrder("doc_read", "skill_alpha");
    }

    // ─── the discovery block splits along the cache boundary ───

    @Test
    void budgetDemotedTools_areReportedSeparatelyFromTheRecipeDeferredOnes() {
        // Membership of the demoted set follows activation recency and
        // measured usage, so it moves between turns. The recipe-derived
        // deferred set does not. They have to be renderable apart, or the
        // volatile half busts the cache marker on the static prefix.
        stubResolve("doc_read", false);
        stubResolve("gmail_rest__a", false);
        stubResolve("always_deferred", true);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_read", "gmail_rest__a", "always_deferred"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                new ToolBudget(1, 0), null);

        assertThat(c.demoted()).containsExactly("gmail_rest__a");
        assertThat(c.deferred()).contains("always_deferred", "gmail_rest__a");
    }

    @Test
    void demotedTools_leaveTheCacheAnchoredDiscoveryBlock_butStayListed() {
        stubResolve("doc_read", false);
        stubResolve("gmail_rest__a", false);
        stubResolve("always_deferred", true);
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("gmail_rest__a"),
                resolved("always_deferred", true)));

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_read", "gmail_rest__a", "always_deferred"),
                RecipeResolver.ToolFilter.EMPTY, Set.of(), null, null,
                new ToolBudget(1, 0), null);
        ContextToolsApi api = new ContextToolsApi(
                dispatcher, ctx, c.allowed(), c.primary(), c.deferred(), c.activatedDeferred(),
                ToolInvocationListener.NOOP)
                .withBudget(new ToolBudget(1, 0), null, c.demoted());

        assertThat(api.discoveryBlockMarkdown())
                .contains("always_deferred")
                .doesNotContain("gmail_rest__a");
        // Nothing disappears — it just moves to the dynamic message.
        assertThat(api.demotedDiscoveryBlockMarkdown())
                .contains("gmail_rest__a")
                .doesNotContain("always_deferred");
    }

    @Test
    void withoutADemotion_theDynamicBlockIsEmpty() {
        stubResolve("deferred_tool", true);
        when(dispatcher.resolveAll(any())).thenReturn(List.of(resolved("deferred_tool", true)));

        ContextToolsApi api = new ContextToolsApi(
                dispatcher, ctx,
                Set.of("deferred_tool"), Set.of(), Set.of("deferred_tool"), Set.of(),
                ToolInvocationListener.NOOP);

        assertThat(api.discoveryBlockMarkdown()).contains("deferred_tool");
        assertThat(api.demotedDiscoveryBlockMarkdown()).isEmpty();
    }

    @Test
    void withAdditional_removesAnAlreadyActivatedExtra_fromTheDeferredBucket() {
        // Otherwise the tool sits in the manifest and in the discovery
        // block at once, and the triage counts it twice.
        stubResolve("doc_read", false);
        stubResolve("skill_alpha", true);
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("doc_read"), resolved("skill_alpha", true)));

        ContextToolsApi base = new ContextToolsApi(
                dispatcher, ctx,
                /*allowed*/ Set.of("doc_read", "skill_alpha"),
                /*primary*/ Set.of("doc_read"),
                /*deferred*/ Set.of("skill_alpha"),
                /*activatedDeferred*/ Set.of("skill_alpha"),
                ToolInvocationListener.NOOP)
                .withBudget(new ToolBudget(50, 1), null);

        ContextToolsApi withSkill = base.withAdditional(Set.of("skill_alpha"));

        assertThat(withSkill.primary()).contains("skill_alpha");
        assertThat(withSkill.deferred()).doesNotContain("skill_alpha");
        assertThat(withSkill.discoveryBlockMarkdown()).doesNotContain("skill_alpha");
    }

    private void stubResolve(String name, boolean deferred) {
        when(dispatcher.resolve(eq(name), any())).thenReturn(Optional.of(resolved(name, deferred)));
    }

    private void stubResolve(String name, boolean deferred, Set<String> allowedProfiles) {
        when(dispatcher.resolve(eq(name), any()))
                .thenReturn(Optional.of(resolved(name, deferred, allowedProfiles)));
    }

    private static ToolDispatcher.Resolved resolved(String name) {
        return resolved(name, false, Set.of());
    }

    private static ToolDispatcher.Resolved resolved(String name, boolean deferred) {
        return resolved(name, deferred, Set.of());
    }

    private static ToolDispatcher.Resolved resolved(String name, boolean deferred, Set<String> allowedProfiles) {
        Tool t = new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public boolean primary() { return true; }
            @Override public boolean deferred() { return deferred; }
            @Override public Set<String> allowedForProfile() { return allowedProfiles; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
                return Map.of();
            }
        };
        ToolSource src = new ToolSource() {
            @Override public String sourceId() { return "stub"; }
            @Override public List<Tool> tools(ToolInvocationContext c) { return List.of(t); }
            @Override public Optional<Tool> find(String n, ToolInvocationContext c) {
                return n.equals(name) ? Optional.of(t) : Optional.empty();
            }
        };
        return new ToolDispatcher.Resolved(t, src);
    }
}
