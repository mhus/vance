package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.tools.CatalogTriggerCreateResponse;
import de.mhus.vance.brain.magrathea.ToolFailure;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Makes an event document from a trigger of the service catalog. The trigger is only a template: what is written
 * is an ordinary {@code _vance/events/<name>.yaml}, validated by the event loader like any other, and from then
 * on nothing ties it to the catalog except the informational {@code from:}.
 */
@Service
@Slf4j
public class CatalogTriggerService {

    private static final String EVENTS_PREFIX = "_vance/events/";

    /** What an event name may be: it becomes a document path, a setting key and a URL segment. */
    private static final java.util.regex.Pattern EVENT_NAME =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private static final String SCHEDULER_PREFIX = "_vance/scheduler/";
    private static final String WORKFLOW_PREFIX = "_vance/workflows/";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceCatalogLoader catalog;
    private final DocumentService documents;
    private final UrsaEventLoader eventLoader;
    private final ToolDispatcher tools;
    private final SettingService settings;
    private final CatalogPollService polls;
    private final CatalogToolResolver resolver;
    private final String publicBaseUrl;

    public CatalogTriggerService(
            ServiceCatalogLoader catalog,
            DocumentService documents,
            UrsaEventLoader eventLoader,
            ToolDispatcher tools,
            SettingService settings,
            CatalogPollService polls,
            CatalogToolResolver resolver,
            @Value("${vance.web.public-base-url:}") String publicBaseUrl) {
        this.catalog = catalog;
        this.documents = documents;
        this.eventLoader = eventLoader;
        this.tools = tools;
        this.settings = settings;
        this.polls = polls;
        this.resolver = resolver;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public CatalogTriggerCreateResponse createEvent(
            String tenantId,
            String projectId,
            SecurityContext subject,
            String service,
            String trigger,
            String workflow,
            @Nullable String name,
            Map<String, String> inputs) {
        if (workflow == null || workflow.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A workflow to start is required");
        }
        List<ServiceCatalogEntry> entries = catalog.list(tenantId, projectId);
        ServiceCatalogEntry entry = entries.stream()
                .filter(e -> e.name().equalsIgnoreCase(service))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Service '" + service + "' is not in the catalog"));
        ServiceCatalogEntry.Trigger t = entry.triggers().stream()
                .filter(x -> x.name().equalsIgnoreCase(trigger))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Service '" + service + "' has no trigger '" + trigger + "'"));
        Map<String, String> given = checkedInputs(t, inputs);
        String eventName = (name == null || name.isBlank() ? entry.name() + "-" + t.name() : name)
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!EVENT_NAME.matcher(eventName).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "An event name is lowercase letters, digits, '-', '_' or '.', and starts with a letter or digit");
        }
        if (t.mode() == ServiceCatalogEntry.TriggerMode.POLL) {
            return createPoll(tenantId, projectId, subject, entry, t, workflow.trim(), eventName, given);
        }
        String path = EVENTS_PREFIX + eventName + ".yaml";
        if (documents.findByPath(tenantId, projectId, path).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event '" + eventName + "' exists already");
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        String description = ServiceCatalogEntry.localized(t.description(), "en");
        if (!description.isBlank()) doc.put("description", description);
        doc.put("from", entry.name() + "/" + t.name());
        doc.put("workflow", workflow.trim());
        if (t.event() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> template = (Map<String, Object>) substitute(t.event(), inputValues(given));
            doc.putAll(template);
        }
        if (!given.isEmpty()) doc.put("inputs", given);
        String secretSetting = secretSettingOf(doc, eventName);
        if (t.subscribe() != null) {
            // The service is told the secret, so it has to be this event's own: a key shared by several events
            // would be overwritten by each of them.
            secretSetting = "events." + eventName + ".secret";
        }
        withSecretSetting(doc, secretSetting);

        String urlPath = "/brain/" + tenantId + "/event/" + projectId + "/" + eventName;
        String secret = null;
        String subscription = null;
        if (t.subscribe() != null) {
            if (publicBaseUrl.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The service has to be told where to call, but vance.web.public-base-url is not set");
            }
            secret = HexFormat.of().formatHex(randomBytes(32));
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            eventLoader.validateYaml(eventName, new Yaml(options).dump(doc));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "The trigger template makes no valid event: " + e.getMessage(), e);
        }

        if (t.subscribe() != null) {
            Map<String, String> values = new LinkedHashMap<>(inputValues(given));
            values.put("url", publicBaseUrl + urlPath);
            values.put("secret", secret);
            values.put("event", eventName);
            subscription = register(tenantId, projectId, subject.subjectId(), entry, t.subscribe(), values);
            if (subscription != null) doc.put("subscription", subscription);
            // Stored only now that the service has accepted the registration, so a refused attempt leaves nothing.
            settings.setAs(
                    tenantId,
                    SettingService.SCOPE_PROJECT,
                    projectId,
                    secretSetting,
                    secret,
                    SettingType.PASSWORD,
                    "Secret of the event '" + eventName + "'",
                    subject.subjectId());
        }
        try {
            documents.createText(
                    tenantId,
                    projectId,
                    path,
                    eventName,
                    List.of("event", "catalog"),
                    new Yaml(options).dump(doc),
                    subject.subjectId(),
                    WriteActor.user(subject));
        } catch (RuntimeException e) {
            // The registration at the service has no event to point at now: take it back, or it keeps calling.
            if (t.subscribe() != null) {
                try {
                    settings.delete(tenantId, SettingService.SCOPE_PROJECT, projectId, secretSetting);
                } catch (RuntimeException undo) {
                    log.warn(
                            "Could not remove the secret '{}' of '{}': {}",
                            secretSetting,
                            eventName,
                            undo.getMessage());
                }
            }
            if (t.subscribe() != null && t.unsubscribe() != null) {
                try {
                    call(
                            tenantId,
                            projectId,
                            subject.subjectId(),
                            entry,
                            t.unsubscribe(),
                            subscriptionValues(eventName, subscription, given));
                } catch (RuntimeException undo) {
                    log.warn(
                            "Could not take back the registration of '{}' at '{}': {}",
                            eventName,
                            service,
                            undo.getMessage());
                }
            }
            throw e;
        }

        return CatalogTriggerCreateResponse.builder()
                .name(eventName)
                .path(path)
                .urlPath(urlPath)
                .secretSetting(isOpen(doc) ? null : (t.subscribe() != null ? null : secretSetting))
                .subscribed(t.subscribe() != null)
                .mode("webhook")
                .build();
    }

    /**
     * A polling trigger makes two documents: a scheduler that ticks, and the workflow it starts. The workflow asks
     * the service ({@code catalog_poll}), starts the target workflow once per new entry, and remembers what it saw
     * ({@code catalog_poll_commit}) last, so a tick that dies in between is repeated.
     */
    private CatalogTriggerCreateResponse createPoll(
            String tenantId,
            String projectId,
            SecurityContext subject,
            ServiceCatalogEntry entry,
            ServiceCatalogEntry.Trigger t,
            String targetWorkflow,
            String name,
            Map<String, String> given) {
        String from = entry.name() + "/" + t.name();
        String schedulerPath = SCHEDULER_PREFIX + name + ".yaml";
        String workflowPath = WORKFLOW_PREFIX + pollWorkflowName(name) + ".yaml";
        if (documents.findByPath(tenantId, projectId, schedulerPath).isPresent()
                || documents.findByPath(tenantId, projectId, workflowPath).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trigger '" + name + "' exists already");
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String workflowYaml = new Yaml(options).dump(pollWorkflow(from, name, targetWorkflow, given));
        String schedulerYaml = new Yaml(options).dump(pollScheduler(t, from, pollWorkflowName(name)));
        try {
            MagratheaWorkflowLoader.parseYaml(pollWorkflowName(name), workflowYaml);
            UrsaSchedulerLoader.parseValidated(name, schedulerYaml);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The trigger template makes no valid scheduler: " + e.getMessage(),
                    e);
        }
        // The workflow first: a scheduler that points at nothing would fail every tick.
        documents.createText(
                tenantId,
                projectId,
                workflowPath,
                pollWorkflowName(name),
                List.of("workflow", "catalog"),
                workflowYaml,
                subject.subjectId(),
                WriteActor.user(subject));
        try {
            documents.createText(
                    tenantId,
                    projectId,
                    schedulerPath,
                    name,
                    List.of("scheduler", "catalog"),
                    schedulerYaml,
                    subject.subjectId(),
                    WriteActor.user(subject));
        } catch (RuntimeException e) {
            documents
                    .findByPath(tenantId, projectId, workflowPath)
                    .ifPresent(d -> documents.delete(d.getId(), WriteActor.user(subject)));
            throw e;
        }
        return CatalogTriggerCreateResponse.builder()
                .name(name)
                .path(schedulerPath)
                .mode("poll")
                .build();
    }

    static String pollWorkflowName(String name) {
        return "catalog-poll-" + name;
    }

    /** The workflow a tick runs: poll, one run of the target per new entry, then commit. */
    private static Map<String, Object> pollWorkflow(
            String from, String name, String targetWorkflow, Map<String, String> given) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$meta", Map.of("kind", "vance-workflow"));
        doc.put(
                "description",
                "Polls " + from + " for '" + name + "' and starts '" + targetWorkflow + "' per new entry");
        doc.put("start", "poll");

        Map<String, Object> poll = new LinkedHashMap<>();
        poll.put("type", "tool_task");
        poll.put("tool", "catalog_poll");
        Map<String, Object> pollParams = new LinkedHashMap<>();
        pollParams.put("name", name);
        pollParams.put("from", from);
        if (!given.isEmpty()) pollParams.put("inputs", given);
        poll.put("params", pollParams);
        poll.put("storeAs", "polled");
        // A service that is busy or briefly away is asked again; one that wants a new sign-in is not.
        poll.put("retry", Map.of("maxAttempts", 3, "on", List.of("rate_limited", "unavailable"), "backoffSeconds", 30));
        poll.put("on", Map.of("success", "fan_out"));

        Map<String, Object> fan = new LinkedHashMap<>();
        fan.put("type", "workflow_task");
        fan.put("workflow", targetWorkflow);
        fan.put("items", "${state.polled.items}");
        fan.put("as", "payload");
        // One entry that fails must not hold back the others, nor be asked for again for ever.
        fan.put("onItemError", "continue");
        fan.put("on", Map.of("success", "commit"));

        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("type", "tool_task");
        commit.put("tool", "catalog_poll_commit");
        commit.put("params", Map.of("name", name, "pending", "${state.polled.pending}"));
        commit.put("on", Map.of("success", "done"));

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("type", "terminal");
        done.put("outcome", "success");

        Map<String, Object> states = new LinkedHashMap<>();
        states.put("poll", poll);
        states.put("fan_out", fan);
        states.put("commit", commit);
        states.put("done", done);
        doc.put("states", states);
        return doc;
    }

    private static Map<String, Object> pollScheduler(ServiceCatalogEntry.Trigger t, String from, String workflow) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$meta", Map.of("kind", "vance-scheduler"));
        doc.put("description", "Polls " + from);
        doc.put("cron", t.poll().schedule());
        doc.put("workflow", workflow);
        // A tick that is still running is not started a second time: it would see the same entries.
        doc.put("overlap", "skip");
        doc.put("tags", List.of("catalog", from));
        return doc;
    }

    /**
     * Removes what a polling trigger made: the scheduler, the workflow it starts and the state it kept.
     */
    private void deletePoll(String tenantId, String projectId, SecurityContext subject, String name) {
        DocumentDocument workflow = documents
                .findByPath(tenantId, projectId, WORKFLOW_PREFIX + pollWorkflowName(name) + ".yaml")
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trigger '" + name + "' not found"));
        // The scheduler first: nothing should tick for a workflow that is going away.
        documents
                .findByPath(tenantId, projectId, SCHEDULER_PREFIX + name + ".yaml")
                .ifPresent(d -> documents.delete(d.getId(), WriteActor.user(subject)));
        documents.delete(workflow.getId(), WriteActor.user(subject));
        polls.forget(tenantId, projectId, name);
    }

    /**
     * Removes an event that was made from a catalog trigger, or a polling trigger by the same name: the registration at the service first, then the
     * document. When the registration cannot be removed the event stays — deleting it would leave the service
     * calling a URL nobody answers. {@code force} deletes anyway.
     */
    public void deleteEvent(
            String tenantId, String projectId, SecurityContext subject, String eventName, boolean force) {
        String name = eventName.trim().toLowerCase(Locale.ROOT);
        Optional<ResolvedUrsaEvent> loaded = eventLoader.load(tenantId, projectId, name);
        if (loaded.isEmpty()) {
            deletePoll(tenantId, projectId, subject, name);
            return;
        }
        ResolvedUrsaEvent event = loaded.get();
        String path = EVENTS_PREFIX + name + ".yaml";
        DocumentDocument document = documents
                .findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Event '" + name + "' is not a document of this project"));
        if (event.from() != null && event.subscription() != null) {
            Optional<ServiceCatalogLoader.Located> located = catalog.locate(tenantId, projectId, event.from());
            if (located.isPresent() && located.get().trigger().unsubscribe() != null) {
                try {
                    call(
                            tenantId,
                            projectId,
                            subject.subjectId(),
                            located.get().entry(),
                            located.get().trigger().unsubscribe(),
                            subscriptionValues(name, event.subscription(), event.inputs()));
                } catch (RuntimeException e) {
                    if (!force) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "The registration at the service could not be removed, the event stays: "
                                        + e.getMessage(),
                                e);
                    }
                    log.warn(
                            "Event '{}' deleted although its registration could not be removed: {}",
                            name,
                            e.getMessage());
                }
            }
        }
        documents.delete(document.getId(), WriteActor.user(subject));
    }

    /** What the person said, as the placeholder values {@code input.<name>}. */
    private static Map<String, String> inputValues(Map<String, String> given) {
        Map<String, String> values = new LinkedHashMap<>();
        given.forEach((k, v) -> values.put("input." + k, v));
        return values;
    }

    /**
     * The inputs checked against what the trigger asks for: every required one is there, none is unknown.
     * Unknown ones are refused rather than dropped — a typo in a name would otherwise leave the value unused
     * without a word.
     */
    private static Map<String, String> checkedInputs(ServiceCatalogEntry.Trigger t, Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ServiceCatalogEntry.Input input : t.inputs()) {
            String value = given.get(input.name());
            if (value == null || value.isBlank()) {
                if (input.required()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "The trigger '" + t.name() + "' needs '" + input.name() + "'");
                }
                continue;
            }
            out.put(input.name(), value.trim());
        }
        for (String key : given.keySet()) {
            if (t.inputs().stream().noneMatch(i -> i.name().equals(key))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The trigger '" + t.name() + "' has no input '" + key + "'");
            }
        }
        return out;
    }

    private static Map<String, String> subscriptionValues(
            String eventName, @Nullable String subscription, Map<String, String> given) {
        Map<String, String> values = new LinkedHashMap<>(inputValues(given));
        values.put("event", eventName);
        if (subscription != null) values.put("subscription", subscription);
        return values;
    }

    /** Calls the registration; returns the id the service gave it, or {@code null} when none is named. */
    private @Nullable String register(
            String tenantId,
            String projectId,
            String userId,
            ServiceCatalogEntry entry,
            ServiceCatalogEntry.Registration registration,
            Map<String, String> values) {
        Map<String, Object> result = call(tenantId, projectId, userId, entry, registration, values);
        if (registration.idFrom() == null) return null;
        Object id = valueAt(result, registration.idFrom());
        if (id == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The service answered, but there is no registration id at '" + registration.idFrom() + "'");
        }
        return String.valueOf(id);
    }

    private Map<String, Object> call(
            String tenantId,
            String projectId,
            String userId,
            ServiceCatalogEntry entry,
            ServiceCatalogEntry.Registration registration,
            Map<String, String> values) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) substitute(registration.params(), values);
        Map<String, Object> result;
        try {
            ToolInvocationContext context =
                    new ToolInvocationContext(tenantId, projectId, /* sessionId */ null, /* processId */ null, userId);
            result = tools.invoke(resolver.resolve(entry, registration.tool(), context), params, context);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "'" + registration.tool() + "' failed: " + e.getMessage(), e);
        }
        Optional<ToolFailure> failure = ToolFailure.classifyResult(result);
        if (failure.isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "'" + registration.tool() + "' failed: " + failure.get().message());
        }
        return result;
    }

    /** {@code ${name}} in every string of {@code value}, nested maps and lists included; unknown names stay as written. */
    static Object substitute(Object value, Map<String, String> values) {
        if (value instanceof String text) {
            String out = text;
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), substitute(v, values)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>();
            for (Object item : list) out.add(substitute(item, values));
            return out;
        }
        return value;
    }

    /** The value at a dotted path ({@code json.id}) in nested maps, or {@code null}. */
    static @Nullable Object valueAt(Map<String, Object> result, String path) {
        Object current = result;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(part);
        }
        return current;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** The doc's {@code auth} with its secret setting pointed at {@code key}; maps are copied, the template stays as it was. */
    @SuppressWarnings("unchecked")
    private static void withSecretSetting(Map<String, Object> doc, String key) {
        Map<String, Object> auth = doc.get("auth") instanceof Map<?, ?> a
                ? new LinkedHashMap<>((Map<String, Object>) a)
                : new LinkedHashMap<>();
        if (auth.isEmpty()) {
            // No template says how the caller proves itself. An event that is open must say so; so does
            // one that is not, and the bearer token is the plain default.
            auth.put("tokenSetting", key);
        } else if (auth.get("signature") instanceof Map<?, ?> signature) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) signature);
            copy.put("secretSetting", key);
            auth.put("signature", copy);
        } else if (auth.containsKey("tokenSetting")) {
            auth.put("tokenSetting", key);
        }
        doc.put("auth", auth);
    }

    /** The setting that has to hold the secret: the signature's, the template's token setting, or a default. */
    @SuppressWarnings("unchecked")
    private static String secretSettingOf(Map<String, Object> doc, String eventName) {
        if (doc.get("auth") instanceof Map<?, ?> auth) {
            if (auth.get("signature") instanceof Map<?, ?> signature && signature.get("secretSetting") != null) {
                return String.valueOf(signature.get("secretSetting"));
            }
            if (auth.get("tokenSetting") != null) return String.valueOf(auth.get("tokenSetting"));
        }
        return "events." + eventName + ".token";
    }

    private static boolean isOpen(Map<String, Object> doc) {
        return doc.get("auth") instanceof Map<?, ?> auth && Boolean.TRUE.equals(auth.get("public"));
    }
}
