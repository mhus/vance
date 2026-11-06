package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.api.tools.CatalogTriggerCreateResponse;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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
@RequiredArgsConstructor
public class CatalogTriggerService {

    private static final String EVENTS_PREFIX = "_vance/events/";

    private final ServiceCatalogLoader catalog;
    private final DocumentService documents;
    private final UrsaEventLoader eventLoader;

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
        if (!doc.containsKey("auth")) {
            // No template says how the caller proves itself. An event that is open must say so; so does
            // one that is not, and the bearer token is the plain default.
            doc.put("auth", Map.of("tokenSetting", secretSetting));
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = new Yaml(options).dump(doc);
        try {
            eventLoader.validateYaml(eventName, yaml);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "The trigger template makes no valid event: " + e.getMessage(), e);
        }
        documents.createText(
                tenantId,
                projectId,
                path,
                eventName,
                List.of("event", "catalog"),
                yaml,
                subject.subjectId(),
                WriteActor.user(subject));

        return CatalogTriggerCreateResponse.builder()
                .name(eventName)
                .path(path)
                .urlPath("/brain/" + tenantId + "/event/" + projectId + "/" + eventName)
                .secretSetting(isOpen(doc) ? null : secretSetting)
                .build();
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
