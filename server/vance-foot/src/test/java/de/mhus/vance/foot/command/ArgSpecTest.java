package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgSpecTest {

    @Test
    void free_buildsFreeKindWithEmptyChoices() {
        ArgSpec spec = ArgSpec.free("message");

        assertThat(spec.name()).isEqualTo("message");
        assertThat(spec.kind()).isEqualTo(ArgKind.FREE);
        assertThat(spec.choices()).isEmpty();
    }

    @Test
    void enumOf_storesProvidedChoices() {
        ArgSpec spec = ArgSpec.enumOf("verbosity", List.of("quiet", "normal", "verbose"));

        assertThat(spec.kind()).isEqualTo(ArgKind.ENUM);
        assertThat(spec.choices()).containsExactly("quiet", "normal", "verbose");
    }

    @Test
    void of_buildsKindWithEmptyChoices() {
        ArgSpec spec = ArgSpec.of("project", ArgKind.PROJECT);

        assertThat(spec.kind()).isEqualTo(ArgKind.PROJECT);
        assertThat(spec.choices()).isEmpty();
    }

    @Test
    void choices_areDefensivelyCopied() {
        List<String> live = new ArrayList<>();
        live.add("a");
        ArgSpec spec = new ArgSpec("name", ArgKind.ENUM, live);
        live.add("b"); // mutate after construction

        assertThat(spec.choices()).containsExactly("a");
    }

    @Test
    void choices_areImmutable_afterConstruction() {
        ArgSpec spec = ArgSpec.enumOf("x", List.of("one"));

        assertThatThrownBy(() -> spec.choices().add("two"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullChoices_areNormalisedToEmpty() {
        ArgSpec spec = new ArgSpec("name", ArgKind.FREE, null);

        assertThat(spec.choices()).isNotNull().isEmpty();
    }
}
