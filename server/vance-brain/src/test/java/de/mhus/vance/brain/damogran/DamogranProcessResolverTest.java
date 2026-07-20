package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DamogranProcessResolverTest {

    private SessionService sessionService;
    private ThinkProcessService thinkProcessService;
    private DamogranProcessResolver resolver;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        resolver = new DamogranProcessResolver(sessionService, thinkProcessService);
    }

    @Test
    void reuses_existing_carrier_without_creating() {
        SessionDocument session = new SessionDocument();
        session.setSessionId("sys-1");
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("proc-1");
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.of(session));
        when(thinkProcessService.findByName("t", "sys-1", "_damogran")).thenReturn(Optional.of(process));

        assertThat(resolver.resolveComposeCarrier("t", "p", null)).isEqualTo("proc-1");

        verify(sessionService, never())
                .create(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(thinkProcessService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void creates_carrier_with_worktarget_override_when_absent() {
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.empty());
        SessionDocument created = new SessionDocument();
        created.setSessionId("sys-9");
        when(sessionService.create(eq("t"), eq("_damogran"), eq("p"), eq("_damogran"),
                any(), any(), any(), eq(true))).thenReturn(created);
        when(thinkProcessService.findByName("t", "sys-9", "_damogran")).thenReturn(Optional.empty());
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("proc-9");
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(process);

        assertThat(resolver.resolveComposeCarrier("t", "p", null)).isEqualTo("proc-9");

        verify(sessionService).markBootstrapped("sys-9");
        ArgumentCaptor<Set<String>> override = ArgumentCaptor.forClass(Set.class);
        verify(thinkProcessService).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                override.capture());
        assertThat(override.getValue()).contains("file_read", "file_write");
    }

    @Test
    void appKey_scopesCarrierPerApp_sanitizedName() {
        SessionDocument session = new SessionDocument();
        session.setSessionId("sys-app");
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("proc-app");
        // "app:notes/build" → sanitized "_damogran_app_notes_build"
        when(sessionService.findSystemSession("t", "p", "_damogran_app_notes_build"))
                .thenReturn(Optional.of(session));
        when(thinkProcessService.findByName("t", "sys-app", "_damogran_app_notes_build"))
                .thenReturn(Optional.of(process));

        assertThat(resolver.resolveComposeCarrier("t", "p", "app:notes/build")).isEqualTo("proc-app");
    }
}
