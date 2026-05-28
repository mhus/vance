package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.api.ursaevents.EventTriggerResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoint for external event triggers. Mapped at
 * {@code /brain/{tenant}/event/{project}/{event}} — the path order
 * deliberately differs from {@code /brain/{tenant}/project/{project}/...}
 * so the dedicated bypass in
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} is unambiguous:
 * event triggers don't carry a JWT, only an optional bearer token
 * (resolved internally against the YAML / setting cascade).
 *
 * <p>Body handling: POSTs must carry a JSON body
 * ({@code Content-Type: application/json}) which is parsed once and
 * passed to the workflow as {@code params.payload}. Empty body is
 * fine; non-JSON body → 415.
 *
 * <p>Response: 200 sync with a small JSON envelope carrying the
 * spawned {@code workflowRunId}. The workflow itself runs
 * asynchronously on the project lane — the caller doesn't wait for
 * completion.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class UrsaEventController {

    private final UrsaEventService eventService;
    private final ObjectMapper objectMapper;

    @GetMapping("/brain/{tenant}/event/{project}/{event}")
    public EventTriggerResponse triggerGet(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String event,
            HttpServletRequest request) {
        String bearer = extractBearer(request);
        UrsaEventService.UrsaEventTriggerResult result = eventService.trigger(
                tenant, project, event,
                "GET", bearer, /*payload*/ null);
        return new EventTriggerResponse(event, result.workflowName(), result.workflowRunId());
    }

    @PostMapping("/brain/{tenant}/event/{project}/{event}")
    public EventTriggerResponse triggerPost(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String event,
            HttpServletRequest request) {
        String bearer = extractBearer(request);
        Object payload = readJsonBody(request);
        UrsaEventService.UrsaEventTriggerResult result = eventService.trigger(
                tenant, project, event,
                "POST", bearer, payload);
        return new EventTriggerResponse(event, result.workflowName(), result.workflowRunId());
    }

    /**
     * Reads the request body as JSON when present. Empty body → {@code null}
     * (omits the {@code payload} key). Non-JSON content-type → 415.
     */
    private @Nullable Object readJsonBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        byte[] raw;
        try {
            raw = request.getInputStream().readAllBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to read request body: " + ex.getMessage(), ex);
        }
        if (raw.length == 0) {
            return null;
        }
        if (contentType == null || !contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Event POST body must be application/json");
        }
        try {
            return objectMapper.readValue(new String(raw, StandardCharsets.UTF_8), Object.class);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Event POST body is not valid JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * Reads the bearer token from the {@code Authorization} header.
     * Returns {@code null} when no header is set — the service-side
     * auth check then decides whether to demand one based on the YAML.
     */
    private static @Nullable String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return null;
    }
}
