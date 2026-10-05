package de.mhus.vance.brain.ai.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ProviderType;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shared attachment → content-block step. Extracted from
 * {@code ArthurEngine} so every engine gets it; these tests pin the
 * behaviour that used to live there, including both non-fatal failure
 * paths — a turn about a picture should get an answer, not a stack trace.
 */
class AttachedUserMessageComposerTest {

    private static final AttachmentRef REF = new AttachmentRef("doc-1");

    private AttachmentResolver resolver;
    private AttachedUserMessageComposer composer;

    @BeforeEach
    void setUp() {
        resolver = mock(AttachmentResolver.class);
        composer = new AttachedUserMessageComposer(resolver);
    }

    private static AttachedUserMessageComposer.Context ctx(Set<ModelCapability> caps) {
        return new AttachedUserMessageComposer.Context(
                "tenant-x", "proj-1", "proc-1", "openai:gpt-x", ProviderType.OPENAI, caps);
    }

    private static ResolvedAttachment png() {
        return new ResolvedAttachment("doc-1", "image/png", new byte[] {1, 2, 3}, "screenshot.png");
    }

    private static ResolvedAttachment text() {
        return new ResolvedAttachment("doc-2", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), "notes.txt");
    }

    @Test
    void noRefs_producesAPlainTextMessage() {
        UserMessage msg = composer.compose(ctx(Set.of()), "just text", List.of());

        assertThat(msg.contents()).singleElement().isInstanceOf(TextContent.class);
        assertThat(((TextContent) msg.contents().get(0)).text()).isEqualTo("just text");
    }

    @Test
    void nullRefs_areTreatedAsNone() {
        assertThat(composer.compose(ctx(Set.of()), "just text", null).contents())
                .singleElement()
                .isInstanceOf(TextContent.class);
    }

    // imageForAVisionModel_becomesAnImageBlockWithTheIdHintBeforeTheText
    // (renamed) carries this case including the id hint.

    @Test
    void resolutionFailure_degradesToTextWithANote() {
        // Missing document, foreign project, oversize — the turn must
        // still reach the model so it can say what went wrong.
        when(resolver.resolveAll(any(), anyString(), anyString()))
                .thenThrow(new AttachmentException("document 'doc-1' not found"));

        UserMessage msg = composer.compose(ctx(Set.of(ModelCapability.VISION)), "look", List.of(REF));

        assertThat(msg.contents()).singleElement().isInstanceOf(TextContent.class);
        assertThat(((TextContent) msg.contents().get(0)).text())
                .startsWith("look")
                .contains("Attachment resolution failed")
                .contains("doc-1");
    }

    @Test
    void imageForAModelWithoutVision_isSkipped_andTheTurnStaysText() {
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(png()));

        UserMessage msg = composer.compose(ctx(Set.of()), "look", List.of(REF));

        assertThat(msg.contents()).singleElement().isInstanceOf(TextContent.class);
        assertThat(((TextContent) msg.contents().get(0)).text()).isEqualTo("look");
    }

    @Test
    void oneRejectedBlock_doesNotDropTheOthers() {
        // Mixed batch on a text-only model: the image is refused, the
        // text attachment still rides along.
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(png(), text()));

        UserMessage msg = composer.compose(ctx(Set.of()), "summarise", List.of(REF, new AttachmentRef("doc-2")));

        assertThat(msg.contents()).hasSize(2);
        assertThat(msg.contents().get(0).type()).isEqualTo(ContentType.TEXT);
        // The image was rejected by the non-vision model, so no id
        // hint is attached — the user text arrives verbatim.
        assertThat(((TextContent) msg.contents().get(1)).text()).isEqualTo("summarise");
    }

    @Test
    void imageForAVisionModel_becomesAnImageBlockWithTheIdHintBeforeTheText() {
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(png()));

        UserMessage msg = composer.compose(ctx(Set.of(ModelCapability.VISION)), "what is this?", List.of(REF));

        List<Content> contents = msg.contents();
        assertThat(contents).hasSize(2);
        assertThat(contents.get(0).type()).isEqualTo(ContentType.IMAGE);
        assertThat(contents.get(1)).isInstanceOf(TextContent.class);
        // The text block carries the image_edit id hint above the user
        // text — see imageAttachments_carryTheDocumentIdEditHintAboveTheText.
        assertThat(((TextContent) contents.get(1)).text()).endsWith("what is this?");
    }

    @Test
    void theUsersTextIsAlwaysTheLastBlock() {
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(png(), png()));

        UserMessage msg = composer.compose(ctx(Set.of(ModelCapability.VISION)), "compare these", List.of(REF, REF));

        List<Content> contents = msg.contents();
        assertThat(contents).hasSize(3);
        assertThat(((TextContent) contents.get(contents.size() - 1)).text()).endsWith("compare these");
    }

    @Test
    void imageAttachments_carryTheDocumentIdEditHintAboveTheText() {
        // The image_edit bridge: the model sees the pixels, but only
        // the text block can tell it the document ids to pass back as
        // referenceDocumentIds.
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(png(), text()));

        UserMessage msg = composer.compose(
                ctx(Set.of(ModelCapability.VISION)), "restyle this", List.of(REF, new AttachmentRef("doc-2")));

        List<Content> contents = msg.contents();
        assertThat(contents).hasSize(3);
        String text = ((TextContent) contents.get(contents.size() - 1)).text();
        assertThat(text)
                .startsWith("[Attached image document ids: 'doc-1'")
                .contains("image_edit")
                .contains("referenceDocumentIds")
                .endsWith("restyle this");
    }

    @Test
    void textOnlyAttachments_carryNoEditHint() {
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(text()));

        UserMessage msg = composer.compose(ctx(Set.of()), "summarise", List.of(new AttachmentRef("doc-2")));

        // One text block for the attachment + the user text — no id
        // hint, because there is no image to edit.
        assertThat(((TextContent) msg.contents().get(1)).text()).isEqualTo("summarise");
    }

    @Test
    void nullCapabilities_areTreatedAsNone() {
        // Defensive: a caller without ModelInfo must not NPE the turn.
        when(resolver.resolveAll(any(), anyString(), anyString())).thenReturn(List.of(png()));

        UserMessage msg = composer.compose(ctx(null), "look", List.of(REF));

        assertThat(msg.contents()).singleElement().isInstanceOf(TextContent.class);
    }
}
