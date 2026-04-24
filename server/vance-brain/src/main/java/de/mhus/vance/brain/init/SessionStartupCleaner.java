package de.mhus.vance.brain.init;

import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.session.SessionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Releases any session that's still bound to this pod's IP at startup.
 *
 * <p>Rationale: the WebSocket close hook
 * ({@code VanceWebSocketHandler.afterConnectionClosed}) only fires on a
 * graceful disconnect. A hard pod kill (SIGKILL, crash, OOM) leaves the
 * {@code boundConnectionId} / {@code boundPodIp} fields populated with
 * references that no longer exist — which blocks Auto-Resume on reconnect
 * (the session looks "held"). On startup the pod therefore releases every
 * binding that points at its own IP: those bindings are definitively stale
 * because this pod just came up fresh.
 *
 * <p>Other pods' bindings are left alone — they might be live.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionStartupCleaner {

    private final SessionService sessionService;
    private final LocationService locationService;

    @PostConstruct
    void releaseStaleBindings() {
        String podIp = locationService.getPodIp();
        sessionService.unbindAllByPod(podIp);
    }
}
