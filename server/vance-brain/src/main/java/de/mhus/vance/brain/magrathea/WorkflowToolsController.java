package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.tools.ServiceCatalogDto;
import de.mhus.vance.api.tools.ServiceCatalogHighlightDto;
import de.mhus.vance.api.tools.ServiceCatalogTriggerDto;
import de.mhus.vance.api.tools.ToolServiceDto;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.oauth.OAuthTokenStore;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.servicecatalog.ServiceCatalogEntry;
import de.mhus.vance.brain.servicecatalog.ServiceCatalogLoader;
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
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ServiceCatalogLoader catalogLoader;
    private final OAuthTokenStore tokenStore;

    @GetMapping("/workflow-tools")
    public List<ToolServiceDto> list(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(value = "lang", defaultValue = "en") String lang,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        Object user = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String userId = user instanceof String s && !s.isBlank() ? s : null;
        ToolInvocationContext ctx =
                new ToolInvocationContext(tenant, project, /* sessionId */ null, /* processId */ null, userId);
        List<ToolServiceDto> groups = group(toolDispatcher.resolveAll(ctx));
        decorate(
                groups,
                catalogLoader.list(tenant, project),
                lang,
                provider ->
                        userId == null ? null : tokenStore.isConnected(tenant, OAuthTokenOwner.user(userId), provider));
        return groups;
    }

    /**
     * Put what the catalog says onto each group that has an entry. A group without one is left as it is.
     *
     * @param connected whether the caller has connected an OAuth provider; {@code null} when that is not known
     */
    static void decorate(
            List<ToolServiceDto> groups,
            List<ServiceCatalogEntry> entries,
            String lang,
            java.util.function.Function<String, @Nullable Boolean> connected) {
        for (ToolServiceDto group : groups) {
            entries.stream()
                    .filter(e -> e.describes(
                            group.getService(),
                            group.getTools().stream()
                                    .flatMap(t -> t.getLabels().stream())
                                    .collect(java.util.stream.Collectors.toSet())))
                    .findFirst()
                    .ifPresent(entry -> {
                        List<ServiceCatalogHighlightDto> highlights = new ArrayList<>();
                        for (ServiceCatalogEntry.Highlight h : entry.highlights()) {
                            group.getTools().stream()
                                    .map(ToolSpec::getName)
                                    .filter(n -> n.equalsIgnoreCase(group.getService() + "__" + h.tool())
                                            || n.toLowerCase(Locale.ROOT)
                                                    .endsWith("__" + h.tool().toLowerCase(Locale.ROOT)))
                                    .findFirst()
                                    // A highlight the pack does not offer is dropped, not shown as a dead pointer.
                                    .ifPresent(name -> highlights.add(ServiceCatalogHighlightDto.builder()
                                            .tool(name)
                                            .description(ServiceCatalogEntry.localized(h.description(), lang))
                                            .params(h.params())
                                            .build()));
                        }
                        group.setCatalog(ServiceCatalogDto.builder()
                                .name(entry.name())
                                .title(ServiceCatalogEntry.localized(entry.title(), lang))
                                .description(ServiceCatalogEntry.localized(entry.description(), lang))
                                .icon(entry.icon())
                                .loginProvider(entry.provider())
                                .connected(entry.provider() == null ? null : connected.apply(entry.provider()))
                                .manual(entry.manual())
                                .highlights(highlights)
                                .triggers(triggers(entry, lang))
                                .build());
                    });
        }
    }

    private static List<ServiceCatalogTriggerDto> triggers(ServiceCatalogEntry entry, String lang) {
        List<ServiceCatalogTriggerDto> out = new ArrayList<>();
        for (ServiceCatalogEntry.Trigger t : entry.triggers()) {
            out.add(ServiceCatalogTriggerDto.builder()
                    .name(t.name())
                    .description(ServiceCatalogEntry.localized(t.description(), lang))
                    .mode(t.mode().name().toLowerCase(Locale.ROOT))
                    .subscribes(t.subscribe() != null)
                    .inputs(t.inputs().stream()
                            .map(i -> de.mhus.vance.api.tools.ServiceCatalogInputDto.builder()
                                    .name(i.name())
                                    .description(ServiceCatalogEntry.localized(i.description(), lang))
                                    .required(i.required())
                                    .build())
                            .toList())
                    .payloadSchema(t.payloadSchema())
                    .build());
        }
        return out;
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
