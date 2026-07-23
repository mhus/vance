package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Grant management for the Simple-Auth provider. Every operation requires
 * ADMIN on the grant's <em>scope</em>: a TENANT-scope grant needs tenant-ADMIN,
 * a PROJECT-scope grant needs ADMIN on that project — so a project admin
 * manages their own project's grants, a tenant admin the tenant's.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/permission-grants")
@RequiredArgsConstructor
public class PermissionGrantAdminController {

    private final RequestAuthority authority;
    private final PermissionGrantService grants;

    @GetMapping
    public List<PermissionGrantDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam("scopeType") GrantScopeType scopeType,
            @RequestParam("scopeId") String scopeId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, scopeResource(tenant, scopeType, scopeId), Action.ADMIN);
        return grants.forScope(tenant, scopeType, scopeId).stream()
                .map(PermissionGrantMapper::toDto)
                .toList();
    }

    @PostMapping
    public PermissionGrantDto set(
            @PathVariable("tenant") String tenant,
            @RequestBody GrantCreateRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest,
                scopeResource(tenant, request.scopeType(), request.scopeId()), Action.ADMIN);
        return PermissionGrantMapper.toDto(grants.set(
                tenant, request.scopeType(), scopeId(request.scopeType(), tenant, request.scopeId()),
                request.subjectType(), request.subjectId(), request.role(),
                authority.contextOf(httpRequest).subjectId()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable("tenant") String tenant,
            @RequestParam("scopeType") GrantScopeType scopeType,
            @RequestParam("scopeId") String scopeId,
            @RequestParam("subjectType") GrantSubjectType subjectType,
            @RequestParam("subjectId") String subjectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, scopeResource(tenant, scopeType, scopeId), Action.ADMIN);
        grants.remove(tenant, scopeType, scopeId(scopeType, tenant, scopeId), subjectType, subjectId);
    }

    /** TENANT grants are keyed on the tenant itself (self-reference). */
    private static String scopeId(GrantScopeType scopeType, String tenant, String scopeId) {
        return scopeType == GrantScopeType.TENANT ? tenant : scopeId;
    }

    private static Resource scopeResource(String tenant, GrantScopeType scopeType, String scopeId) {
        return scopeType == GrantScopeType.TENANT
                ? new Resource.Tenant(tenant)
                : new Resource.Project(tenant, scopeId);
    }
}
