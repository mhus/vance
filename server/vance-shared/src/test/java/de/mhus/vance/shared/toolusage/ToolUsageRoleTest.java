package de.mhus.vance.shared.toolusage;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.junit.jupiter.api.Test;

/**
 * The role key the demand counters are written and read under. It has a
 * single derivation site because three call sites depend on it — two
 * writers ({@code ThinkEngineService}, {@code ToolDescriptionTool}) and the
 * reader ({@code ToolBudgetService}). If they ever disagreed, the budget's
 * tie-break would silently read an empty bucket while the counters piled up
 * under a different key.
 */
class ToolUsageRoleTest {

    @Test
    void recipeName_isTheRole() {
        assertThat(ToolUsageService.roleOf(process("arthur", "arthur-engine")))
                .isEqualTo("arthur");
    }

    @Test
    void engineName_isUsedWhenNoRecipeIsSet() {
        assertThat(ToolUsageService.roleOf(process(null, "frankie"))).isEqualTo("frankie");
        assertThat(ToolUsageService.roleOf(process("  ", "frankie"))).isEqualTo("frankie");
    }

    @Test
    void surroundingWhitespaceIsStripped() {
        // The write path and the read path have to produce byte-identical
        // keys — an untrimmed name would key its own bucket.
        assertThat(ToolUsageService.roleOf(process(" coding ", null))).isEqualTo("coding");
    }

    @Test
    void nothingAttributable_yieldsNull() {
        // null, not ROLE_UNKNOWN: the write path substitutes the bucket, so
        // callers that only want to *report* a role can tell "unattributed"
        // from "attributed to the unknown bucket".
        assertThat(ToolUsageService.roleOf(process(null, null))).isNull();
        assertThat(ToolUsageService.roleOf(process("", ""))).isNull();
        assertThat(ToolUsageService.roleOf(null)).isNull();
    }

    private static ThinkProcessDocument process(String recipe, String engine) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setRecipeName(recipe);
        p.setThinkEngine(engine);
        return p;
    }
}
