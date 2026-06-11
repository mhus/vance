package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.SearchScope;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Two-layer enable/disable gate for {@code Zarniwoop} provider
 * instances:
 *
 * <ol>
 *   <li><b>Settings default</b> — {@code research.endpoint.<id>.enabled}
 *       (default {@code true}). Persistent, project-cascade. Operators
 *       use this to ship an instance "off by default" (think: an
 *       endpoint that's configured but not in active rotation).</li>
 *   <li><b>Manual override</b> — pod-local, in-memory, scoped per
 *       {@code (tenant, project, instance)}. Set from the Insights
 *       UI to temporarily flip an instance off (or on, when the
 *       settings default keeps it off). Evicted on project suspend
 *       and on pod restart.</li>
 * </ol>
 *
 * <p>Resolution: when an override exists it wins outright; otherwise
 * the settings default decides. {@link #isEnabled} is what the
 * dispatcher actually consults — the structured separation is
 * surfaced to the Insights view so the operator sees both layers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZarniwoopGateService {

    private final SettingService settings;

    private final Map<Key, ManualState> overrides = new ConcurrentHashMap<>();

    /** Pod-local override values. */
    public enum ManualState {
        ENABLED,
        DISABLED
    }

    /**
     * Composite gate decision the dispatcher and the insights view
     * both consume.
     */
    public record GateDecision(
            boolean defaultEnabled,
            Optional<ManualState> override,
            boolean effectivelyEnabled) {
    }

    /**
     * Final effective state. Dispatcher calls this. Returns
     * {@code true} unless the operator (settings or UI) told it
     * otherwise.
     */
    public boolean isEnabled(SearchScope scope, String instanceId) {
        return resolve(scope, instanceId).effectivelyEnabled();
    }

    /** Full decision — used by {@code ZarniwoopInsightsService}. */
    public GateDecision resolve(SearchScope scope, String instanceId) {
        boolean defaultEnabled = settingsDefault(scope, instanceId);
        Optional<ManualState> override = currentOverride(scope, instanceId);
        boolean effective = switch (override.orElse(null)) {
            case ENABLED -> true;
            case DISABLED -> false;
            case null -> defaultEnabled;
        };
        return new GateDecision(defaultEnabled, override, effective);
    }

    /** Set a pod-local manual override on the instance. */
    public void setOverride(SearchScope scope, String instanceId, ManualState state) {
        if (!isValid(scope, instanceId) || state == null) {
            throw new ZarniwoopException("invalid override target");
        }
        overrides.put(keyOf(scope, instanceId), state);
        log.info("Zarniwoop: manual override {} for '{}' on '{}/{}'",
                state, instanceId, scope.tenantId(), scope.projectId());
    }

    /** Drop the manual override; falls back to the settings default. */
    public void clearOverride(SearchScope scope, String instanceId) {
        if (!isValid(scope, instanceId)) return;
        if (overrides.remove(keyOf(scope, instanceId)) != null) {
            log.info("Zarniwoop: cleared override for '{}' on '{}/{}'",
                    instanceId, scope.tenantId(), scope.projectId());
        }
    }

    public Optional<ManualState> currentOverride(SearchScope scope, String instanceId) {
        if (!isValid(scope, instanceId)) return Optional.empty();
        return Optional.ofNullable(overrides.get(keyOf(scope, instanceId)));
    }

    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        overrides.keySet().removeIf(k ->
                k.tenantId.equals(event.tenantId())
                        && k.projectId.equals(event.projectName()));
        log.debug("Zarniwoop: cleared overrides for '{}/{}' on project stop",
                event.tenantId(), event.projectName());
    }

    private boolean settingsDefault(SearchScope scope, String instanceId) {
        String raw = settings.getStringValueCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                ZarniwoopSettings.endpointEnabledKey(instanceId));
        // Default is true — only an explicit "false" turns it off.
        if (raw == null) return true;
        return !"false".equalsIgnoreCase(raw.trim());
    }

    private static boolean isValid(SearchScope scope, String instanceId) {
        return scope != null
                && !StringUtils.isBlank(scope.projectId())
                && !StringUtils.isBlank(instanceId);
    }

    private static Key keyOf(SearchScope scope, String instanceId) {
        return new Key(scope.tenantId(), scope.projectId(), instanceId);
    }

    private record Key(String tenantId, String projectId, String instanceId) { }
}
