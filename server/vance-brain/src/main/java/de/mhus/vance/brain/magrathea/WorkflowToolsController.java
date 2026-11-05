package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.tools.ToolServiceDto;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolInvocationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tools a workflow author can put into a {@code tool_task}, grouped by service, each with
 * its parameter and result schema — the data an editor needs to offer "pick a tool, fill its
 * parameters, reach into its result" instead of free text.
 *
 * <p>Read from the same dispatcher a {@code tool_task} calls through, so what is listed is what
 * runs: no second list to keep in step. A tool that does not know its result's shape lists
 * without {@code outputSchema} and the editor falls back to free input. Tools a
 * {@code tool_task} refuses — those restricted to an engine role — are left out.
 *
 * <p>{@code Project READ}. The list is what the project can reach; whether the caller is
 * <em>allowed</em> to call a tool is decided when it is called, as for every tool.
 */
@RestController
@RequestMapping("/brain/{tenant}/project/{project}")
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowToolsController {

    private final ToolDispatcher toolDispatcher;
    private final RequestAuthority authority;

    @GetMapping("/workflow-tools")
    public List<ToolServiceDto> list(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        Object user = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        ToolInvocationContext ctx = new ToolInvocationContext(
                tenant,
                project, /* sessionId */
                null, /* processId */
                null,
                user instanceof String s && !s.isBlank() ? s : null);
        return group(toolDispatcher.resolveAll(ctx));
    }

    /**
     * Group tools by service and sort both levels by name. A tool that belongs to no service is
     * grouped under the id of its source, so it is not lost.
     */
    static List<ToolServiceDto> group(List<ToolDispatcher.Resolved> resolved) {
        Map<String, List<ToolSpec>> byService = new LinkedHashMap<>();
        for (ToolDispatcher.Resolved r : resolved) {
            if (!r.tool().requiresEngineRoles().isEmpty()) continue;
            ToolSpec spec = r.tool().toSpec(r.source().sourceId());
            String service = spec.getService() != null && !spec.getService().isBlank()
                    ? spec.getService()
                    : r.source().sourceId();
            byService.computeIfAbsent(service, k -> new ArrayList<>()).add(spec);
        }
        List<ToolServiceDto> out = new ArrayList<>(byService.size());
        byService.forEach((service, tools) -> {
            tools.sort(Comparator.comparing(ToolSpec::getName));
            out.add(ToolServiceDto.builder().service(service).tools(tools).build());
        });
        out.sort(Comparator.comparing(ToolServiceDto::getService));
        return out;
    }
}
