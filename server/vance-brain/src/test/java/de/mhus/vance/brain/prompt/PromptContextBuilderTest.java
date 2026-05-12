package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptContextBuilderTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void withRootDirTypes_setsBooleansPerType() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withRootDirTypes(Set.of("python", "git"))
                .build();

        assertThat(ctx)
                .containsEntry("has_python_rootdir", Boolean.TRUE)
                .containsEntry("has_git_rootdir", Boolean.TRUE);
    }

    @Test
    void withRootDirTypes_lowercases() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withRootDirTypes(Set.of("PYTHON"))
                .build();

        assertThat(ctx).containsEntry("has_python_rootdir", Boolean.TRUE);
    }

    @Test
    void withRootDirTypes_rejectsUnsafeNames() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withRootDirTypes(Set.of("py thon", "git;rm", "valid_one"))
                .build();

        // Only the safe one lands in the context.
        assertThat(ctx).containsKey("has_valid_one_rootdir");
        assertThat(ctx).doesNotContainKey("has_py thon_rootdir");
        assertThat(ctx).doesNotContainKey("has_git;rm_rootdir");
    }

    @Test
    void withRootDirTypes_emptyOrNullIsNoOp() {
        Map<String, Object> empty = PromptContextBuilder.create()
                .withRootDirTypes(Set.of())
                .build();
        Map<String, Object> nullCase = PromptContextBuilder.create()
                .withRootDirTypes(null)
                .build();

        assertThat(empty.keySet()).noneMatch(k -> k.startsWith("has_"));
        assertThat(nullCase.keySet()).noneMatch(k -> k.startsWith("has_"));
    }

    @Test
    void pebbleConditional_rendersWhenFlagSet() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withRootDirTypes(Set.of("python"))
                .build();
        String out = renderer.render(
                "Base.{% if has_python_rootdir %} Python ready.{% endif %}",
                ctx);

        assertThat(out).isEqualTo("Base. Python ready.");
    }

    @Test
    void pebbleConditional_skipsWhenFlagAbsent() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withRootDirTypes(Set.of("git"))
                .build();
        String out = renderer.render(
                "Base.{% if has_python_rootdir %} Python ready.{% endif %}",
                ctx);

        assertThat(out).isEqualTo("Base.");
    }
}
