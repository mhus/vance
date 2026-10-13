package de.mhus.vance.brain.trillian.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.trillian.TrillianHeadService;
import de.mhus.vance.brain.trillian.TrillianOwnSessions;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The gates of the protocol entry: who may open, who may write, what stays
 * readable. Every refusal carries a stable reason — the shells show it to
 * the external agent as text, so it must be worth grepping for.
 */
@ExtendWith(MockitoExtension.class)
class TrillianConversationServiceTest {

    private static final String TENANT = "acme";
    private static final String CALLER = "road.runner";
    private static final String COLIN = "_trillian-colin-5127";
    private static final String OTHER = "wile.coyote";

    @Mock
    private MaximegalonService threads;

    @Mock
    private TrillianExposureService exposureService;

    @Mock
    private TrillianOwnSessions ownSessions;

    @Mock
    private TrillianNatureRegistry natureRegistry;

    @Mock
    private TrillianHeadService headService;

    private TrillianConversationService service;

    @BeforeEach
    void setUp() {
        service = new TrillianConversationService(threads, exposureService, ownSessions, natureRegistry, headService);
        lenient().when(natureRegistry.resolve(anyString())).thenReturn(mock(TrillianNature.class));
        lenient().when(ownSessions.liveLoopOf(TENANT, COLIN)).thenReturn(Optional.of(new ThinkProcessDocument()));
    }

    // ──────────────────── open ────────────────────

    @Test
    void openCreatesTheConversationThreadThroughTheEntry() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(true);
        when(threads.create(any())).thenAnswer(inv -> {
            MaximegalonDocument doc = inv.getArgument(0);
            doc.setId("thread-1");
            return doc;
        });

        TrillianConversationService.Opened opened =
                service.open(TENANT, CALLER, "colin", "Hello", "A first contribution.");

        assertThat(opened.conversationId()).isEqualTo("thread-1");
        ArgumentCaptor<MaximegalonDocument> thread = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(threads).create(thread.capture());
        MaximegalonDocument created = thread.getValue();
        assertThat(created.getOriginatorUserId()).isEqualTo(CALLER);
        assertThat(created.getAssignedToUserId()).isEqualTo(COLIN);
        assertThat(created.getType()).isEqualTo(MaximegalonType.OUTPUT_TEXT);
        assertThat(created.getTags()).containsExactly("discussion");
        assertThat(created.isRequiresAction()).isFalse();
        assertThat(created.getTitle()).isEqualTo("Hello");
        assertThat(created.getBody()).isEqualTo("A first contribution.");
    }

    @Test
    void openWithoutTitleDerivesItFromTheFirstLine() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(true);
        when(threads.create(any())).thenAnswer(inv -> inv.getArgument(0));

        service.open(TENANT, CALLER, "colin", null, "First line is the title\nand this is the rest.");

        ArgumentCaptor<MaximegalonDocument> thread = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(threads).create(thread.capture());
        assertThat(thread.getValue().getTitle()).isEqualTo("First line is the title");
    }

    @Test
    void openRefusesAnUnknownAgent() {
        when(exposureService.findByExposedName(TENANT, "nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "nobody", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent not exposed");
        verify(threads, never()).create(any());
    }

    @Test
    void openRefusesWhenTheDoorIsClosed() {
        givenExposure(colinConfig("road.runner"), false);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("door closed");
        verify(threads, never()).create(any());
    }

    @Test
    void openRefusesACounterpartThatIsNotAllowed() {
        givenExposure(colinConfig(OTHER), true);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not allowed");
        verify(threads, never()).create(any());
    }

    @Test
    void openRefusesAnInactiveAgent() {
        givenExposure(colinConfig("road.runner"), true);
        when(ownSessions.liveLoopOf(TENANT, COLIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent inactive");
        verify(threads, never()).create(any());
    }

    @Test
    void openIsHonestAboutTheConversationLimit() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation limit reached");
        verify(threads, never()).create(any());
    }

    // ──────────────────── send & read ────────────────────

    @Test
    void sendContributesAndReturnsTheMessage() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING, message("m-1", COLIN, "earlier"));
        when(threads.postMessage(TENANT, "thread-1", CALLER, "and more", null))
                .thenReturn(Optional.of(conversation(
                        "thread-1",
                        CALLER,
                        MaximegalonStatus.PENDING,
                        message("m-1", COLIN, "earlier"),
                        message("m-2", CALLER, "and more"))));

        TrillianConversationService.Contribution sent = service.send(TENANT, CALLER, "thread-1", "and more");

        assertThat(sent.id()).isEqualTo("m-2");
        assertThat(sent.author()).isEqualTo(CALLER);
        assertThat(sent.body()).isEqualTo("and more");
    }

    @Test
    void sendRefusesSomeoneElsesConversation() {
        givenConversation("thread-1", OTHER, MaximegalonStatus.PENDING);

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation is not yours");
        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
    }

    @Test
    void sendRefusesAnArchivedConversation() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED);

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation archived");
        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
    }

    @Test
    void readKeepsWorkingAfterArchive() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED, message("m-1", COLIN, "all done"));

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", null, 0);

        assertThat(reply.arrived()).isTrue();
        assertThat(reply.messages()).hasSize(1);
        assertThat(reply.messages().get(0).body()).isEqualTo("all done");
    }

    @Test
    void readAnswersOnlyWhatFollowsTheCursor() {
        givenConversation(
                "thread-1",
                CALLER,
                MaximegalonStatus.PENDING,
                message("m-1", COLIN, "one"),
                message("m-2", CALLER, "two"),
                message("m-3", COLIN, "three"));

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", "m-1", 0);

        assertThat(reply.messages())
                .extracting(TrillianConversationService.Contribution::id)
                .containsExactly("m-2", "m-3");
    }

    @Test
    void readWaitsForSomethingNew_andSaysSoWhenNothingCame() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING);

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", null, 0);

        assertThat(reply.arrived()).isFalse();
        assertThat(reply.messages()).isEmpty();
    }

    @Test
    void readOnAHumanThreadIsNotFound() {
        MaximegalonDocument humanThread = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        humanThread.setAssignedToUserId(OTHER);
        when(threads.findById(TENANT, "thread-1")).thenReturn(Optional.of(humanThread));

        assertThatThrownBy(() -> service.read(TENANT, CALLER, "thread-1", null, 0))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation not found");
    }

    // ──────────────────── fixtures ────────────────────

    private static TrillianExposureConfig colinConfig(String... allowed) {
        return new TrillianExposureConfig(
                "colin", "Colin", List.of(allowed), 0, 0, new TrillianExposureConfig.Doors(true, false));
    }

    private void givenExposure(TrillianExposureConfig config, boolean mcpDoor) {
        TrillianExposureConfig effective = new TrillianExposureConfig(
                config.exposedName(),
                config.description(),
                config.allowedCounterparts(),
                config.headLimit(),
                config.inactivityDays(),
                new TrillianExposureConfig.Doors(mcpDoor, false));
        when(exposureService.findByExposedName(TENANT, "colin"))
                .thenReturn(Optional.of(new TrillianExposureService.Exposed(COLIN, effective)));
    }

    private void givenConversation(
            String id, String originator, MaximegalonStatus status, MaximegalonMessage... messages) {
        MaximegalonDocument item = conversation(id, originator, status, messages);
        when(threads.findById(TENANT, id)).thenReturn(Optional.of(item));
    }

    private static MaximegalonDocument conversation(
            String id, String originator, MaximegalonStatus status, MaximegalonMessage... messages) {
        MaximegalonDocument item = new MaximegalonDocument();
        item.setId(id);
        item.setTenantId(TENANT);
        item.setAssignedToUserId(COLIN);
        item.setOriginatorUserId(originator);
        item.setStatus(status);
        item.setType(MaximegalonType.OUTPUT_TEXT);
        item.setMessages(new ArrayList<>(List.of(messages)));
        return item;
    }

    private static MaximegalonMessage message(String id, String author, String body) {
        return MaximegalonMessage.builder()
                .id(id)
                .authorUserId(author)
                .body(body)
                .createdAt(Instant.now())
                .build();
    }
}
