package de.mhus.vance.brain.ursahooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrsaHookDocumentListenerTest {

    private UrsaHookService hookService;
    private UrsaHookDocumentListener listener;

    @BeforeEach
    void setUp() {
        hookService = mock(UrsaHookService.class);
        listener = new UrsaHookDocumentListener(hookService);
    }

    @Test
    void hook_path_triggers_refreshOne_with_decoded_event_and_name() {
        // Pick any known UrsaHookEventName at runtime so the test
        // doesn't break when the enum surface evolves.
        UrsaHookEventName sample = UrsaHookEventName.values()[0];
        String path = UrsaHookLoader.HOOK_PATH_ROOT
                + sample.wireName() + "/my-hook" + UrsaHookLoader.HOOK_PATH_SUFFIX;

        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", path, "id-1"));

        verify(hookService, times(1))
                .refreshOne("acme", "_tenant", sample, "my-hook");
    }

    @Test
    void non_hook_path_is_ignored() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "mail-assistant", "documents/notes.md", "id-1"));

        verify(hookService, never())
                .refreshOne(any(), any(), any(), any());
    }

    @Test
    void hook_path_without_event_segment_is_ignored() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", UrsaHookLoader.HOOK_PATH_ROOT + "lonely.yaml", "id-1"));

        verify(hookService, never())
                .refreshOne(any(), any(), any(), any());
    }

    @Test
    void unknown_event_wire_name_is_ignored() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant",
                UrsaHookLoader.HOOK_PATH_ROOT + "definitely.not.a.real.event/h.yaml",
                "id-1"));

        verify(hookService, never())
                .refreshOne(any(), any(), any(), any());
    }

    @Test
    void delete_event_for_hook_also_triggers_refreshOne() {
        UrsaHookEventName sample = UrsaHookEventName.values()[0];
        String path = UrsaHookLoader.HOOK_PATH_ROOT
                + sample.wireName() + "/doomed-hook" + UrsaHookLoader.HOOK_PATH_SUFFIX;

        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Deleted(
                "acme", "_tenant", path, "id-1"));

        verify(hookService, times(1))
                .refreshOne("acme", "_tenant", sample, "doomed-hook");
    }

    @Test
    void service_exception_is_swallowed() {
        UrsaHookEventName sample = UrsaHookEventName.values()[0];
        doThrow(new RuntimeException("yaml broken"))
                .when(hookService).refreshOne(eq("acme"), eq("_tenant"), eq(sample), eq("h"));
        String path = UrsaHookLoader.HOOK_PATH_ROOT
                + sample.wireName() + "/h" + UrsaHookLoader.HOOK_PATH_SUFFIX;

        // Must not throw — the publisher must not be unwound.
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", path, "id-1"));
    }

    @Test
    void parsePath_matches_pathFor_inverse() {
        UrsaHookEventName sample = UrsaHookEventName.values()[0];
        String path = UrsaHookLoader.HOOK_PATH_ROOT
                + sample.wireName() + "/my-hook" + UrsaHookLoader.HOOK_PATH_SUFFIX;

        UrsaHookLoader.ParsedPath parsed = UrsaHookLoader.parsePath(path);

        assertThat(parsed).isNotNull();
        assertThat(parsed.event()).isEqualTo(sample.wireName());
        assertThat(parsed.hookName()).isEqualTo("my-hook");
    }

    @Test
    void parsePath_rejects_deeper_paths() {
        String path = UrsaHookLoader.HOOK_PATH_ROOT + "evt/sub/deeper.yaml";
        assertThat(UrsaHookLoader.parsePath(path)).isNull();
    }
}
