package de.mhus.vance.brain.daemon;

import de.mhus.vance.api.daemon.DaemonInfoDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only admin view of foot daemons registered on this pod.
 *
 * <ul>
 *   <li>{@code GET /brain/{tenant}/admin/daemons} — all daemons in the
 *       tenant; optionally filtered by {@code ?projectId=…}.</li>
 * </ul>
 *
 * <p>Auth: tenant-level {@link Action#ADMIN}. Project-scoped admins can
 * read the unfiltered list too — the leak is just "who's online".
 *
 * <p>Caveat: the registry is per-pod and in-memory, so this endpoint
 * shows the daemons attached to <em>this</em> pod only. Projects whose
 * home-pod is elsewhere will not appear here even if daemons exist —
 * call the admin endpoint of the right pod (or use a cluster-aware
 * aggregator once that exists).
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/daemons")
@RequiredArgsConstructor
public class DaemonAdminController {

    private final DaemonRegistry registry;
    private final RequestAuthority authority;

    @GetMapping
    public List<DaemonInfoDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        List<DaemonRegistry.DaemonRef> refs = projectId == null || projectId.isBlank()
                ? registry.listInTenant(tenant)
                : registry.listInProject(tenant, projectId);
        List<DaemonInfoDto> out = new ArrayList<>(refs.size());
        for (DaemonRegistry.DaemonRef ref : refs) {
            out.add(DaemonInfoDto.builder()
                    .projectId(ref.key().projectId())
                    .daemonName(ref.key().daemonName())
                    .status(ref.stale() ? "stale" : "online")
                    .registeredAt(ref.registeredAt().toString())
                    .lastSeenAt(ref.lastSeenAt().toString())
                    .disconnectedAt(ref.disconnectedAt() == null
                            ? null : ref.disconnectedAt().toString())
                    .tools(new ArrayList<>(ref.manifest().keySet()))
                    .build());
        }
        return out;
    }
}
