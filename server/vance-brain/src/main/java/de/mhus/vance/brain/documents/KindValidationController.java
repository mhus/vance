package de.mhus.vance.brain.documents;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.kind.validate.KindValidationService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for kind validation — the Web-UI findings panel. Resource-
 * oriented (a stored document, by path); the agent-oriented sibling is the
 * {@code kind_validate} LLM tool. Read-only, advisory: it reports what does
 * not fit the kind, never blocks a write.
 */
@RestController
@RequiredArgsConstructor
public class KindValidationController {

    private final KindValidationService validationService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/documents/validate")
    public Map<String, Object> validate(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        return validationService.validateByPath(tenant, projectId, path).toMap();
    }
}
