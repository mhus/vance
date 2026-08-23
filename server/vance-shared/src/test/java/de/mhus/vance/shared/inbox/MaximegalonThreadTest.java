package de.mhus.vance.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The thread mechanics on {@link MaximegalonService}: participants, read state,
 * messages and reactions. Mongo is stubbed — what is asserted is which update
 * the service issues and which invariant it refuses to break.
 */
class MaximegalonThreadTest {

    private MaximegalonRepository repository;
    private MongoTemplate mongoTemplate;
    private MaximegalonService service;

    @BeforeEach
    void setUp() {
        repository = mock(MaximegalonRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new MaximegalonService(repository, mongoTemplate,
                mock(ApplicationEventPublisher.class), new InboxEffectRegistry(List.of()));
    }

    // ──── Seeding on create ─────────────────────────────────────────────

    @Test
    void create_seedsParticipantsFromOriginatorAndAssignee() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        MaximegalonDocument saved = service.create(thread("acme", "alice", "bob").build());

        assertThat(saved.getParticipants()).containsExactly("alice", "bob");
    }

    @Test
    void create_marksUnreadForEveryoneButTheCreator() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        MaximegalonDocument saved = service.create(thread("acme", "alice", "bob").build());

        assertThat(saved.getReadBy()).containsExactly("alice");
        assertThat(saved.getUnreadFor()).containsExactly("bob");
    }

    /**
     * A LOW-criticality item with a default is decided at creation and
     * deliberately bothers nobody — a badge on it would contradict the whole
     * point of the auto-default.
     */
    @Test
    void create_autoAnsweredThread_startsFullyRead() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(MaximegalonService.PAYLOAD_DEFAULT_KEY, "yes");

        MaximegalonDocument saved = service.create(thread("acme", "alice", "bob")
                .criticality(Criticality.LOW)
                .payload(payload)
                .build());

        assertThat(saved.getStatus()).isEqualTo(MaximegalonStatus.ANSWERED);
        assertThat(saved.getUnreadFor()).isEmpty();
    }

    @Test
    void create_keepsParticipantsSuppliedByTheCaller() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        MaximegalonDocument saved = service.create(thread("acme", "alice", "bob")
                .participants(new ArrayList<>(List.of("alice", "bob", "cecilia")))
                .build());

        assertThat(saved.getParticipants()).containsExactly("alice", "bob", "cecilia");
        assertThat(saved.getUnreadFor()).containsExactlyInAnyOrder("bob", "cecilia");
    }

    // ──── Messages ──────────────────────────────────────────────────────

    /**
     * The scenario the explicit participant list exists for: Bob answers, so
     * Alice has to see it — one update that appends and re-marks unread.
     */
    @Test
    void postMessage_marksUnreadForEveryParticipantButTheAuthor() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1")
                .participants(new ArrayList<>(List.of("alice", "bob")))
                .unreadFor(new ArrayList<>())
                .build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.postMessage("acme", "t1", "bob", "which of the two?", null);

        Update update = captureUpdate();
        String rendered = update.getUpdateObject().toString();
        assertThat(rendered).contains("$push").contains("messages");
        assertThat(rendered).contains("alice");
        assertThat(rendered).doesNotContain("\"bob\", \"alice\"");
    }

    @Test
    void postMessage_atTheLimit_isRefused() {
        List<MaximegalonMessage> full = new ArrayList<>();
        for (int i = 0; i < MaximegalonService.MAX_MESSAGES; i++) {
            full.add(MaximegalonMessage.builder().id("m" + i).build());
        }
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1").messages(full).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.postMessage("acme", "t1", "bob", "one more", null))
                .isInstanceOf(MaximegalonRuleException.class)
                .extracting(e -> ((MaximegalonRuleException) e).getReason())
                .isEqualTo(MaximegalonRuleException.MESSAGE_LIMIT_REACHED);
    }

    @Test
    void postMessage_replyToUnknownMessage_isRefused() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.postMessage("acme", "t1", "bob", "re", "nope"))
                .isInstanceOf(MaximegalonRuleException.class)
                .extracting(e -> ((MaximegalonRuleException) e).getReason())
                .isEqualTo(MaximegalonRuleException.INVALID_PARENT);
    }

    /** Depth is capped at one level: a reply cannot itself be replied to. */
    @Test
    void postMessage_replyToAReply_isRefused() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1")
                .messages(new ArrayList<>(List.of(
                        MaximegalonMessage.builder().id("root").build(),
                        MaximegalonMessage.builder().id("child").parentId("root").build())))
                .build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.postMessage("acme", "t1", "bob", "re", "child"))
                .isInstanceOf(MaximegalonRuleException.class)
                .extracting(e -> ((MaximegalonRuleException) e).getReason())
                .isEqualTo(MaximegalonRuleException.INVALID_PARENT);
    }

    // ──── Read state ────────────────────────────────────────────────────

    /** Looking at a decision is not making it. */
    @Test
    void markRead_leavesStatusUntouched() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1")
                .messages(new ArrayList<>(List.of(MaximegalonMessage.builder().id("m1").build())))
                .build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.markRead("acme", "t1", "bob");

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("readBy").contains("unreadFor");
        assertThat(rendered).doesNotContain("status");
    }

    // ──── Participation ─────────────────────────────────────────────────

    /** An invitation has to be noticeable — it comes from someone else. */
    @Test
    void invite_addsParticipantAndMarksUnread() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.invite("acme", "t1", "cecilia", "alice");

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("participants").contains("unreadFor").contains("cecilia");
    }

    /** Joining yourself is not an alarm: you are already looking at it. */
    @Test
    void follow_doesNotMarkUnread() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.setFollowing("acme", "t1", "cecilia", true);

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("participants");
        assertThat(rendered).doesNotContain("unreadFor");
    }

    @Test
    void unfollow_dropsFromParticipantsAndUnread() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1").requiresAction(false).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.setFollowing("acme", "t1", "cecilia", false);

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("$pull").contains("participants").contains("unreadFor");
    }

    /** A process is waiting on the assignee; going quiet would strand it. */
    @Test
    void unfollow_byAssigneeOfAnOpenAsk_isRefused() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1").requiresAction(true).status(MaximegalonStatus.PENDING).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.setFollowing("acme", "t1", "bob", false))
                .isInstanceOf(MaximegalonRuleException.class)
                .extracting(e -> ((MaximegalonRuleException) e).getReason())
                .isEqualTo(MaximegalonRuleException.ASSIGNEE_MUST_STAY);
    }

    /** Once answered there is nothing left to strand. */
    @Test
    void unfollow_byAssigneeOfAnAnsweredThread_isAllowed() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1").requiresAction(true).status(MaximegalonStatus.ANSWERED)
                .answer(AnswerPayload.builder().outcome(AnswerOutcome.DECIDED)
                        .answeredBy("bob").build())
                .build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.setFollowing("acme", "t1", "bob", false);

        assertThat(captureUpdate().getUpdateObject().toString()).contains("$pull");
    }

    // ──── Delegation ────────────────────────────────────────────────────

    /** The new assignee has to learn about it; the previous one stays on. */
    @Test
    void delegate_addsNewAssigneeAsParticipantAndMarksUnread() {
        MaximegalonDocument doc = thread("acme", "alice", "bob")
                .id("t1").participants(new ArrayList<>(List.of("alice", "bob"))).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.delegate("acme", "t1", "carol", "bob", null);

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("carol").contains("participants").contains("unreadFor");
        assertThat(rendered).doesNotContain("$pull");
    }

    // ──── Reactions ─────────────────────────────────────────────────────

    /** Five agreements must not ring five bells. */
    @Test
    void react_neverTouchesUnread() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").version(3L).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                any(Class.class))).thenReturn(com.mongodb.client.result.UpdateResult
                        .acknowledged(1, 1L, null));

        service.react("acme", "t1", null, "thumbsup", "cecilia", true);

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("reactions").contains("thumbsup");
        assertThat(rendered).doesNotContain("unreadFor");
    }

    /** A key with nobody behind it would render as a chip showing zero. */
    @Test
    void react_removingTheLastUser_dropsTheReaction() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").version(1L)
                .reactions(new ArrayList<>(List.of(MaximegalonReaction.builder()
                        .key("thumbsup").userIds(new ArrayList<>(List.of("cecilia"))).build())))
                .build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                any(Class.class))).thenReturn(com.mongodb.client.result.UpdateResult
                        .acknowledged(1, 1L, null));

        service.react("acme", "t1", null, "thumbsup", "cecilia", false);

        assertThat(captureUpdate().getUpdateObject().toString()).doesNotContain("thumbsup");
    }

    // ──── Removing a participant ────────────────────────────────────────

    /**
     * The counterpart to joining. Without it, a self-join through
     * {@code setFollowing} would be irreversible for everyone except the
     * joiner — and joining converts a visibility that merely followed the
     * assignee into one that stays.
     */
    @Test
    void removeParticipant_dropsThemFromParticipantsAndFromTheBadge() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1")
                .participants(new ArrayList<>(List.of("alice", "bob", "cecilia")))
                .unreadFor(new ArrayList<>(List.of("cecilia")))
                .build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        service.removeParticipant("acme", "t1", "cecilia", "bob");

        String rendered = captureUpdate().getUpdateObject().toString();
        assertThat(rendered).contains("participants").contains("unreadFor");
        assertThat(rendered).contains("cecilia");
    }

    @Test
    void removeParticipant_refusesTheAssigneeOfAnOpenAsk() {
        // A process is waiting on them; delegation is the way out. Same rule
        // that stops them unsubscribing themselves.
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.removeParticipant("acme", "t1", "bob", "alice"))
                .isInstanceOf(MaximegalonRuleException.class)
                .hasFieldOrPropertyWithValue(
                        "reason", MaximegalonRuleException.PARTICIPANT_MUST_STAY);
    }

    @Test
    void removeParticipant_refusesTheOriginator() {
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.removeParticipant("acme", "t1", "alice", "bob"))
                .isInstanceOf(MaximegalonRuleException.class)
                .hasFieldOrPropertyWithValue(
                        "reason", MaximegalonRuleException.PARTICIPANT_MUST_STAY);
    }

    // ──── Reaction key bound ────────────────────────────────────────────

    /**
     * Every distinct key is another entry in an array that lives inside the
     * thread document, so the count is what has to be bounded — a per-key
     * length limit alone lets a client grow the document one novel key at a
     * time.
     */
    @Test
    void react_refusesANewKeyOnceTheNodeIsFull() {
        List<MaximegalonReaction> full = new ArrayList<>();
        for (int i = 0; i < MaximegalonService.MAX_REACTION_KEYS; i++) {
            full.add(MaximegalonReaction.builder()
                    .key("k" + i).userIds(new ArrayList<>(List.of("alice"))).build());
        }
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").version(1L)
                .reactions(full).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));

        assertThatThrownBy(
                () -> service.react("acme", "t1", null, "brand_new", "bob", true))
                .isInstanceOf(MaximegalonRuleException.class)
                .hasFieldOrPropertyWithValue(
                        "reason", MaximegalonRuleException.REACTION_LIMIT_REACHED);
    }

    @Test
    void react_joiningAnExistingKeyStillWorksWhenFull() {
        // The cap is on distinct keys, not on people — and a rule that could
        // not be undone would be a trap, so taking one back is never refused.
        List<MaximegalonReaction> full = new ArrayList<>();
        for (int i = 0; i < MaximegalonService.MAX_REACTION_KEYS; i++) {
            full.add(MaximegalonReaction.builder()
                    .key("k" + i).userIds(new ArrayList<>(List.of("alice"))).build());
        }
        MaximegalonDocument doc = thread("acme", "alice", "bob").id("t1").version(1L)
                .reactions(full).build();
        when(repository.findByIdAndTenantId("t1", "acme")).thenReturn(Optional.of(doc));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                any(Class.class))).thenReturn(com.mongodb.client.result.UpdateResult
                        .acknowledged(1, 1L, null));

        service.react("acme", "t1", null, "k0", "bob", true);

        assertThat(captureUpdate().getUpdateObject().toString()).contains("bob");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private Update captureUpdate() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(), any(Class.class));
        return captor.getValue();
    }

    private static MaximegalonDocument.MaximegalonDocumentBuilder thread(
            String tenant, String originator, String assignee) {
        return MaximegalonDocument.builder()
                .tenantId(tenant)
                .originatorUserId(originator)
                .assignedToUserId(assignee)
                .type(MaximegalonType.APPROVAL)
                .requiresAction(true)
                .status(MaximegalonStatus.PENDING);
    }

    // ──── Persistence contract ──────────────────────────────────────────

    /**
     * The embedded message id must be stored as {@code id}, not {@code _id}.
     *
     * <p>Spring Data maps a field called {@code id} onto {@code _id} by
     * convention, even inside an embedded document. Every {@code arrayFilters}
     * in the service addresses {@code messages.$[m].id} — so without the
     * explicit {@code @Field} the filter matches nothing and reactions and
     * per-message read marks do <em>nothing at all</em>, silently: the update is
     * well-formed, Mongo applies it to zero elements, and reads still show an id
     * because the mapper fills it back in. This test exists because that cost an
     * hour to find in a browser.
     */
    @Test
    void message_idIsStoredUnderItsOwnName_notAsMongoId() throws Exception {
        java.lang.reflect.Field field = MaximegalonMessage.class.getDeclaredField("id");
        org.springframework.data.mongodb.core.mapping.Field mapping =
                field.getAnnotation(org.springframework.data.mongodb.core.mapping.Field.class);

        assertThat(mapping)
                .as("MaximegalonMessage.id needs @Field(\"id\") or Spring Data stores it as _id")
                .isNotNull();
        assertThat(mapping.value()).isEqualTo("id");
    }
}
