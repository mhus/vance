package de.mhus.vance.brain.ws.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.IdeContext;
import de.mhus.vance.api.thinkprocess.IdeFileRange;
import org.junit.jupiter.api.Test;

class ProcessSteerHandlerIdeContextTest {

    @Test
    void renderIdeContext_nullReturnsEmpty() {
        assertThat(ProcessSteerHandler.renderIdeContext(null)).isEmpty();
    }

    @Test
    void renderIdeContext_emptyContextReturnsEmpty() {
        IdeContext ctx = IdeContext.builder().build();

        assertThat(ProcessSteerHandler.renderIdeContext(ctx)).isEmpty();
    }

    @Test
    void renderIdeContext_atMentionWithRange_emitsTag() {
        IdeContext ctx = IdeContext.builder()
                .atMention(IdeFileRange.builder()
                        .filePath("/abs/Foo.java")
                        .lineStart(5)
                        .lineEnd(13)
                        .build())
                .build();

        String rendered = ProcessSteerHandler.renderIdeContext(ctx);

        assertThat(rendered).contains("<ide-at-mention file=\"/abs/Foo.java\"")
                .contains("lineStart=\"5\"")
                .contains("lineEnd=\"13\"")
                .endsWith("\n");
    }

    @Test
    void renderIdeContext_atMentionWithoutRange_emitsFileOnly() {
        IdeContext ctx = IdeContext.builder()
                .atMention(IdeFileRange.builder().filePath("/abs/Foo.java").build())
                .build();

        String rendered = ProcessSteerHandler.renderIdeContext(ctx);

        assertThat(rendered).contains("<ide-at-mention file=\"/abs/Foo.java\"/>")
                .doesNotContain("lineStart");
    }

    @Test
    void renderIdeContext_bothFieldsSet_rendersBothTags() {
        IdeContext ctx = IdeContext.builder()
                .atMention(IdeFileRange.builder().filePath("/abs/Foo.java").lineStart(1).lineEnd(2).build())
                .currentSelection(IdeFileRange.builder().filePath("/abs/Bar.java").lineStart(7).lineEnd(7).build())
                .build();

        String rendered = ProcessSteerHandler.renderIdeContext(ctx);

        assertThat(rendered).contains("<ide-at-mention file=\"/abs/Foo.java\"")
                .contains("<ide-selection file=\"/abs/Bar.java\"");
    }

    @Test
    void renderIdeContext_blankFilePath_skipped() {
        IdeContext ctx = IdeContext.builder()
                .currentSelection(IdeFileRange.builder().filePath("").lineStart(1).lineEnd(2).build())
                .build();

        assertThat(ProcessSteerHandler.renderIdeContext(ctx)).isEmpty();
    }

    @Test
    void renderIdeContext_filePathWithSpecialChars_escapesAttr() {
        IdeContext ctx = IdeContext.builder()
                .atMention(IdeFileRange.builder().filePath("/x/<weird>&\"quoted\".md").build())
                .build();

        String rendered = ProcessSteerHandler.renderIdeContext(ctx);

        assertThat(rendered)
                .contains("&lt;weird&gt;".replace("&gt;", ">"))
                .contains("&amp;")
                .contains("&quot;")
                .doesNotContain("\"\"\"");
    }
}
