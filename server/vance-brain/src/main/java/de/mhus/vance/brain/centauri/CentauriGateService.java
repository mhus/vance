package de.mhus.vance.brain.centauri;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.feed.FeedScope;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pre-dispatch gate for a feed source: is it switched on, and is it in a
 * cooldown from an earlier failure.
 *
 * <p>Both questions are asked before a stream is fetched, because a source
 * that is off or known-down should not slow down the other streams of the
 * same page — a mixed feed waits for its slowest source.
 *
 * <p>Deliberately without Zarniwoop's pod-local manual override: that exists
 * to be flipped from an operator UI, and Centauri has none yet. Adding the
 * mechanism before its surface would be a switch nobody can reach.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentauriGateService {

    private final SettingService settings;
    private final ToolHealthService healthService;

    /** Why a source is not being asked — surfaced as a page note. */
    public enum Blocked {
        DISABLED,
        COOLING_DOWN
    }

    /** Empty when the source may be used. */
    public Optional<Blocked> check(FeedScope scope, String sourceId) {
        if (!isEnabled(scope, sourceId)) {
            return Optional.of(Blocked.DISABLED);
        }
        if (isCoolingDown(scope, sourceId)) {
            return Optional.of(Blocked.COOLING_DOWN);
        }
        return Optional.empty();
    }

    /** Default is on — only an explicit {@code false} turns a source off. */
    public boolean isEnabled(FeedScope scope, String sourceId) {
        return settings.getBooleanValueCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                CentauriSettings.endpointEnabledKey(sourceId), true);
    }

    public boolean isCoolingDown(FeedScope scope, String sourceId) {
        Optional<ToolHealthCooldown> cooldown = healthService.lookupActiveCooldown(
                scope.tenantId(),
                ToolHealthScope.PROJECT,
                scope.projectId(),
                CentauriSettings.cooldownSubject(sourceId),
                /* errorSignature */ null,
                /* userId */ scope.userId(),
                Instant.now());
        return cooldown.isPresent();
    }
}
