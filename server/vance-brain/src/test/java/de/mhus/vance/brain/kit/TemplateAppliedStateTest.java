package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks down the audit-blob produced by
 * {@link TemplateApplier#buildAppliedState} that lands at
 * {@code _vance/tool-templates/&lt;name&gt;.applied.yaml} after each
 * apply. The hard invariant is: PASSWORD inputs are <em>never</em>
 * in this blob, regardless of how the rest of the descriptor looks.
 * Loosening that rule requires explicit code-review approval —
 * this test exists to make accidental relaxation fail loudly.
 */
class TemplateAppliedStateTest {

    private static final Instant FIXED = Instant.parse("2026-05-20T07:30:00Z");

    @Test
    void password_inputs_are_excluded_from_applied_state() {
        TemplateDescriptor descriptor = atlassianDescriptor();
        Map<String, String> sanitised = new LinkedHashMap<>();
        sanitised.put("clientId", "client-abc");
        sanitised.put("clientSecret", "super-secret-token-PLEASE-NEVER-LEAK"); // PASSWORD-typed input
        sanitised.put("features", "[\"jira\"]");

        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        for (TemplateInput in : descriptor.inputs()) byName.put(in.name(), in);

        Map<String, Object> state = TemplateApplier.buildAppliedState(
                descriptor, sanitised, byName, Map.of(), null, "wile.coyote", FIXED);

        // The hard invariant: secret must not appear anywhere in the blob.
        assertThat(state.toString()).doesNotContain("super-secret-token-PLEASE-NEVER-LEAK");
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) state.get("inputs");
        assertThat(inputs).doesNotContainKey("clientSecret");
        // Non-secret inputs are preserved.
        assertThat(inputs).containsEntry("clientId", "client-abc");
        assertThat(inputs.get("features")).isEqualTo(List.of("jira"));
    }

    @Test
    void records_template_name_actor_and_timestamp() {
        TemplateDescriptor descriptor = atlassianDescriptor();
        Map<String, String> sanitised = Map.of(
                "clientId", "x", "clientSecret", "y", "features", "[\"jira\"]");
        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        for (TemplateInput in : descriptor.inputs()) byName.put(in.name(), in);

        Map<String, Object> state = TemplateApplier.buildAppliedState(
                descriptor, sanitised, byName, Map.of(),
                "abc123-commit-sha", "wile.coyote", FIXED);

        assertThat(state.get("template")).isEqualTo("atlassian");
        assertThat(state.get("appliedAt")).isEqualTo("2026-05-20T07:30:00Z");
        assertThat(state.get("appliedBy")).isEqualTo("wile.coyote");
        assertThat(state.get("sourceCommit")).isEqualTo("abc123-commit-sha");
    }

    @Test
    void multiselect_lands_as_yaml_list_not_json_string() {
        TemplateDescriptor descriptor = atlassianDescriptor();
        Map<String, String> sanitised = Map.of(
                "clientId", "x",
                "clientSecret", "y",
                "features", "[\"jira\",\"confluence\"]");
        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        for (TemplateInput in : descriptor.inputs()) byName.put(in.name(), in);

        Map<String, Object> state = TemplateApplier.buildAppliedState(
                descriptor, sanitised, byName, Map.of(), null, null, FIXED);

        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) state.get("inputs");
        assertThat(inputs.get("features")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> features = (List<String>) inputs.get("features");
        assertThat(features).containsExactly("jira", "confluence");
        // Mirrored at top-level for read-back convenience.
        @SuppressWarnings("unchecked")
        List<String> topFeatures = (List<String>) state.get("features");
        assertThat(topFeatures).containsExactly("jira", "confluence");
    }

    @Test
    void derived_values_mirror_into_state() {
        TemplateDescriptor descriptor = atlassianDescriptor();
        Map<String, String> sanitised = Map.of(
                "clientId", "x", "clientSecret", "y", "features", "[\"jira\"]");
        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        for (TemplateInput in : descriptor.inputs()) byName.put(in.name(), in);

        Map<String, List<String>> derived = Map.of(
                "oauth_scopes", List.of("read:me", "offline_access", "read:jira-work"));

        Map<String, Object> state = TemplateApplier.buildAppliedState(
                descriptor, sanitised, byName, derived, null, null, FIXED);

        @SuppressWarnings("unchecked")
        Map<String, Object> derivedOut = (Map<String, Object>) state.get("derived");
        assertThat(derivedOut.get("oauth_scopes"))
                .isEqualTo(List.of("read:me", "offline_access", "read:jira-work"));
    }

    @Test
    void omits_inputs_not_supplied_by_user() {
        TemplateDescriptor descriptor = atlassianDescriptor();
        // Only the multiselect is supplied — clientId / clientSecret absent.
        Map<String, String> sanitised = Map.of("features", "[\"jira\"]");
        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        for (TemplateInput in : descriptor.inputs()) byName.put(in.name(), in);

        Map<String, Object> state = TemplateApplier.buildAppliedState(
                descriptor, sanitised, byName, Map.of(), null, null, FIXED);

        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) state.get("inputs");
        assertThat(inputs).doesNotContainKeys("clientId", "clientSecret");
        assertThat(inputs).containsKey("features");
    }

    /** Reusable fixture mirroring a real-world Atlassian-style template. */
    private static TemplateDescriptor atlassianDescriptor() {
        List<TemplateInput> inputs = new ArrayList<>();
        inputs.add(new TemplateInput(
                "clientId", TemplateInputType.STRING, "OAuth Client ID",
                null, true, null, List.of(),
                TemplateInputTarget.documentInline()));
        inputs.add(new TemplateInput(
                "clientSecret", TemplateInputType.PASSWORD, "OAuth Client Secret",
                null, true, null, List.of(),
                new TemplateInputTarget(
                        TemplateInputTarget.Kind.SETTING,
                        TemplateInputTarget.Scope.TENANT,
                        null,
                        "oauth.atlassian.client_secret")));
        inputs.add(new TemplateInput(
                "features", TemplateInputType.MULTI_SELECT, "Products",
                null, true, null,
                List.of(
                        new TemplateChoice("jira", "Jira", true),
                        new TemplateChoice("confluence", "Confluence", false)),
                TemplateInputTarget.documentInline()));
        return new TemplateDescriptor(
                "atlassian", "Atlassian", null, null, inputs,
                List.of(), List.of(), null);
    }
}
