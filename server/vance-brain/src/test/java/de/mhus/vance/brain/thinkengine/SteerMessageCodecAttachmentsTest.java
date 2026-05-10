package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Roundtrip coverage for the attachments fan-in/fan-out across the
 * {@link SteerMessageCodec} boundary. The persistent
 * {@link PendingMessageDocument} only carries plain document ids; the
 * sealed {@link SteerMessage.UserChatInput} works with typed
 * {@link AttachmentRef}s. This test pins the translation in both
 * directions so future Mongo-side or wire-side schema changes can't
 * silently drop the attachment list.
 */
class SteerMessageCodecAttachmentsTest {

    @Test
    void encodeDecodeRoundtrip_preservesAttachmentIds() {
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.parse("2026-05-10T12:34:56Z"),
                "client-key-7",
                "alice",
                "summarise these PDFs",
                List.of(
                        new AttachmentRef("doc-foo"),
                        new AttachmentRef("doc-bar")));

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);
        SteerMessage decoded = SteerMessageCodec.toMessage(encoded);

        assertThat(encoded.getAttachmentDocumentIds())
                .containsExactly("doc-foo", "doc-bar");
        assertThat(decoded).isInstanceOf(SteerMessage.UserChatInput.class);
        SteerMessage.UserChatInput round = (SteerMessage.UserChatInput) decoded;
        assertThat(round.attachments())
                .containsExactly(new AttachmentRef("doc-foo"), new AttachmentRef("doc-bar"));
        assertThat(round.content()).isEqualTo("summarise these PDFs");
        assertThat(round.fromUser()).isEqualTo("alice");
    }

    @Test
    void emptyAttachments_serialiseToNullToKeepMongoLean() {
        // null on the doc rather than an empty array — saves one Mongo
        // field per row across the millions of plain user messages.
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.now(), null, "alice", "no attachments here");

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);

        assertThat(encoded.getAttachmentDocumentIds()).isNull();
    }

    @Test
    void decode_nullAttachmentDocumentIds_returnsEmptyList() {
        PendingMessageDocument doc = PendingMessageDocument.builder()
                .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser("alice")
                .content("no attachments")
                .attachmentDocumentIds(null)
                .build();

        SteerMessage decoded = SteerMessageCodec.toMessage(doc);

        assertThat(((SteerMessage.UserChatInput) decoded).attachments()).isEmpty();
    }

    @Test
    void decode_filtersBlankIds() {
        PendingMessageDocument doc = PendingMessageDocument.builder()
                .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser("alice")
                .content("partially-corrupt row")
                .attachmentDocumentIds(java.util.Arrays.asList("doc-ok", "", null, "doc-also"))
                .build();

        SteerMessage decoded = SteerMessageCodec.toMessage(doc);

        assertThat(((SteerMessage.UserChatInput) decoded).attachments())
                .containsExactly(new AttachmentRef("doc-ok"), new AttachmentRef("doc-also"));
    }

    @Test
    void userChatInput_attachmentsList_isImmutable() {
        java.util.List<AttachmentRef> mutable = new java.util.ArrayList<>();
        mutable.add(new AttachmentRef("doc-1"));

        SteerMessage.UserChatInput msg = new SteerMessage.UserChatInput(
                Instant.now(), null, "alice", "test", mutable);

        // Mutating the source list does not leak into the record —
        // the compact constructor takes a defensive copy.
        mutable.add(new AttachmentRef("doc-2"));
        assertThat(msg.attachments()).hasSize(1);
    }

    @Test
    void userChatInput_legacyConstructor_buildsEmptyAttachments() {
        SteerMessage.UserChatInput msg = new SteerMessage.UserChatInput(
                Instant.now(), null, "alice", "no attachments");

        assertThat(msg.attachments()).isEmpty();
    }
}
