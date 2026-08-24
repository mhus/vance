package de.mhus.vance.brain.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.inbox.MaximegalonDto;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The discussion size on the wire. Three answers have to stay distinct: a
 * counted thread, an empty one, and one whose messages were never fetched —
 * the last is what a list row gets, and mistaking it for "empty" would hide
 * every discussion in the listing.
 */
class InboxMapperTest {

    private static MaximegalonMessage message(String id) {
        return MaximegalonMessage.builder().id(id).authorUserId("ford").body("b").build();
    }

    @Test
    void toDto_withMessages_countsThem() {
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .id("t1")
                .messages(List.of(message("m1"), message("m2")))
                .build();

        assertThat(InboxMapper.toDto(doc).getMessageCount()).isEqualTo(2);
    }

    @Test
    void toDto_withMessagesProjectedOut_leavesCountUnknown() {
        // What the list query hands back: the field is absent, not empty.
        MaximegalonDocument doc = MaximegalonDocument.builder().id("t1").messages(null).build();

        MaximegalonDto dto = InboxMapper.toDto(doc);

        assertThat(dto.getMessageCount()).isNull();
        assertThat(dto.getMessages()).isEmpty();
    }

    @Test
    void toDtos_withCounts_usesTheCountedSizePerThread() {
        List<MaximegalonDocument> docs = List.of(
                MaximegalonDocument.builder().id("t1").messages(null).build(),
                MaximegalonDocument.builder().id("t2").messages(null).build());

        List<MaximegalonDto> dtos = InboxMapper.toDtos(docs, Map.of("t1", 3));

        assertThat(dtos.get(0).getMessageCount()).isEqualTo(3);
        // No row in the counting query means no such thread — not zero.
        assertThat(dtos.get(1).getMessageCount()).isNull();
    }
}
