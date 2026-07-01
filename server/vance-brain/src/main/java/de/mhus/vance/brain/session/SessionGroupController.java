package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.SessionGroupAssignRequest;
import de.mhus.vance.api.session.SessionGroupCreateRequest;
import de.mhus.vance.api.session.SessionGroupDto;
import de.mhus.vance.api.session.SessionGroupRenameRequest;
import de.mhus.vance.api.session.SessionGroupReorderRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.sessiongroup.SessionGroupDocument;
import de.mhus.vance.shared.sessiongroup.SessionGroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-facing CRUD for session groups — a per-user, per-project grouping of
 * sessions for UI organisation only. Unlike project groups this is not an
 * admin surface: every user manages their own groups inside a project.
 *
 * <p>Groups are scoped to {@code (tenant, project, user)}; {@code project}
 * comes from the request (query param on reads, body/query on writes) and
 * {@code user} from the authenticated request. Project-level access is
 * enforced per operation. See {@code planning/session-groups.md}.
 */
@RestController
@RequestMapping("/brain/{tenant}/session-groups")
@RequiredArgsConstructor
@Slf4j
public class SessionGroupController {

    private final SessionGroupService sessionGroupService;
    private final RequestAuthority authority;

    @GetMapping
    public List<SessionGroupDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            HttpServletRequest request) {
        String user = currentUser(request);
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return sessionGroupService.list(tenant, projectId, user).stream()
                .map(SessionGroupController::toDto)
                .toList();
    }

    @PostMapping
    public ResponseEntity<SessionGroupDto> create(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody SessionGroupCreateRequest req,
            HttpServletRequest request) {
        String user = currentUser(request);
        authority.enforce(request, new Resource.Project(tenant, req.getProjectId()), Action.CREATE);
        try {
            SessionGroupDocument saved = sessionGroupService.create(
                    tenant, req.getProjectId(), user, req.getName(), req.getTitle());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (SessionGroupService.SessionGroupAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/order")
    public List<SessionGroupDto> reorder(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @Valid @RequestBody SessionGroupReorderRequest req,
            HttpServletRequest request) {
        String user = currentUser(request);
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        sessionGroupService.reorder(tenant, projectId, user, req.getOrderedNames());
        return sessionGroupService.list(tenant, projectId, user).stream()
                .map(SessionGroupController::toDto)
                .toList();
    }

    @PutMapping("/assign")
    public ResponseEntity<Void> assign(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @Valid @RequestBody SessionGroupAssignRequest req,
            HttpServletRequest request) {
        String user = currentUser(request);
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        try {
            sessionGroupService.assign(tenant, projectId, user, req.getSessionId(), req.getGroupName());
            return ResponseEntity.noContent().build();
        } catch (SessionGroupService.SessionGroupNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/{name}")
    public SessionGroupDto rename(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam("projectId") String projectId,
            @Valid @RequestBody SessionGroupRenameRequest req,
            HttpServletRequest request) {
        String user = currentUser(request);
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        try {
            SessionGroupDocument saved = sessionGroupService.rename(
                    tenant, projectId, user, name, req.getTitle());
            return toDto(saved);
        } catch (SessionGroupService.SessionGroupNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam("projectId") String projectId,
            HttpServletRequest request) {
        String user = currentUser(request);
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.DELETE);
        try {
            sessionGroupService.delete(tenant, projectId, user, name);
            return ResponseEntity.noContent().build();
        } catch (SessionGroupService.SessionGroupNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static SessionGroupDto toDto(SessionGroupDocument doc) {
        return SessionGroupDto.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .sortIndex(doc.getSortIndex())
                .sessionIds(List.copyOf(doc.getSessionIds()))
                .build();
    }

    private static String currentUser(HttpServletRequest request) {
        Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        return s;
    }
}
