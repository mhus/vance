package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer;
import de.mhus.vance.brain.ai.attachment.AttachmentResolver;
import de.mhus.vance.brain.ai.attachment.ResolvedAttachment;
import de.mhus.vance.brain.ai.attachment.ToolAttachmentSink;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Handing a tool-produced image to the model in the shared action loop
 * (Arthur / Eddie). A tool result is text in the OpenAI-compatible API,
 * so the picture has to arrive on a message of its own.
 */
class StructuredActionEngineAttachmentTest {

    private AttachmentResolver resolver;
    private ToolAttachmentSink sink;
    private ThinkEngineContext ctx;
    private ThinkProcessDocument process;
    private TestEngine engine;
    private List<ChatMessage> messages;

    @BeforeEach
    void setUp() {
        resolver = mock(AttachmentResolver.class);
        sink = new ToolAttachmentSink();
        ctx = mock(ThinkEngineContext.class);
        when(ctx.attachmentSink()).thenReturn(sink);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("proj");
        engine = new TestEngine(new AttachedUserMessageComposer(resolver));
        messages = new ArrayList<>();
    }

    private static AttachedUserMessageComposer.Context attachmentContext() {
        return new AttachedUserMessageComposer.Context(
                "acme", "proj", "proc-1", "openai:gpt-x", ProviderType.OPENAI,
                Set.of(ModelCapability.VISION));
    }

    private void resolverReturnsAnImage() {
        when(resolver.resolveAll(any(), anyString(), anyString()))
                .thenReturn(List.of(new ResolvedAttachment(
                        "doc-1", "image/png", new byte[] {1, 2, 3}, "screenshot.png")));
    }

    @Test
    void pendingImage_isAppendedAsAnImageBlock() {
        resolverReturnsAnImage();
        sink.emit(List.of(new AttachmentRef("doc-1")));

        engine.appendToolAttachments(ctx, messages, process, attachmentContext());

        assertThat(messages).singleElement().isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(0)).contents().get(0).type())
                .isEqualTo(ContentType.IMAGE);
    }

    @Test
    void appending_drainsTheSink_soTheImageIsNotRepeated() {
        resolverReturnsAnImage();
        sink.emit(List.of(new AttachmentRef("doc-1")));

        engine.appendToolAttachments(ctx, messages, process, attachmentContext());
        engine.appendToolAttachments(ctx, messages, process, attachmentContext());

        assertThat(messages).hasSize(1);
    }

    @Test
    void emptySink_appendsNothing() {
        engine.appendToolAttachments(ctx, messages, process, attachmentContext());

        assertThat(messages).isEmpty();
    }

    @Test
    void withoutAttachmentContext_nothingIsAppended_andTheSinkKeepsItsContent() {
        // An engine that did not resolve its model metadata must not
        // silently swallow the queue — a later call can still show it.
        sink.emit(List.of(new AttachmentRef("doc-1")));

        engine.appendToolAttachments(ctx, messages, process, null);

        assertThat(messages).isEmpty();
        assertThat(sink.hasPending()).isTrue();
    }

    @Test
    void resolutionFailure_stillCarriesANoteToTheModel() {
        // AttachmentException is the composer's own failure mode: it
        // degrades to text so the model can say what went wrong.
        when(resolver.resolveAll(any(), anyString(), anyString()))
                .thenThrow(new de.mhus.vance.brain.ai.attachment.AttachmentException(
                        "document 'doc-1' not found"));
        sink.emit(List.of(new AttachmentRef("doc-1")));

        engine.appendToolAttachments(ctx, messages, process, attachmentContext());

        assertThat(messages).hasSize(1);
        assertThat(((UserMessage) messages.get(0)).contents().get(0).type())
                .isEqualTo(ContentType.TEXT);
    }

    @Test
    void unexpectedFailure_isSwallowed_andTheTurnGoesOn() {
        // Anything else (unknown provider wire-name, a bug) must cost the
        // turn the picture, not the turn itself.
        when(resolver.resolveAll(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("resolver exploded"));
        sink.emit(List.of(new AttachmentRef("doc-1")));

        engine.appendToolAttachments(ctx, messages, process, attachmentContext());

        assertThat(messages).isEmpty();
    }

    /** Minimal concrete engine — only the attachment helper is exercised. */
    private static final class TestEngine extends StructuredActionEngine {
        TestEngine(AttachedUserMessageComposer composer) {
            super(null, null, null, null, null, null,
                    mock(ThinkProcessService.class), null, composer);
        }

        @Override public String name() { return "test-engine"; }
        @Override public String title() { return "Test Engine"; }
        @Override public String description() { return "test"; }
        @Override public String version() { return "1.0.0"; }
        @Override public void start(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void resume(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void suspend(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void steer(ThinkProcessDocument p, ThinkEngineContext c, SteerMessage m) { }
        @Override public void stop(ThinkProcessDocument p, ThinkEngineContext c) { }

        @Override protected String actionToolName() { return "test_action"; }
        @Override protected String actionToolDescription() { return "test"; }
        @Override protected Map<String, Object> actionToolSchema() { return Map.of(); }
        @Override protected Set<String> supportedActionTypes() { return Set.of(); }
        @Override protected ActionTurnOutcome handleAction(
                EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
            return null;
        }
    }
}
