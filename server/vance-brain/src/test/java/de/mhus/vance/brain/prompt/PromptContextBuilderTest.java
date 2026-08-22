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

    // ─── has_tool ───────────────────────────────────────────────────────

    @Test
    void withAvailableTools_gatesToolSpecificText() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withAvailableTools(Set.of("doc_write", "doc_read"))
                .build();

        assertThat(renderer.render(
                        "Base.{% if has_tool.doc_write %} Fence rule.{% endif %}", ctx))
                .isEqualTo("Base. Fence rule.");
    }

    /**
     * The reason this gate exists: a prompt section explaining a tool the
     * manifest does not contain makes the model invent calls. An absent
     * tool must therefore render nothing at all, not an empty heading.
     */
    @Test
    void withAvailableTools_absentToolRendersNothing() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withAvailableTools(Set.of("doc_read"))
                .build();

        assertThat(renderer.render(
                        "Base.{% if has_tool.doc_write %} Fence rule.{% endif %}", ctx))
                .isEqualTo("Base.");
    }

    @Test
    void withAvailableTools_noToolsAtAllRendersNothing() {
        // Restricted recipe / worker with no doc tools: `has_tool` is not
        // in the context at all, and the lookup must still be falsy rather
        // than blowing up the render.
        Map<String, Object> ctx = PromptContextBuilder.create().build();

        assertThat(renderer.render(
                        "Base.{% if has_tool.doc_write %} Fence rule.{% endif %}", ctx))
                .isEqualTo("Base.");
    }

    @Test
    void withAvailableTools_lowercasesAndRejectsUnsafeNames() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .withAvailableTools(Set.of("DOC_WRITE", "bad name", "rm;ls", "ok_tool"))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) ctx.get("has_tool");
        assertThat(flags).containsOnlyKeys("doc_write", "ok_tool");
    }

    @Test
    void withAvailableTools_emptyOrNullIsNoOp() {
        assertThat(PromptContextBuilder.create().withAvailableTools(Set.of()).build())
                .doesNotContainKey("has_tool");
        assertThat(PromptContextBuilder.create().withAvailableTools(null).build())
                .doesNotContainKey("has_tool");
    }
}
