package de.mhus.vance.brain.ai.attachment;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.StandardAiChat;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link UserMessage} for a turn whose user input carries
 * attachments — resolving each {@link AttachmentRef} to bytes and
 * turning it into the multimodal content block the bound model accepts.
 *
 * <p>Engine-agnostic on purpose. This logic started as a private method
 * in {@code ArthurEngine}, which is why an image attached to a Frankie
 * session was silently dropped: every engine that wants attachments
 * needs the same resolve → convert → assemble sequence, and one copy of
 * it is the only way that stays true.
 *
 * <p>Content order matches what the providers expect and what Arthur
 * has always sent: attachment blocks first, the user's text last.
 *
 * <p>Two failure modes, both non-fatal — a turn that mentions a picture
 * should get an answer about the picture's absence, not a stack trace:
 * <ul>
 *   <li><b>Resolution fails</b> (missing document, foreign project,
 *       oversize) — the whole turn degrades to text plus a bracketed
 *       note, so the model can tell the user what happened.</li>
 *   <li><b>One attachment is rejected by the model</b> (e.g. an image
 *       for a model without {@code VISION}) — that block is skipped and
 *       logged; the rest of the turn goes out intact.</li>
 * </ul>
 *
 * <p>The caller passes the <em>final</em> text: sender prefixes for
 * multi-user sessions and any other rendering stay engine business.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AttachedUserMessageComposer {

    private final AttachmentResolver attachmentResolver;

    /**
     * Everything the composer needs about the turn that is not the
     * message itself. Built once per turn by the engine.
     *
     * @param processId only ever used in log lines — attachment
     *                  problems are diagnosed per process
     */
    public record Context(
            String tenantId,
            String projectId,
            String processId,
            String chatName,
            ProviderType providerType,
            Set<ModelCapability> capabilities) {

        public Context {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    /**
     * Returns a plain text message when {@code refs} is empty (the
     * common case, and byte-identical to the pre-attachment path),
     * otherwise a multimodal message.
     */
    public UserMessage compose(Context ctx, String text, List<AttachmentRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return UserMessage.from(text);
        }
        List<ResolvedAttachment> resolved;
        try {
            resolved = attachmentResolver.resolveAll(refs, ctx.tenantId(), ctx.projectId());
        } catch (AttachmentException e) {
            log.warn("attachment resolution failed for process '{}': {} — "
                            + "falling back to text-only turn",
                    ctx.processId(), e.getMessage());
            return UserMessage.from(text
                    + "\n\n[Attachment resolution failed: " + e.getMessage() + "]");
        }
        List<Content> blocks = new ArrayList<>();
        for (ResolvedAttachment att : resolved) {
            try {
                blocks.add(StandardAiChat.toContentBlock(
                        att, ctx.chatName(), ctx.providerType(), ctx.capabilities()));
            } catch (AttachmentException e) {
                log.warn("attachment '{}' rejected by model '{}' (process '{}'): {} — skipping",
                        att.originalFilename(), ctx.chatName(), ctx.processId(), e.getMessage());
            }
        }
        if (blocks.isEmpty()) {
            // Every block was rejected — sending a UserMessage with only
            // text is the same as the text-only path, so take it rather
            // than building a one-element multimodal message.
            return UserMessage.from(text);
        }
        blocks.add(TextContent.from(text));
        return UserMessage.from(blocks);
    }
}
