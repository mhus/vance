package de.mhus.vance.brain.skill;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import org.junit.jupiter.api.Test;

/** Evicts exactly the changed tier, ignores every other path, never throws. */
class SkillActivationDocumentListenerTest {

    private final SkillActivationService service = mock(SkillActivationService.class);
    private final SkillActivationDocumentListener listener = new SkillActivationDocumentListener(service);

    @Test
    void changeToTheOverrideDocument_evictsItsTier() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "proj", SkillActivationService.ACTIVATION_CONFIG_PATH, "d1"));

        verify(service).evictTier("acme", "proj");
    }

    @Test
    void otherPaths_areIgnored() {
        listener.onRoutedDocumentChanged(
                new RoutedDocumentChangedEvent.Upserted("acme", "proj", "_vance/config/skill_categories.yaml", "d1"));

        verify(service, never()).evictTier(anyString(), anyString());
    }

    @Test
    void evictFailure_isSwallowed() {
        doThrow(new IllegalStateException("boom")).when(service).evictTier("acme", "proj");

        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "proj", SkillActivationService.ACTIVATION_CONFIG_PATH, "d1"));
    }
}
