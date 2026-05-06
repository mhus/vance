package de.mhus.vance.brain.insights;

import de.mhus.vance.api.insights.SessionClientToolsDto;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pod-internal endpoints that read pod-local in-memory state for the
 * insights views. Reachable only with a valid {@code X-Vance-Internal-Token}
 * (see {@link de.mhus.vance.brain.workspace.access.InternalAccessFilter}).
 *
 * <p>Layer 1 ({@link InsightsAdminController}) does the JWT/tenant
 * authorization and forwards the call here on the owner pod — same
 * pattern as the workspace endpoints. The pod-local state in question
 * here is the {@link ClientToolRegistry}, which is populated when the
 * client opens its WebSocket and pushes its tools.
 */
@RestController
@RequestMapping("/internal/insights")
@Slf4j
public class InsightsInternalController {

    private final ClientToolRegistry clientToolRegistry;

    public InsightsInternalController(ClientToolRegistry clientToolRegistry) {
        this.clientToolRegistry = clientToolRegistry;
    }

    @GetMapping("/sessions/{sessionId}/client-tools")
    public SessionClientToolsDto clientTools(@PathVariable("sessionId") String sessionId) {
        Optional<ClientToolRegistry.Entry> entry = clientToolRegistry.entry(sessionId);
        if (entry.isEmpty()) {
            return SessionClientToolsDto.builder()
                    .sessionId(sessionId)
                    .bound(false)
                    .tools(List.of())
                    .build();
        }
        ClientToolRegistry.Entry e = entry.get();
        return SessionClientToolsDto.builder()
                .sessionId(sessionId)
                .bound(true)
                .connectionId(e.connectionId())
                .tools(List.copyOf(e.tools().values()))
                .build();
    }
}
