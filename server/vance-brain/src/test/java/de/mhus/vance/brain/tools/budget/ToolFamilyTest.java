package de.mhus.vance.brain.tools.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The family rule decides what the budget demotes as a unit, so it has to
 * be boringly predictable — no clever splitting, no surprises for names
 * that carry an underscore in the middle.
 */
class ToolFamilyTest {

    @Test
    void packSubTool_groupsUnderThePackPrefix() {
        assertThat(ToolFamily.of("slack_rest__conversations_history")).isEqualTo("slack_rest");
        assertThat(ToolFamily.of("intellij_mcp__get_file_problems")).isEqualTo("intellij_mcp");
    }

    @Test
    void builtinTool_groupsUnderItsFirstSegment() {
        assertThat(ToolFamily.of("doc_read")).isEqualTo("doc");
        assertThat(ToolFamily.of("work_file_write")).isEqualTo("work");
    }

    @Test
    void singleWordTool_isItsOwnFamily() {
        assertThat(ToolFamily.of("whoami")).isEqualTo("whoami");
    }

    @Test
    void blankName_mapsToPlaceholder_soGroupingNeedsNoNullCheck() {
        assertThat(ToolFamily.of("")).isEqualTo("?");
        assertThat(ToolFamily.of(null)).isEqualTo("?");
    }

    @Test
    void isPackTool_separatesGeneratedPacksFromHandWrittenTools() {
        assertThat(ToolFamily.isPackTool("gmail_rest__gmail_users_messages_list")).isTrue();
        assertThat(ToolFamily.isPackTool("doc_read")).isFalse();
        // Leading separator is not a pack prefix — there is no pack name.
        assertThat(ToolFamily.isPackTool("__weird")).isFalse();
    }
}
