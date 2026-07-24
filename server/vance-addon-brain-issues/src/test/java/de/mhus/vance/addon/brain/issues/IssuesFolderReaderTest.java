package de.mhus.vance.addon.brain.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/** Pure helpers of {@link IssuesFolderReader}. */
class IssuesFolderReaderTest {

    @Test
    void slugify_normalises() {
        assertThat(IssuesFolderReader.slugify("Login NPE on empty password!"))
                .isEqualTo("login-npe-on-empty-password");
        assertThat(IssuesFolderReader.slugify("  ")).isEmpty();
    }

    @Test
    void humanise_dropsLeadingNumberPrefix() {
        assertThat(IssuesFolderReader.humanise("login-bug")).isEqualTo("Login bug");
    }

    @Test
    void normaliseFolder_stripsSlashes_rejectsEmpty() {
        assertThat(IssuesFolderReader.normaliseFolder("/issues/")).isEqualTo("issues");
        assertThatThrownBy(() -> IssuesFolderReader.normaliseFolder(" "))
                .isInstanceOf(ToolException.class);
    }
}
