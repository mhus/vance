package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequireSpecTest {

    @Test
    void parse_nameAndVersion() {
        RequireSpec spec = RequireSpec.parse("db@2.4", "x");

        assertThat(spec.name()).isEqualTo("db");
        assertThat(spec.version()).isEqualTo("2.4");
        assertThat(spec.id()).isEqualTo("db@2.4");
    }

    @Test
    void parse_upperCaseName_isLowered() {
        assertThat(RequireSpec.parse("DB@1", "x").name()).isEqualTo("db");
    }

    /**
     * A bare name would have to mean "whatever is newest", which is a second
     * resolution mode and a different promise: `db@1` records which API the
     * author wrote against, and that is what lets a conflict be reported.
     */
    @Test
    void parse_withoutVersion_isRejected() {
        assertThatThrownBy(() -> RequireSpec.parse("db", "views/main.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("views/main.yaml")
                .hasMessageContaining("the version is required");
    }

    @Test
    void parse_badSpelling_isRejected() {
        for (String bad : List.of("@1", "db@", "1db@1", "d b@1", "db@x", "db@1.")) {
            assertThatThrownBy(() -> RequireSpec.parse(bad, "x"))
                    .as(bad)
                    .isInstanceOf(ToolException.class);
        }
    }

    /**
     * Part-wise and numeric, so 10 sorts after 2. Lexicographic comparison
     * would make `db@10` older than `db@2` and quietly load the wrong one.
     */
    @Test
    void compareVersions_isNumericPerPart() {
        assertThat(RequireSpec.compareVersions("10", "2")).isPositive();
        assertThat(RequireSpec.compareVersions("1.10", "1.2")).isPositive();
        assertThat(RequireSpec.compareVersions("2", "2.0")).isZero();
        assertThat(RequireSpec.compareVersions("1.2", "1.2.1")).isNegative();
    }
}
