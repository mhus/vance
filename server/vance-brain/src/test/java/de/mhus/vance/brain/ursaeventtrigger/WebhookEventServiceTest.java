package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.ursaevents.WebhookCreateResponse;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class WebhookEventServiceTest {

    private final DocumentService documents = mock(DocumentService.class);
    private final SettingService settings = mock(SettingService.class);
    private final UrsaEventLoader parser = new UrsaEventLoader(null);
    private final UrsaEventLoader loader = mock(UrsaEventLoader.class);
    private final SecurityContext user = SecurityContext.user("mike", "acme", List.of());
    private WebhookEventService service;

    @BeforeEach
    void setUp() {
        when(documents.findByPath(eq("acme"), eq("p"), anyString())).thenReturn(Optional.empty());
        service = new WebhookEventService(documents, parser, settings);
    }

    @Test
    void makes_an_event_that_names_the_token_setting_and_hands_the_token_back_once() {
        WebhookCreateResponse made = service.create("acme", "p", user, "daily-audit", "Audit-Hook", "Audit trigger");

        assertThat(made.getName()).isEqualTo("audit-hook");
        assertThat(made.getPath()).isEqualTo("_vance/events/audit-hook.yaml");
        assertThat(made.getUrlPath()).isEqualTo("/brain/acme/event/p/audit-hook");
        assertThat(made.getTokenSetting()).isEqualTo("events.audit-hook.token");
        assertThat(made.getToken()).matches("[0-9a-f]{64}");

        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents)
                .createText(
                        eq("acme"),
                        eq("p"),
                        eq("_vance/events/audit-hook.yaml"),
                        eq("audit-hook"),
                        eq(List.of("event")),
                        yaml.capture(),
                        eq("mike"),
                        any(WriteActor.class));
        ResolvedUrsaEvent event = parser.validateYaml("audit-hook", yaml.getValue());
        assertThat(event.workflow()).isEqualTo("daily-audit");
        assertThat(event.description()).isEqualTo("Audit trigger");
        assertThat(event.tokenSettingKey()).isEqualTo("events.audit-hook.token");
        assertThat(event.methods()).containsExactly("POST");
        assertThat(yaml.getValue()).doesNotContain(made.getToken());

        verify(settings)
                .setAs(
                        "acme",
                        SettingService.SCOPE_PROJECT,
                        "p",
                        "events.audit-hook.token",
                        made.getToken(),
                        SettingType.PASSWORD,
                        "Token of the event 'audit-hook'",
                        "mike");
    }

    @Test
    void takes_the_workflow_name_and_a_made_up_description_when_nothing_else_is_given() {
        WebhookCreateResponse made = service.create("acme", "p", user, "daily-audit", null, null);

        assertThat(made.getName()).isEqualTo("daily-audit");
        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents).createText(any(), any(), any(), any(), any(), yaml.capture(), any(), any(WriteActor.class));
        assertThat(parser.validateYaml("daily-audit", yaml.getValue()).description())
                .isEqualTo("Starts daily-audit");
    }

    @Test
    void every_event_gets_a_token_of_its_own() {
        String a = service.create("acme", "p", user, "w", "one", null).getToken();
        String b = service.create("acme", "p", user, "w", "two", null).getToken();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void refuses_a_missing_workflow_a_bad_name_and_an_event_that_exists() {
        assertThatThrownBy(() -> service.create("acme", "p", user, " ", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("workflow");
        assertThatThrownBy(() -> service.create("acme", "p", user, "w", "Not Fine!", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("event name");

        when(documents.findByPath("acme", "p", "_vance/events/taken.yaml"))
                .thenReturn(Optional.of(mock(DocumentDocument.class)));
        assertThatThrownBy(() -> service.create("acme", "p", user, "w", "taken", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exists already");

        verify(settings, never()).setAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void takes_the_token_back_when_the_document_cannot_be_written() {
        doThrow(new IllegalStateException("denied"))
                .when(documents)
                .createText(any(), any(), any(), any(), any(), any(), any(), any(WriteActor.class));

        assertThatThrownBy(() -> service.create("acme", "p", user, "w", "hook", null))
                .isInstanceOf(IllegalStateException.class);

        verify(settings).delete("acme", SettingService.SCOPE_PROJECT, "p", "events.hook.token");
    }

    // ── delete ──────────────────────────────────────────────────

    private ResolvedUrsaEvent event(String name, String yaml) {
        ResolvedUrsaEvent event = parser.validateYaml(name, yaml);
        when(loader.load("acme", "p", name)).thenReturn(Optional.of(event));
        return event;
    }

    private DocumentDocument document(String name) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id-" + name);
        when(documents.findByPath("acme", "p", "_vance/events/" + name + ".yaml"))
                .thenReturn(Optional.of(doc));
        return doc;
    }

    @Test
    void deleting_removes_the_document_and_the_token_it_made() {
        event("hook", """
                workflow: w
                auth: { tokenSetting: events.hook.token }
                """);
        document("hook");
        WebhookEventService deleting = new WebhookEventService(documents, loader, settings);

        deleting.delete("acme", "p", user, "Hook");

        verify(documents).delete(eq("id-hook"), any(WriteActor.class));
        verify(settings).delete("acme", SettingService.SCOPE_PROJECT, "p", "events.hook.token");
    }

    @Test
    void deleting_leaves_a_setting_that_other_events_may_share() {
        event("hook", """
                workflow: w
                auth: { tokenSetting: shared.token }
                """);
        document("hook");
        WebhookEventService deleting = new WebhookEventService(documents, loader, settings);

        deleting.delete("acme", "p", user, "hook");

        verify(documents).delete(eq("id-hook"), any(WriteActor.class));
        verify(settings, never()).delete(any(), any(), any(), any());
    }

    @Test
    void deleting_refuses_an_event_registered_at_a_service() {
        event("gh", """
                workflow: w
                from: github/push
                subscription: "42"
                auth: { signature: { header: X-Sig, secretSetting: events.gh.secret } }
                """);
        WebhookEventService deleting = new WebhookEventService(documents, loader, settings);

        assertThatThrownBy(() -> deleting.delete("acme", "p", user, "gh"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("workflow-triggers");

        verify(documents, never()).delete(any(), any(WriteActor.class));
    }

    @Test
    void deleting_an_event_that_is_not_there_is_not_found() {
        when(loader.load("acme", "p", "nope")).thenReturn(Optional.empty());
        WebhookEventService deleting = new WebhookEventService(documents, loader, settings);

        assertThatThrownBy(() -> deleting.delete("acme", "p", user, "nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }
}
