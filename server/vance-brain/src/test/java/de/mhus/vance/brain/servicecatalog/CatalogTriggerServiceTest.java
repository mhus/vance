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
              - name: repo-hook
                inputs:
                  - { name: owner }
                  - { name: repo }
                  - { name: note, required: false }
                event:
                  idempotencyHeader: "X-Delivery-${input.repo}"
                  auth: { signature: { header: X-Sig, secretSetting: shared.secret } }
                subscribe: { tool: create_hook, params: { owner: "${input.owner}", repo: "${input.repo}", url: "${url}" }, idFrom: json.id }
                unsubscribe: { tool: delete_hook, params: { owner: "${input.owner}", repo: "${input.repo}", id: "${subscription}" } }
              - name: hook
                event:
                  auth: { signature: { header: X-Sig, secretSetting: shared.secret } }
                subscribe: { tool: gh__create_hook, params: { config: { url: "${url}", secret: "${secret}" }, name: "${event}" }, idFrom: json.id }
                unsubscribe: { tool: gh__delete_hook, params: { id: "${subscription}" } }
              - name: room-poll
                mode: poll
                inputs: [ { name: room } ]
                poll: { tool: history, schedule: "0 0/5 * * * *", params: { room: "${input.room}" } }
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
    private final CatalogPollService polls = mock(CatalogPollService.class);
    private final CatalogToolResolver resolver = new CatalogToolResolver(tools);
    private CatalogTriggerService service;

    @BeforeEach
    void setUp() {
        var parsed = ServiceCatalogLoader.parse("github", GITHUB);
        when(catalog.list("acme", "p")).thenReturn(List.of(parsed));
        when(catalog.locate(eq("acme"), eq("p"), any())).thenAnswer(inv -> {
            String from = inv.getArgument(2);
            return parsed.triggers().stream()
                    .filter(t -> from.equals("github/" + t.name()))
                    .map(t -> new ServiceCatalogLoader.Located(parsed, t))
                    .findFirst();
        });
        // Packs are not installed in these tests: the tools are named in full.
        when(tools.resolveAll(any())).thenReturn(List.of());
        when(documents.findByPath(eq("acme"), eq("p"), anyString())).thenReturn(Optional.empty());
        service = new CatalogTriggerService(
                catalog, documents, eventLoader, tools, settings, polls, resolver, "https://vance.example/");
    }

    @Test
    void theTemplateBecomesAnOrdinaryEventThatPointsAtTheWorkflowAndRemembersItsOrigin() {
        var created = service.createEvent("acme", "p", user, "github", "push", "pr-review", null, Map.of());

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
        var created = service.createEvent("acme", "p", user, "github", "ping", "w", "my-ping", Map.of());

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

        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "push", "w", null, Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exists already");
        verify(documents, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownServicesAndTriggersAndPollingTriggersAreRefused() {
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "gitlab", "push", "w", null, Map.of()))
                .hasMessageContaining("not in the catalog");
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "nope", "w", null, Map.of()))
                .hasMessageContaining("no trigger");
        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "push", " ", null, Map.of()))
                .hasMessageContaining("workflow");
    }

    @Test
    void aSubscribingTriggerTellsTheServiceTheUrlAndASecretOfItsOwn_andKeepsTheRegistrationId() {
        when(tools.invoke(eq("gh__create_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 201, "json", Map.of("id", 4711)));

        var created = service.createEvent("acme", "p", user, "github", "hook", "w", null, Map.of());

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

        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "hook", "w", null, Map.of()))
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

        assertThatThrownBy(() -> service.createEvent("acme", "p", user, "github", "hook", "w", null, Map.of()))
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
        var svc = new CatalogTriggerService(
                catalog, documents, loader, tools, settings, polls, resolver, "https://vance.example");
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

    @Test
    void aPollingTriggerMakesASchedulerAndTheWorkflowItStarts_bothValid() {
        var created = service.createEvent("acme", "p", user, "github", "new-message", "on-message", "inbox", Map.of());

        assertThat(created.getMode()).isEqualTo("poll");
        assertThat(created.getPath()).isEqualTo("_vance/scheduler/inbox.yaml");
        assertThat(created.getUrlPath()).isNull();
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> yamls = ArgumentCaptor.forClass(String.class);
        verify(documents, org.mockito.Mockito.times(2))
                .createText(any(), any(), paths.capture(), any(), any(), yamls.capture(), any(), any());
        // The workflow first, so the scheduler never points at nothing.
        assertThat(paths.getAllValues())
                .containsExactly("_vance/workflows/catalog-poll-inbox.yaml", "_vance/scheduler/inbox.yaml");

        var workflow = de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader.parseYaml(
                "catalog-poll-inbox", yamls.getAllValues().get(0));
        assertThat(workflow.yaml())
                .contains("catalog_poll")
                .contains("catalog_poll_commit")
                .contains("on-message");
        var scheduler = de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader.parseValidated(
                "inbox", yamls.getAllValues().get(1));
        assertThat(scheduler.cron()).isEqualTo("0 0/5 * * * *");
        assertThat(scheduler.workflow()).isEqualTo("catalog-poll-inbox");
    }

    @Test
    void aPollingTriggerThatExistsIsNotMadeTwice() {
        when(documents.findByPath("acme", "p", "_vance/scheduler/inbox.yaml"))
                .thenReturn(Optional.of(mock(DocumentDocument.class)));

        assertThatThrownBy(
                        () -> service.createEvent("acme", "p", user, "github", "new-message", "w", "inbox", Map.of()))
                .hasMessageContaining("exists already");
    }

    @Test
    void deletingAPollingTriggerRemovesSchedulerWorkflowAndState() {
        UrsaEventLoader loader = mock(UrsaEventLoader.class);
        when(loader.load("acme", "p", "inbox")).thenReturn(Optional.empty());
        DocumentDocument scheduler = mock(DocumentDocument.class);
        when(scheduler.getId()).thenReturn("s-1");
        DocumentDocument workflow = mock(DocumentDocument.class);
        when(workflow.getId()).thenReturn("w-1");
        when(documents.findByPath("acme", "p", "_vance/scheduler/inbox.yaml")).thenReturn(Optional.of(scheduler));
        when(documents.findByPath("acme", "p", "_vance/workflows/catalog-poll-inbox.yaml"))
                .thenReturn(Optional.of(workflow));
        var svc = new CatalogTriggerService(
                catalog, documents, loader, tools, settings, polls, resolver, "https://vance.example");

        svc.deleteEvent("acme", "p", user, "inbox", false);

        var order = org.mockito.Mockito.inOrder(documents, polls);
        order.verify(documents).delete(eq("s-1"), any());
        order.verify(documents).delete(eq("w-1"), any());
        order.verify(polls).forget("acme", "p", "inbox");
    }

    @Test
    void deletingSomethingThatIsNeitherAnEventNorAPollingTriggerIsNotFound() {
        UrsaEventLoader loader = mock(UrsaEventLoader.class);
        when(loader.load("acme", "p", "nope")).thenReturn(Optional.empty());
        var svc = new CatalogTriggerService(
                catalog, documents, loader, tools, settings, polls, resolver, "https://vance.example");

        assertThatThrownBy(() -> svc.deleteEvent("acme", "p", user, "nope", false))
                .hasMessageContaining("not found");
    }

    @Test
    void whatThePersonSaysFillsTheTemplateTheRegistrationAndTheRemoval() {
        // The short names are resolved against the packs the entry describes.
        when(tools.resolveAll(any())).thenReturn(List.of());
        var named = new CatalogToolResolver(tools) {
            @Override
            public String resolve(ServiceCatalogEntry entry, String tool, ToolInvocationContext ctx) {
                return "github_rest__" + tool;
            }
        };
        var svc = new CatalogTriggerService(
                catalog, documents, eventLoader, tools, settings, polls, named, "https://vance.example");
        when(tools.invoke(eq("github_rest__create_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 201, "json", Map.of("id", 77)));

        var created = svc.createEvent(
                "acme", "p", user, "github", "repo-hook", "w", null, Map.of("owner", "mhus", "repo", "vance"));

        assertThat(created.getName()).isEqualTo("github-repo-hook");
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(tools).invoke(eq("github_rest__create_hook"), params.capture(), any(ToolInvocationContext.class));
        assertThat(params.getValue())
                .containsEntry("owner", "mhus")
                .containsEntry("repo", "vance")
                .containsEntry("url", "https://vance.example/brain/acme/event/p/github-repo-hook");
        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents).createText(any(), any(), any(), any(), any(), yaml.capture(), any(), any());
        ResolvedUrsaEvent event = eventLoader.validateYaml("github-repo-hook", yaml.getValue());
        assertThat(event.idempotencyHeader()).isEqualTo("X-Delivery-vance");
        // Kept: the removal needs the repository again.
        assertThat(event.inputs()).containsEntry("owner", "mhus").containsEntry("repo", "vance");
        assertThat(event.subscription()).isEqualTo("77");
    }

    @Test
    void theRemovalGetsWhatWasSaidWhenTheEventWasMade() {
        var named = new CatalogToolResolver(tools) {
            @Override
            public String resolve(ServiceCatalogEntry entry, String tool, ToolInvocationContext ctx) {
                return "github_rest__" + tool;
            }
        };
        ResolvedUrsaEvent event = eventLoader.validateYaml("github-repo-hook", """
                workflow: w
                from: github/repo-hook
                subscription: "77"
                inputs: { owner: mhus, repo: vance }
                auth: { tokenSetting: events.x.secret }
                """);
        UrsaEventLoader loader = mock(UrsaEventLoader.class);
        when(loader.load("acme", "p", "github-repo-hook")).thenReturn(Optional.of(event));
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("d-1");
        when(documents.findByPath("acme", "p", "_vance/events/github-repo-hook.yaml"))
                .thenReturn(Optional.of(doc));
        when(tools.invoke(eq("github_rest__delete_hook"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 204));
        var svc = new CatalogTriggerService(
                catalog, documents, loader, tools, settings, polls, named, "https://vance.example");

        svc.deleteEvent("acme", "p", user, "github-repo-hook", false);

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(tools).invoke(eq("github_rest__delete_hook"), params.capture(), any(ToolInvocationContext.class));
        assertThat(params.getValue())
                .containsEntry("owner", "mhus")
                .containsEntry("repo", "vance")
                .containsEntry("id", "77");
    }

    @Test
    void aMissingRequiredInputAndAnUnknownOneAreRefused() {
        assertThatThrownBy(() -> service.createEvent(
                        "acme", "p", user, "github", "repo-hook", "w", null, Map.of("owner", "mhus")))
                .hasMessageContaining("needs 'repo'");
        assertThatThrownBy(() -> service.createEvent(
                        "acme",
                        "p",
                        user,
                        "github",
                        "repo-hook",
                        "w",
                        null,
                        Map.of("owner", "a", "repo", "b", "ownr", "typo")))
                .hasMessageContaining("no input 'ownr'");
        verify(documents, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void thePollingWorkflowCarriesTheInputsToEveryTick() {
        service.createEvent("acme", "p", user, "github", "room-poll", "w", "rooms", Map.of("room", "R1"));

        ArgumentCaptor<String> yamls = ArgumentCaptor.forClass(String.class);
        verify(documents, org.mockito.Mockito.times(2))
                .createText(any(), any(), any(), any(), any(), yamls.capture(), any(), any());
        var workflow = de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader.parseYaml(
                "catalog-poll-rooms", yamls.getAllValues().get(0));
        assertThat(workflow.yaml()).contains("inputs:").contains("room: R1");
    }
}
