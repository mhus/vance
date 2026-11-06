package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.api.tools.CatalogTriggerCreateRequest;
import de.mhus.vance.api.tools.CatalogTriggerCreateResponse;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editor's button "add trigger": makes an event from a trigger of the service catalog. {@code Project WRITE}
 * — it writes an event document.
 */
@RestController
@RequestMapping("/brain/{tenant}/project/{project}")
@RequiredArgsConstructor
public class CatalogTriggerController {

    private final CatalogTriggerService service;
    private final RequestAuthority authority;

    @PostMapping("/workflow-triggers")
    public CatalogTriggerCreateResponse create(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestBody CatalogTriggerCreateRequest body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        return service.createEvent(
                tenant,
                project,
                authority.contextOf(request),
                body.getService(),
                body.getTrigger(),
                body.getWorkflow(),
                body.getName());
    }
}
