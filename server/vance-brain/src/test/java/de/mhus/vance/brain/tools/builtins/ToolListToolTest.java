package de.mhus.vance.brain.tools.builtins;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.tools.builtins.ToolListTool.Entry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolListTool#buildListing} — the pure listing
 * step (prefix filter, bucket split, pack-hint dedup). Scoping to the
 * engine allow-set happens before this step and is covered by the
 * invoke-path integration in {@code qa/ai-test}.
 */
class ToolListToolTest {

    private static Entry ctx(String name) {
        return new Entry(name, true, "");
    }

    private static Entry avail(String name) {
        return new Entry(name, false, "");
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> out, String key) {
        return (List<String>) out.get(key);
    }

    @Test
    void splitsIntoInContextAndAvailable_sortedByName() {
        Map<String, Object> out = ToolListTool.buildListing(
                List.of(avail("workpage_create"), ctx("respond"), avail("doc_read"), ctx("answer")),
                null);

        assertThat(names(out, "inContext")).containsExactly("answer", "respond");
        assertThat(names(out, "available")).containsExactly("doc_read", "workpage_create");
        assertThat(out.get("count")).isEqualTo(4);
    }

    @Test
    void prefixFilter_isCaseInsensitiveAndAppliesToBothBuckets() {
        Map<String, Object> out = ToolListTool.buildListing(
                List.of(ctx("doc_write"), avail("doc_read"), avail("workpage_create")), "DOC_");

        assertThat(names(out, "inContext")).containsExactly("doc_write");
        assertThat(names(out, "available")).containsExactly("doc_read");
        assertThat(out.get("count")).isEqualTo(2);
    }

    @Test
    void prefixMatchesOnNamePrefixOnly_notSubstring() {
        // The old find_tools matched "read" anywhere in name/description;
        // tool_list is prefix-only — the model does the fuzzy part.
        Map<String, Object> out = ToolListTool.buildListing(List.of(avail("doc_read")), "read");

        assertThat(names(out, "available")).isEmpty();
        assertThat(out.get("count")).isEqualTo(0);
    }

    @Test
    void packHint_isEmittedOncePerPackPrefix() {
        Map<String, Object> out = ToolListTool.buildListing(
                List.of(
                        new Entry("jira_rest__searchIssues", false, "  cloudId is auto-injected  "),
                        new Entry("jira_rest__createIssue", false, "cloudId is auto-injected"),
                        new Entry("gmail_rest__messagesList", false, "scopes: gmail.readonly")),
                null);

        assertThat(out.get("packHints")).isEqualTo(Map.of(
                "gmail_rest", "scopes: gmail.readonly",
                "jira_rest", "cloudId is auto-injected"));
    }

    @Test
    void packHints_omittedWhenNoPackToolCarriesOne() {
        // Single-tool "packs" (no "__" in the name) don't get a heading —
        // their hint reaches the LLM through activePromptHints instead.
        Map<String, Object> out = ToolListTool.buildListing(
                List.of(new Entry("doc_read", false, "read before you write")), null);

        assertThat(out).doesNotContainKey("packHints");
    }
}
