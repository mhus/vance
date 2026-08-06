package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code stateGeneration()} is what re-arms {@link LiveRegion}'s
 * once-per-idle-period fetch latch when a new assistant message arrives
 * while the user isn't typing. If it stopped moving, a suggestion would
 * only ever be fetched after the next keystroke.
 */
@SuppressWarnings("unchecked")
class FollowUpSuggestionServiceGenerationTest {

    private FollowUpSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new FollowUpSuggestionService(
                new FootConfig(),
                mock(ObjectProvider.class),
                mock(SessionService.class));
    }

    @Test
    void newAssistantMessage_bumpsTheGeneration() {
        long before = service.stateGeneration();

        service.onAssistantMessage("Here is the weather.");

        assertThat(service.stateGeneration()).isGreaterThan(before);
    }

    @Test
    void everyAssistantMessage_bumpsAgain() {
        service.onAssistantMessage("first");
        long afterFirst = service.stateGeneration();

        service.onAssistantMessage("second");

        assertThat(service.stateGeneration()).isGreaterThan(afterFirst);
    }

    @Test
    void blankAssistantMessage_stillBumps() {
        // A blank message clears the suggestion too; the animator must
        // see that state change rather than keep a stale latch.
        long before = service.stateGeneration();

        service.onAssistantMessage("");

        assertThat(service.stateGeneration()).isGreaterThan(before);
    }

    @Test
    void acceptingASuggestion_doesNotBumpTheGeneration() {
        // Accepting must NOT re-arm the latch: the accepted key is
        // suppressed for this assistant message, so re-firing would just
        // walk into the "already accepted" skip on every tick — the
        // behaviour the latch exists to stop.
        service.onAssistantMessage("reply");
        long afterMessage = service.stateGeneration();

        service.acceptCurrent();

        assertThat(service.stateGeneration()).isEqualTo(afterMessage);
    }

    @Test
    void clearingASuggestion_doesNotBumpTheGeneration() {
        // Typing already re-arms the latch via the input-activity
        // timestamp; bumping here as well would be a second, redundant
        // trigger source.
        service.onAssistantMessage("reply");
        long afterMessage = service.stateGeneration();

        service.clearSuggestion();

        assertThat(service.stateGeneration()).isEqualTo(afterMessage);
    }

    @Test
    void unusedRestProvider_isNeverTouchedWithoutABoundSession() {
        // Guard order: no bound session → return before the REST client
        // is resolved. Keeps the fetch cheap on the animator thread.
        ObjectProvider<BrainRestClientService> restProvider = mock(ObjectProvider.class);
        SessionService sessions = mock(SessionService.class);  // current() → null
        FollowUpSuggestionService s = new FollowUpSuggestionService(
                new FootConfig(), restProvider, sessions);
        s.onAssistantMessage("reply");

        s.fetchIfApplicable();

        assertThat(s.currentSuggestion()).isNull();
        verifyNoInteractions(restProvider);
    }
}
