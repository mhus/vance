package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.ursaevents.WebhookCreateResponse;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Makes and removes a plain webhook event: an ordinary {@code _vance/events/<name>.yaml} that starts a workflow
 * when its URL is called with a bearer token. The token is generated here, kept as a password setting
 * ({@code events.<name>.token}) and handed back once; the document only names the setting, never the value.
 *
 * <p>Events made from a service catalog trigger are the catalog's business ({@code CatalogTriggerService}): one
 * that is registered at a service has to be unregistered there first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DocumentService documents;
    private final UrsaEventLoader eventLoader;
    private final SettingService settings;

    /** The setting that holds the token of the event {@code name}. */
    static String tokenSettingOf(String name) {
        return "events." + name + ".token";
    }

    public WebhookCreateResponse create(
            String tenantId,
            String projectId,
            SecurityContext subject,
            @Nullable String workflow,
            @Nullable String name,
            @Nullable String description) {
        if (workflow == null || workflow.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A workflow to start is required");
        }
        String target = workflow.trim();
        String eventName =
                (name == null || name.isBlank() ? target : name).trim().toLowerCase(Locale.ROOT);
        if (!UrsaEventLoader.NAME.matcher(eventName).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "An event name is lowercase letters, digits, '-', '_' or '.', and starts with a letter or digit");
        }
        String path = UrsaEventLoader.EVENT_PATH_PREFIX + eventName + UrsaEventLoader.EVENT_PATH_SUFFIX;
        if (documents.findByPath(tenantId, projectId, path).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event '" + eventName + "' exists already");
        }

        String tokenSetting = tokenSettingOf(eventName);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("description", description == null || description.isBlank() ? "Starts " + target : description.trim());
        doc.put("workflow", target);
        doc.put("methods", List.of("POST"));
        doc.put("auth", Map.of("tokenSetting", tokenSetting));

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = new Yaml(options).dump(doc);
        try {
            eventLoader.validateYaml(eventName, yaml);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid event: " + e.getMessage(), e);
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        // The token first: an event whose setting is missing would answer every call with 401.
        settings.setAs(
                tenantId,
                SettingService.SCOPE_PROJECT,
                projectId,
                tokenSetting,
                token,
                SettingType.PASSWORD,
                "Token of the event '" + eventName + "'",
                subject.subjectId());
        try {
            documents.createText(
                    tenantId,
                    projectId,
                    path,
                    eventName,
                    List.of("event"),
                    yaml,
                    subject.subjectId(),
                    WriteActor.user(subject));
        } catch (RuntimeException e) {
            try {
                settings.delete(tenantId, SettingService.SCOPE_PROJECT, projectId, tokenSetting);
            } catch (RuntimeException undo) {
                log.warn("Could not remove the token '{}' of '{}': {}", tokenSetting, eventName, undo.getMessage());
            }
            throw e;
        }
        return WebhookCreateResponse.builder()
                .name(eventName)
                .path(path)
                .urlPath("/brain/" + tenantId + "/event/" + projectId + "/" + eventName)
                .tokenSetting(tokenSetting)
                .token(token)
                .build();
    }

    /**
     * Removes an event of this project and the token this service made for it. An event that was registered at a
     * service through the catalog is refused: deleting it here would leave the service calling a URL nobody answers.
     */
    public void delete(String tenantId, String projectId, SecurityContext subject, String eventName) {
        String name = eventName.trim().toLowerCase(Locale.ROOT);
        ResolvedUrsaEvent event = eventLoader
                .load(tenantId, projectId, name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Event '" + name + "' not found in project '" + projectId + "'"));
        if (event.subscription() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Event '" + name + "' is registered at a service; remove it through workflow-triggers");
        }
        DocumentDocument document = documents
                .findByPath(
                        tenantId,
                        projectId,
                        UrsaEventLoader.EVENT_PATH_PREFIX + name + UrsaEventLoader.EVENT_PATH_SUFFIX)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Event '" + name + "' is not a document of this project"));
        documents.delete(document.getId(), WriteActor.user(subject));
        // Only the token this service would have made: a setting shared with other events stays.
        if (tokenSettingOf(name).equals(event.tokenSettingKey())) {
            settings.delete(tenantId, SettingService.SCOPE_PROJECT, projectId, tokenSettingOf(name));
        }
    }
}
