package de.mhus.vance.brain.applications.calendar;

import de.mhus.vance.api.calendar.CalendarArtefactSummary;
import de.mhus.vance.api.calendar.CalendarEventCreateRequest;
import de.mhus.vance.api.calendar.CalendarEventUpdateRequest;
import de.mhus.vance.api.calendar.CalendarEventView;
import de.mhus.vance.api.calendar.CalendarPlannerView;
import de.mhus.vance.api.calendar.CalendarRebuildResponse;
import de.mhus.vance.brain.applications.CalendarsApplication;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.calendar.CalendarFolderReader;
import de.mhus.vance.brain.tools.calendar.CalendarLinkBuilder;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.CalendarCodec;
import de.mhus.vance.shared.document.kind.CalendarDocument;
import de.mhus.vance.shared.document.kind.CalendarEvent;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the interactive Calendar planner editor in the
 * Web-UI. Thin adapter over {@link CalendarsApplication} +
 * {@link CalendarFolderReader} + {@link DocumentService} — no business
 * logic here.
 *
 * <p>Events are addressed by their stable {@code id} (a UUID assigned
 * on first parse). Move-across-lanes is handled by writing the event
 * into the target lane file and removing it from the source lane file
 * in one PATCH call.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class CalendarPlannerController {

    private static final String YAML_MIME = "application/yaml";

    private final CalendarsApplication calendarsApplication;
    private final CalendarFolderReader folderReader;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    // ── Planner view ──────────────────────────────────────────────

    @GetMapping("/brain/{tenant}/calendar/planner")
    public CalendarPlannerView getPlanner(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = normaliseFolder(folder);
        CalendarFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        CalendarPlannerView view = CalendarPlannerMapper.toView(scan);

        // Inline artefact bodies so the overview tab renders without a
        // second hop. Read straight from the existing files.
        List<CalendarArtefactSummary> arts = new ArrayList<>();
        addArtefactIfPresent(tenant, projectId, normalised, "gantt",
                scan.calendarConfig().gantt().outputPath(), arts);
        addArtefactIfPresent(tenant, projectId, normalised, "conflicts",
                scan.calendarConfig().conflicts().outputPath(), arts);
        view.setArtefacts(arts);
        return view;
    }

    // ── Event create ──────────────────────────────────────────────

    @PostMapping("/brain/{tenant}/calendar/events")
    public CalendarEventView createEvent(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @Valid @RequestBody CalendarEventCreateRequest request,
            HttpServletRequest httpRequest) {

        String normalisedFolder = normaliseFolder(folder);
        String lane = request.getLane() != null && !request.getLane().isBlank()
                ? sanitiseName(request.getLane())
                : CalendarsApplication.COMMON_LANE;
        String laneFile = normalisedFolder + "/" + lane + "/work.yaml";

        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, laneFile), Action.WRITE);

        CalendarEvent newEvent = new CalendarEvent(
                UUID.randomUUID().toString(),
                request.getTitle(),
                request.getStart(),
                request.getEnd(),
                request.isAllDay(),
                request.getLocation(),
                request.getAttendees() != null ? new ArrayList<>(request.getAttendees()) : new ArrayList<>(),
                request.getRecurrence(),
                request.getColor(),
                request.getTags() != null ? new ArrayList<>(request.getTags()) : new ArrayList<>(),
                request.getNotes(),
                new LinkedHashMap<>());

        DocumentDocument laneDoc = appendEventToLane(tenant, projectId, laneFile, newEvent, httpRequest);

        log.info("CalendarPlannerController.createEvent tenant='{}' folder='{}' lane='{}' id='{}'",
                tenant, normalisedFolder, lane, newEvent.id());

        return eventToView(newEvent, lane, laneDoc.getPath());
    }

    // ── Event update ──────────────────────────────────────────────

    @PatchMapping("/brain/{tenant}/calendar/events")
    public CalendarEventView updateEvent(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("id") String eventId,
            @Valid @RequestBody CalendarEventUpdateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalisedFolder = normaliseFolder(folder);

        CalendarFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalisedFolder);
        EventLocation loc = findEvent(scan, eventId);
        CalendarEvent merged = mergePatch(loc.event(), request);

        String targetLaneRaw = request.getTargetLane();
        String targetLane = (targetLaneRaw != null && !targetLaneRaw.isBlank())
                ? sanitiseName(targetLaneRaw) : loc.lane();
        String targetLaneFile = normalisedFolder + "/" + targetLane + "/work.yaml";

        if (targetLane.equals(loc.lane())) {
            // In-place update.
            replaceEventInLane(tenant, projectId, loc.sourcePath(), loc.event().id(), merged);
            return eventToView(merged, targetLane, loc.sourcePath());
        }

        // Cross-lane move: remove from source, add to target.
        removeEventFromLane(tenant, projectId, loc.sourcePath(), loc.event().id());
        DocumentDocument targetDoc = appendEventToLane(
                tenant, projectId, targetLaneFile, merged, httpRequest);

        log.info("CalendarPlannerController.updateEvent tenant='{}' folder='{}' "
                        + "{}→{} id='{}'",
                tenant, normalisedFolder, loc.lane(), targetLane, eventId);

        return eventToView(merged, targetLane, targetDoc.getPath());
    }

    // ── Event delete ──────────────────────────────────────────────

    @DeleteMapping("/brain/{tenant}/calendar/events")
    public void deleteEvent(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("id") String eventId,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalisedFolder = normaliseFolder(folder);
        CalendarFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalisedFolder);
        EventLocation loc = findEvent(scan, eventId);
        removeEventFromLane(tenant, projectId, loc.sourcePath(), eventId);

        log.info("CalendarPlannerController.deleteEvent tenant='{}' folder='{}' id='{}'",
                tenant, normalisedFolder, eventId);
    }

    // ── Rebuild ───────────────────────────────────────────────────

    @PostMapping("/brain/{tenant}/calendar/rebuild")
    public CalendarRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);
        VanceApplication.RefreshResult result = calendarsApplication.refresh(rc);

        List<CalendarArtefactSummary> arts = new ArrayList<>();
        for (VanceApplication.ArtefactResult a : result.artefacts()) {
            String body = readArtefactBody(tenant, projectId, a.path());
            String mime = "gantt".equals(a.name()) ? "text/markdown" : YAML_MIME;
            arts.add(CalendarArtefactSummary.builder()
                    .name(a.name()).path(a.path()).markdownLink(a.markdownLink())
                    .body(body).mimeType(mime).build());
        }
        return CalendarRebuildResponse.builder()
                .folder(normalised).artefacts(arts).build();
    }

    // ── Lane file mutations ───────────────────────────────────────

    private DocumentDocument appendEventToLane(String tenant, String projectId,
                                               String laneFilePath, CalendarEvent event,
                                               HttpServletRequest httpRequest) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenant, projectId, laneFilePath);
        if (existing.isPresent()) {
            CalendarDocument existingCal = parseCalendar(existing.get());
            List<CalendarEvent> events = new ArrayList<>(existingCal.events());
            events.add(event);
            CalendarDocument updated = new CalendarDocument(
                    existingCal.kind(), events, existingCal.extra());
            String body = CalendarCodec.serialize(updated, YAML_MIME);
            return documentService.update(
                    existing.get().getId(),
                    titleFromPath(laneFilePath), List.of("calendar"),
                    body, null, null, null, null, YAML_MIME);
        }
        // Fresh file.
        List<CalendarEvent> events = List.of(event);
        CalendarDocument doc = new CalendarDocument("calendar", events, new LinkedHashMap<>());
        String body = CalendarCodec.serialize(doc, YAML_MIME);
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    tenant, projectId, laneFilePath,
                    titleFromPath(laneFilePath), List.of("calendar"),
                    YAML_MIME, in, currentUser(httpRequest));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write calendar '" + laneFilePath + "': " + e.getMessage());
        }
    }

    private void replaceEventInLane(String tenant, String projectId,
                                    String laneFilePath, String eventId,
                                    CalendarEvent newEvent) {
        DocumentDocument doc = documentService.findByPath(tenant, projectId, laneFilePath)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Lane file not found: " + laneFilePath));
        CalendarDocument cal = parseCalendar(doc);
        List<CalendarEvent> events = new ArrayList<>(cal.events().size());
        boolean replaced = false;
        for (CalendarEvent ev : cal.events()) {
            if (eventId.equals(ev.id())) {
                events.add(newEvent);
                replaced = true;
            } else {
                events.add(ev);
            }
        }
        if (!replaced) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Event '" + eventId + "' not found in " + laneFilePath);
        }
        CalendarDocument updated = new CalendarDocument(cal.kind(), events, cal.extra());
        String body = CalendarCodec.serialize(updated, YAML_MIME);
        documentService.update(doc.getId(),
                doc.getTitle(), List.of("calendar"),
                body, null, null, null, null, YAML_MIME);
    }

    private void removeEventFromLane(String tenant, String projectId,
                                     String laneFilePath, String eventId) {
        Optional<DocumentDocument> docOpt = documentService.findByPath(
                tenant, projectId, laneFilePath);
        if (docOpt.isEmpty()) return;
        DocumentDocument doc = docOpt.get();
        CalendarDocument cal = parseCalendar(doc);
        List<CalendarEvent> events = new ArrayList<>(cal.events().size());
        for (CalendarEvent ev : cal.events()) {
            if (!eventId.equals(ev.id())) events.add(ev);
        }
        if (events.size() == cal.events().size()) return;
        CalendarDocument updated = new CalendarDocument(cal.kind(), events, cal.extra());
        String body = CalendarCodec.serialize(updated, YAML_MIME);
        documentService.update(doc.getId(),
                doc.getTitle(), List.of("calendar"),
                body, null, null, null, null, YAML_MIME);
    }

    // ── Lookup helpers ────────────────────────────────────────────

    private record EventLocation(String lane, String sourcePath, CalendarEvent event) { }

    private static EventLocation findEvent(CalendarFolderReader.Scan scan, String eventId) {
        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            for (CalendarEvent ev : cf.calendar().events()) {
                if (eventId.equals(ev.id())) {
                    return new EventLocation(cf.lane(), cf.doc().getPath(), ev);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Event '" + eventId + "' not found.");
    }

    private CalendarDocument parseCalendar(DocumentDocument doc) {
        String body = readBody(doc);
        try {
            return CalendarCodec.parse(body, doc.getMimeType());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not parse calendar '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    private String readBody(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read calendar body: " + e.getMessage());
        }
    }

    private void addArtefactIfPresent(String tenant, String projectId, String folder,
                                      String name, String relativePath,
                                      List<CalendarArtefactSummary> out) {
        String path = CalendarFolderReader.resolveOutputPath(folder, relativePath);
        if (path == null) return;
        Optional<DocumentDocument> docOpt = documentService.findByPath(tenant, projectId, path);
        if (docOpt.isEmpty()) return;
        DocumentDocument doc = docOpt.get();
        String body = readBody(doc);
        out.add(CalendarArtefactSummary.builder()
                .name(name)
                .path(doc.getPath())
                .mimeType(doc.getMimeType())
                .body(body)
                .build());
    }

    private @Nullable String readArtefactBody(String tenant, String projectId, String path) {
        return documentService.findByPath(tenant, projectId, path)
                .map(this::readBody).orElse(null);
    }

    // ── Patch merge ───────────────────────────────────────────────

    private static CalendarEvent mergePatch(CalendarEvent existing, CalendarEventUpdateRequest p) {
        String title = p.getTitle() != null ? p.getTitle() : existing.title();
        String start = p.getStart() != null ? p.getStart() : existing.start();
        String end = p.getEnd() != null
                ? (p.getEnd().isBlank() ? null : p.getEnd())
                : existing.end();
        boolean allDay = p.getAllDay() != null ? p.getAllDay() : existing.allDay();
        String location = p.getLocation() != null
                ? (p.getLocation().isBlank() ? null : p.getLocation())
                : existing.location();
        List<String> attendees = p.getAttendees() != null
                ? new ArrayList<>(p.getAttendees())
                : new ArrayList<>(existing.attendees());
        String recurrence = p.getRecurrence() != null
                ? (p.getRecurrence().isBlank() ? null : p.getRecurrence())
                : existing.recurrence();
        String color = p.getColor() != null
                ? (p.getColor().isBlank() ? null : p.getColor())
                : existing.color();
        List<String> tags = p.getTags() != null
                ? new ArrayList<>(p.getTags())
                : new ArrayList<>(existing.tags());
        String notes = p.getNotes() != null
                ? (p.getNotes().isBlank() ? null : p.getNotes())
                : existing.notes();
        return new CalendarEvent(
                existing.id(), title, start, end, allDay,
                location, attendees, recurrence, color, tags, notes,
                existing.extra());
    }

    private static CalendarEventView eventToView(CalendarEvent ev, String lane, String sourcePath) {
        CalendarLinkBuilder.Links links = CalendarLinkBuilder.buildLinks(ev);
        return CalendarEventView.builder()
                .id(ev.id())
                .lane(lane)
                .sourcePath(sourcePath)
                .title(ev.title())
                .start(ev.start())
                .end(ev.end())
                .allDay(ev.allDay())
                .location(ev.location())
                .attendees(new ArrayList<>(ev.attendees()))
                .recurrence(ev.recurrence())
                .color(ev.color())
                .tags(new ArrayList<>(ev.tags()))
                .notes(ev.notes())
                .googleUrl(links.google())
                .outlookUrl(links.outlook())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must be provided");
        }
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must not be empty");
        }
        return f;
    }

    private static String sanitiseName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "lane" : sb.toString();
    }

    private static String titleFromPath(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) return path;
        // Lane source files live at <folder>/<lane>/work.yaml — the parent
        // folder's name is the lane title.
        String trimmed = path.substring(0, slash);
        int innerSlash = trimmed.lastIndexOf('/');
        String lane = innerSlash < 0 ? trimmed : trimmed.substring(innerSlash + 1);
        return "Calendar — " + lane;
    }

    private static @Nullable String currentUser(HttpServletRequest httpRequest) {
        Object v = httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        return v instanceof String s ? s : null;
    }
}
