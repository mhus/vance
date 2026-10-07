package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionDto;
import de.mhus.vance.api.admin.AdminActionRunResultDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for the operator actions — the Settings page's
 * "Aktionen" tab. Listing returns whatever the {@link AdminActionRegistry}
 * collected (addons included); running hands the matched action its
 * {@link AdminActionContext} and passes its generic result through
 * untouched. The surface is deliberately action-agnostic: a new
 * {@link AdminAction} bean appears without a line of controller or
 * client code.
 *
 * <p><b>Enforcement lives here, not in actions:</b> every route
 * enforces ADMIN on the tenant, and a project run requires the project
 * resource too — an action can never lower the bar of the surface that
 * lists it. A {@code TENANT_ONLY} action called with a project is a
 * 400 before the action ever runs: the scope contract is visible in
 * the listing, so the caller can see the same rule the server applies.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/actions")
@RequiredArgsConstructor
@Slf4j
public class AdminActionController {

    private final AdminActionRegistry registry;
    private final RequestAuthority authority;
    private final SecurityContextFactory securityContextFactory;

    @GetMapping
    public List<AdminActionDto> list(@PathVariable("tenant") String tenant, HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        return registry.list();
    }

    @PostMapping("/{id}")
    public AdminActionRunResultDto run(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        // A project-scoped run also needs ADMIN on that project — the
        // tenant check alone would let an operator run against projects
        // they cannot administer.
        if (projectId != null && !projectId.isBlank()) {
            authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.ADMIN);
        }
        AdminAction action = registry.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No admin action '" + id + "'"));
        if (projectId != null && !projectId.isBlank() && action.scope() == AdminActionScope.TENANT_ONLY) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Admin action '" + id + "' runs in the tenant scope only");
        }
        String userId = securityContextFactory.fromRequest(httpRequest).subjectId();
        log.info("AdminAction '{}': run by '{}' on tenant '{}' projectId='{}'", id, userId, tenant, projectId);
        long start = System.currentTimeMillis();
        AdminActionRunResultDto result = action.run(new AdminActionContext(tenant, blankToNull(projectId), userId));
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
