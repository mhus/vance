package de.mhus.vance.brain.servicecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class CatalogTriggerServiceTest {

    private static final String GITHUB = """
            match: [github]
            highlights: []
            triggers:
              - name: push
                description: { en: "A push" }
                event:
                  auth: { signature: { header: X-Hub-Signature-256, secretSetting: webhook.github.secret, prefix: "sha256=" } }
                  idempotencyHeader: X-GitHub-Delivery
              - name: ping
              - name: new-message
                mode: poll
                poll: { tool: listMessages, schedule: "0 0/5 * * * *" }
            """;

    private final DocumentService documents = mock(DocumentService.class);
    private final ServiceCatalogLoader catalog = mock(ServiceCatalogLoader.class);
    private final UrsaEventLoader eventLoader = new UrsaEventLoader(null);
    private final SecurityContext user = SecurityContext.user("mike", "acme", List.of());
    private CatalogTriggerService service;

    @BeforeEach
    void setUp() {
        when(catalog.list("acme", "p")).thenReturn(List.of(ServiceCatalogLoader.parse("github", GITHUB)));
        when(documents.findByPath(eq("acme"), eq("p"), anyString())).thenReturn(Optional.empty());
        service = new CatalogTriggerService(catalog, documents, eventLoader);
    }

    @Test
    void theTemplateBecomesAnOrdinaryEventThatPointsAtTheWorkflowAndRemembersItsOrigin() {
        var created = service.createEvent("acme", "p", user, "github", "push", "pr-review", null);

        assertThat(created.getName()).isEqualTo("github-push");
        assertThat(created.getPath()).isEqualTo("_vance/events/github-push.yaml");
        assertThat(created.getUrlPath()).isEqualTo("/brain/acme/event/p/github-push");
        assertThat(created.getSecretSetting()).isEqualTo("webhook.github.secret");

        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents)
                .createText(
                        eq("acme"),
                        eq("p"),
                        eq("_vance/events/github-push.yaml"),
                        any(),
                        any(),
                        yaml.capture(),
                        any(),
                        any());
        ResolvedUrsaEvent event = eventLoader.validateYaml("github-push", yaml.getValue());
        assertThat(event.workflow()).isEqualTo("pr-review");
        assertThat(event.from()).isEqualTo("github/push");
        assertThat(event.signature().header()).isEqualTo("X-Hub-Signature-256");
        assertThat(event.idempotencyHeader()).isEqualTo("X-GitHub-Delivery");
    }

    @Test
    void aTriggerWithoutAuthInItsTemplateGetsABearerTokenSetting() {
        var created = service.createEvent("acme", "p", user, "github", "ping", "w", "my-ping");

        assertThat(created.getName()).isEqualTo("my-ping");
        assertThat(created.getSecretSetting()).isEqualTo("events.my-ping.token");
        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents).createText(any(), any(), any(), any(), any(), yaml.capture(), any(), any());
        assertThat(eventLoader.validateYaml("my-ping", yaml.getValue()).tokenSettingKey())
                .isEqualTo("events.my-ping.token");
    }

    @Test
    void anExistingEventIsNotOverwritten() {
        when(documents.findByPath("acme", "p", "_vance/events/github-push.yaml"))
                .thenReturn(Optional.of(mock(DocumentDocument.class)));

        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "push", "w", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exists already");
        verify(documents, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownServicesAndTriggersAndPollingTriggersAreRefused() {
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "gitlab", "push", "w", null))
                .hasMessageContaining("not in the catalog");
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "nope", "w", null))
                .hasMessageContaining("no trigger");
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "new-message", "w", null))
                .hasMessageContaining("polls");
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "push", " ", null))
                .hasMessageContaining("workflow");
    }
}
