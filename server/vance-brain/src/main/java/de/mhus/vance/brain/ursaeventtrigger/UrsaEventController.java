package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.api.ursaevents.EventTriggerResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
 * <p>Routing: the trigger runs on the pod that holds the project. If none
 * does, the project is brought up first and the call waits for that — a cold
 * start on the first trigger is the price for not keeping every
 * webhook-carrying project on a pod year-round (see
 * {@code ProjectOwnerRequirementService}).
 *
 * <p>Response: 200 sync with a small JSON envelope carrying the
 * spawned {@code workflowRunId}. The workflow itself runs
 * asynchronously on the project lane — the caller doesn't wait for
 * completion.
 */
@RestController
@Slf4j
public class UrsaEventController {

    /** Default for {@code vance.events.max-body-bytes}: one MiB, far above any webhook payload that is not a file. */
    static final long DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    private final UrsaEventService eventService;
    private final ObjectMapper objectMapper;
    private final UrsaEventForwarder forwarder;
    private final long maxBodyBytes;

    UrsaEventController(UrsaEventService eventService, ObjectMapper objectMapper, UrsaEventForwarder forwarder) {
        this(eventService, objectMapper, forwarder, DEFAULT_MAX_BODY_BYTES);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UrsaEventController(
            UrsaEventService eventService,
            ObjectMapper objectMapper,
            UrsaEventForwarder forwarder,
            @org.springframework.beans.factory.annotation.Value("${vance.events.max-body-bytes:1048576}")
                    long maxBodyBytes) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.forwarder = forwarder;
        this.maxBodyBytes = maxBodyBytes;
    }

    @GetMapping("/brain/{tenant}/event/{project}/{event}")
    public EventTriggerResponse triggerGet(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String event,
            HttpServletRequest request) {
        String bearer = extractBearer(request);
        UrsaEventService.UrsaEventTriggerResult result = eventService.trigger(
                tenant,
                project,
                event,
                "GET",
                bearer, /*payload*/
                null,
                isForwarded(request),
                new UrsaEventService.WebhookRequest(new byte[0], request::getHeader));
        return new EventTriggerResponse(event, result.workflowName(), result.workflowRunId(), result.output());
    }

    @PostMapping("/brain/{tenant}/event/{project}/{event}")
    public EventTriggerResponse triggerPost(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String event,
            HttpServletRequest request) {
        String bearer = extractBearer(request);
        byte[] raw = readRawBody(request);
        Object payload = parseJsonBody(request, raw);
        UrsaEventService.UrsaEventTriggerResult result = eventService.trigger(
                tenant,
                project,
                event,
                "POST",
                bearer,
                payload,
                isForwarded(request),
                new UrsaEventService.WebhookRequest(raw, request::getHeader));
        return new EventTriggerResponse(event, result.workflowName(), result.workflowRunId(), result.output());
    }

    /**
     * True when another pod routed this request here. Such a request is run
     * locally without resolving the owner again — see {@link UrsaEventForwarder}.
     *
     * <p>The marker alone is not enough: this route is JWT-free and never
     * passes {@code InternalAccessFilter}, so an external caller could set the
     * header and skip the owner resolution. The hop has to prove itself with
     * the internal token; an unproven marker is ignored (and logged), which
     * puts the request back on the ordinary routing path rather than failing
     * it.
     */
    private boolean isForwarded(HttpServletRequest request) {
        String marker = request.getHeader(UrsaEventForwarder.FORWARDED_HEADER);
        if (marker == null) {
            return false;
        }
        boolean trusted = forwarder.isTrustedHop(marker, request.getHeader(UrsaEventForwarder.INTERNAL_TOKEN_HEADER));
        if (!trusted) {
            log.warn(
                    "Ignoring {} on {} {} — no valid internal token, treating the request " + "as un-routed",
                    UrsaEventForwarder.FORWARDED_HEADER,
                    request.getMethod(),
                    request.getRequestURI());
        }
        return trusted;
    }

    /** The request body as received — a provider's signature covers exactly these bytes. */
    private byte[] readRawBody(HttpServletRequest request) {
        // The endpoint has no JWT, so what a caller sends is read only up to a cap: a declared length over it is
        // answered without reading a byte, and a body that turns out longer than it said stops being read at the
        // cap. Without this an anonymous caller chooses how much memory a request holds.
        if (maxBodyBytes > 0 && request.getContentLengthLong() > maxBodyBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Event body is larger than the limit");
        }
        try {
            byte[] raw = maxBodyBytes > 0
                    ? request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBodyBytes + 1))
                    : request.getInputStream().readAllBytes();
            if (maxBodyBytes > 0 && raw.length > maxBodyBytes) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Event body is larger than the limit");
            }
            return raw;
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Failed to read request body: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses the body as JSON when present. Empty body → {@code null}
     * (omits the {@code payload} key). Non-JSON content-type → 415.
     */
    private @Nullable Object parseJsonBody(HttpServletRequest request, byte[] raw) {
        String contentType = request.getContentType();
        if (raw.length == 0) {
            return null;
        }
        if (contentType == null || !contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Event POST body must be application/json");
        }
        try {
            return objectMapper.readValue(new String(raw, StandardCharsets.UTF_8), Object.class);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Event POST body is not valid JSON: " + ex.getOriginalMessage(), ex);
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
