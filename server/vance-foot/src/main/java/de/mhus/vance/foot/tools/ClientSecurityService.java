package de.mhus.vance.foot.tools;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gatekeeper for incoming brain-initiated tool invocations. Every
 * {@code client-tool-invoke} routes through {@link #permit} before the
 * actual tool runs.
 *
 * <p>v1 is permissive: every request is allowed (we just log it at
 * INFO so the user can audit). The seam is in place so a future
 * version can add per-tool confirmation prompts in the REPL, an
 * allowlist driven by config, or a "yolo / safe / strict" mode
 * switch.
 */
@Service
@Slf4j
public class ClientSecurityService {

    /**
     * Decides whether the brain may invoke {@code toolName} with
     * {@code params}. Returning {@code false} causes the
     * {@link ClientToolService} to reply with an error to the brain;
     * the tool is never called.
     *
     * <p>v1 always returns {@code true} — change here when adding
     * real policy.
     */
    public boolean permit(String toolName, Map<String, Object> params) {
        log.info("client-tool invoke '{}' (permit=true; v1 always allows)", toolName);
        return true;
    }

    /** Reason string used when {@link #permit} returns {@code false}. */
    public String denyReason(String toolName, Map<String, Object> params) {
        return "Client security policy denied invocation of '" + toolName + "'";
    }
}
