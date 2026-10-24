package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.ProjectRoleSetRequest;
import de.mhus.vance.api.roles.RoleAssignmentDto;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRoleSetRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Role administration: tenant roles (tenant ADMIN) and project memberships
 * (project ADMIN). Authority and the assignment rules are enforced in
 * {@link RoleAdministrationService}; this controller only translates.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/roles")
@RequiredArgsConstructor
public class RoleAdminController {

    private final RequestAuthority authority;
    private final RoleAdministrationService administration;

    /** The stored tenant roles — everyone not listed holds USER. */
    @GetMapping("/tenant")
    public List<RoleAssignmentDto> tenantRoles(@PathVariable("tenant") String tenant, HttpServletRequest httpRequest) {
        return translate(() -> administration.listTenantRoles(authority.contextOf(httpRequest), tenant).stream()
                .sorted(Comparator.comparing(RoleAssignmentDocument::getSubjectId))
                .map(RoleMapper::toDto)
                .toList());
    }

    @PutMapping("/tenant/{user}")
    public ResponseEntity<Void> setTenantRole(
            @PathVariable("tenant") String tenant,
            @PathVariable("user") String user,
            @Valid @RequestBody TenantRoleSetRequest request,
            HttpServletRequest httpRequest) {
        translate(() -> {
            administration.assignTenantRole(authority.contextOf(httpRequest), tenant, user, request.getRole());
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{project}")
    public List<RoleAssignmentDto> projectMembers(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        return translate(
                () -> administration.listProjectMembers(authority.contextOf(httpRequest), tenant, project).stream()
                        .sorted(Comparator.comparing(RoleAssignmentDocument::getSubjectType)
                                .thenComparing(RoleAssignmentDocument::getSubjectId))
                        .map(RoleMapper::toDto)
                        .toList());
    }

    @PutMapping("/projects/{project}")
    public RoleAssignmentDto setProjectRole(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @Valid @RequestBody ProjectRoleSetRequest request,
            HttpServletRequest httpRequest) {
        return translate(() -> RoleMapper.toDto(administration.assignProjectRole(
                authority.contextOf(httpRequest),
                tenant,
                project,
                request.getSubjectType(),
                request.getSubjectId(),
                request.getRole())));
    }

    @DeleteMapping("/projects/{project}")
    public ResponseEntity<Void> removeProjectRole(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam("subjectType") RoleSubjectType subjectType,
            @RequestParam("subjectId") String subjectId,
            HttpServletRequest httpRequest) {
        translate(() -> administration.removeProjectRole(
                authority.contextOf(httpRequest), tenant, project, subjectType, subjectId));
        return ResponseEntity.noContent().build();
    }

    /** A refused change is the caller's to fix — 404 for a missing name, 409 for a broken rule. */
    private static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (RoleAssignmentException e) {
            HttpStatus status =
                    e.kind() == RoleAssignmentException.Kind.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, e.getMessage(), e);
        }
    }
}
