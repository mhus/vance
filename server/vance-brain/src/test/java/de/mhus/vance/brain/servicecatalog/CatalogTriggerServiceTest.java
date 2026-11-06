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

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
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
              - name: hook
                event:
                  auth: { signature: { header: X-Sig, secretSetting: shared.secret } }
                subscribe: { tool: gh__create_hook, params: { config: { url: "${url}", secret: "${secret}" }, name: "${event}" }, idFrom: json.id }
                unsubscribe: { tool: gh__delete_hook, params: { id: "${subscription}" } }
              - name: new-message
                mode: poll
                poll: { tool: listMessages, schedule: "0 0/5 * * * *" }
            """;

    private final DocumentService documents = mock(DocumentService.class);
    private final ServiceCatalogLoader catalog = mock(ServiceCatalogLoader.class);
    private final UrsaEventLoader eventLoader = new UrsaEventLoader(null);
    private final SecurityContext user = SecurityContext.user("mike", "acme", List.of());
    private final ToolDispatcher tools = mock(ToolDispatcher.class);
    private final SettingService settings = mock(SettingService.class);
    private CatalogTriggerService service;

    @BeforeEach
    void setUp() {
        when(catalog.list("acme", "p")).thenReturn(List.of(ServiceCatalogLoader.parse("github", GITHUB)));
        when(documents.findByPath(eq("acme"), eq("p"), anyString())).thenReturn(Optional.empty());
        service = new CatalogTriggerService(catalog, documents, eventLoader, tools, settings, "https://vance.example/");
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

    @Test
    void aSubscribingTriggerTellsTheServiceTheUrlAndASecretOfItsOwn_andKeepsTheRegistrationId() {
        when(tools.invoke(eq("gh__create_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 201, "json", Map.of("id", 4711)));

        var created = service.createEvent("acme", "p", user, "github", "hook", "w", null);

        assertThat(created.isSubscribed()).isTrue();
        // The server made the secret, so there is nothing the user has to store.
        assertThat(created.getSecretSetting()).isNull();
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(tools).invoke(eq("gh__create_hook"), params.capture(), any(ToolInvocationContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) params.getValue().get("config");
        assertThat(config.get("url")).isEqualTo("https://vance.example/brain/acme/event/p/github-hook");
        assertThat((String) config.get("secret")).hasSize(64);
        assertThat(params.getValue().get("name")).isEqualTo("github-hook");
        // The secret is stored under a key of this event, not under the template's shared key.
        verify(settings)
                .setAs(
                        eq("acme"),
                        eq("project"),
                        eq("p"),
                        eq("events.github-hook.secret"),
                        eq((String) config.get("secret")),
                        eq(SettingType.PASSWORD),
                        any(),
                        any());
        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents).createText(any(), any(), any(), any(), any(), yaml.capture(), any(), any());
        ResolvedUrsaEvent event = eventLoader.validateYaml("github-hook", yaml.getValue());
        assertThat(event.subscription()).isEqualTo("4711");
        assertThat(event.signature().secretSettingKey()).isEqualTo("events.github-hook.secret");
    }

    @Test
    void aFailedRegistrationMakesNoEvent() {
        when(tools.invoke(eq("gh__create_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", false, "status", 401, "body", "bad credentials"));

        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "hook", "w", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("gh__create_hook");
        verify(documents, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRegistrationWithoutAnEventToPointAtIsTakenBack() {
        when(tools.invoke(eq("gh__create_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 201, "json", Map.of("id", 9)));
        when(documents.createText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("disk full"));
        when(tools.invoke(eq("gh__delete_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 204));

        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "hook", "w", null))
                .hasMessageContaining("disk full");

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(tools).invoke(eq("gh__delete_hook"), params.capture(), any(ToolInvocationContext.class));
        assertThat(params.getValue()).containsEntry("id", "9");
    }

    @Test
    void deletingTheEventRemovesTheRegistrationFirst_andKeepsTheEventWhenThatFails() {
        String yaml = """
                workflow: w
                from: github/hook
                subscription: "9"
                auth: { tokenSetting: events.github-hook.secret }
                """;
        ResolvedUrsaEvent event = eventLoader.validateYaml("github-hook", yaml);
        UrsaEventLoader loader = mock(UrsaEventLoader.class);
        when(loader.load("acme", "p", "github-hook")).thenReturn(Optional.of(event));
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("doc-1");
        when(documents.findByPath("acme", "p", "_vance/events/github-hook.yaml"))
                .thenReturn(Optional.of(doc));
        var svc = new CatalogTriggerService(catalog, documents, loader, tools, settings, "https://vance.example");
        when(tools.invoke(eq("gh__delete_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", false, "status", 503, "body", "down"));

        assertThatThrownBy(() -> svc.deleteEvent("acme", "p", user, "github-hook", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stays");
        verify(documents, never()).delete(any(), any());

        svc.deleteEvent("acme", "p", user, "github-hook", true);
        verify(documents).delete(eq("doc-1"), any());
    }

    @Test
    void placeholdersAreReplacedInNestedValuesAndUnknownOnesStay() {
        assertThat(CatalogTriggerService.substitute(
                        Map.of("a", "x-${url}", "b", List.of("${event}", "${nope}")), Map.of("url", "U", "event", "E")))
                .isEqualTo(Map.of("a", "x-U", "b", List.of("E", "${nope}")));
        assertThat(CatalogTriggerService.valueAt(Map.of("json", Map.of("id", 5)), "json.id"))
                .isEqualTo(5);
        assertThat(CatalogTriggerService.valueAt(Map.of("json", "x"), "json.id"))
                .isNull();
    }
}
