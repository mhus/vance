package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.tools.CatalogTriggerCreateResponse;
import de.mhus.vance.brain.magrathea.ToolFailure;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceCatalogLoader catalog;
    private final DocumentService documents;
    private final UrsaEventLoader eventLoader;
    private final ToolDispatcher tools;
    private final SettingService settings;
    private final String publicBaseUrl;

    public CatalogTriggerService(
            ServiceCatalogLoader catalog,
            DocumentService documents,
            UrsaEventLoader eventLoader,
            ToolDispatcher tools,
            SettingService settings,
            @Value("${vance.web.public-base-url:}") String publicBaseUrl) {
        this.catalog = catalog;
        this.documents = documents;
        this.eventLoader = eventLoader;
        this.tools = tools;
        this.settings = settings;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public CatalogTriggerCreateResponse createEvent(
            String tenantId,
            String projectId,
            SecurityContext subject,
            String service,
            String trigger,
            String workflow,
            @Nullable String name) {
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
        if (t.mode() != ServiceCatalogEntry.TriggerMode.WEBHOOK) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Trigger '" + trigger + "' polls: it makes a scheduler, not an event");
        }

        String eventName = (name == null || name.isBlank() ? entry.name() + "-" + t.name() : name)
                .trim()
                .toLowerCase(Locale.ROOT);
        String path = EVENTS_PREFIX + eventName + ".yaml";
        if (documents.findByPath(tenantId, projectId, path).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event '" + eventName + "' exists already");
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        String description = ServiceCatalogEntry.localized(t.description(), "en");
        if (!description.isBlank()) doc.put("description", description);
        doc.put("from", entry.name() + "/" + t.name());
        doc.put("workflow", workflow.trim());
        if (t.event() != null) doc.putAll(t.event());
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

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            eventLoader.validateYaml(eventName, new Yaml(options).dump(doc));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "The trigger template makes no valid event: " + e.getMessage(), e);
        }

        if (t.subscribe() != null) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("url", publicBaseUrl + urlPath);
            values.put("secret", secret);
            values.put("event", eventName);
            subscription = register(tenantId, projectId, subject.subjectId(), t.subscribe(), values);
            if (subscription != null) doc.put("subscription", subscription);
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
            if (t.subscribe() != null && t.unsubscribe() != null) {
                try {
                    call(
                            tenantId,
                            projectId,
                            subject.subjectId(),
                            t.unsubscribe(),
                            subscriptionValues(eventName, subscription));
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
                .build();
    }

    /**
     * Removes an event that was made from a catalog trigger: the registration at the service first, then the
     * document. When the registration cannot be removed the event stays — deleting it would leave the service
     * calling a URL nobody answers. {@code force} deletes anyway.
     */
    public void deleteEvent(
            String tenantId, String projectId, SecurityContext subject, String eventName, boolean force) {
        String name = eventName.trim().toLowerCase(Locale.ROOT);
        ResolvedUrsaEvent event = eventLoader
                .load(tenantId, projectId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event '" + name + "' not found"));
        String path = EVENTS_PREFIX + name + ".yaml";
        DocumentDocument document = documents
                .findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Event '" + name + "' is not a document of this project"));
        if (event.from() != null && event.subscription() != null) {
            Optional<ServiceCatalogEntry.Registration> removal = removalOf(tenantId, projectId, event.from());
            if (removal.isPresent()) {
                try {
                    call(
                            tenantId,
                            projectId,
                            subject.subjectId(),
                            removal.get(),
                            subscriptionValues(name, event.subscription()));
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

    private Optional<ServiceCatalogEntry.Registration> removalOf(String tenantId, String projectId, String from) {
        int slash = from.indexOf('/');
        if (slash < 0) return Optional.empty();
        String service = from.substring(0, slash);
        String trigger = from.substring(slash + 1);
        return catalog.list(tenantId, projectId).stream()
                .filter(e -> e.name().equalsIgnoreCase(service))
                .flatMap(e -> e.triggers().stream())
                .filter(x -> x.name().equalsIgnoreCase(trigger))
                .findFirst()
                .map(ServiceCatalogEntry.Trigger::unsubscribe);
    }

    private static Map<String, String> subscriptionValues(String eventName, @Nullable String subscription) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("event", eventName);
        if (subscription != null) values.put("subscription", subscription);
        return values;
    }

    /** Calls the registration; returns the id the service gave it, or {@code null} when none is named. */
    private @Nullable String register(
            String tenantId,
            String projectId,
            String userId,
            ServiceCatalogEntry.Registration registration,
            Map<String, String> values) {
        Map<String, Object> result = call(tenantId, projectId, userId, registration, values);
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
            ServiceCatalogEntry.Registration registration,
            Map<String, String> values) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) substitute(registration.params(), values);
        Map<String, Object> result;
        try {
            result = tools.invoke(
                    registration.tool(),
                    params,
                    new ToolInvocationContext(tenantId, projectId, /* sessionId */ null, /* processId */ null, userId));
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
