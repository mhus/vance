package de.mhus.vance.foot.ide;

import de.mhus.vance.api.ws.IntellijMcpRegisterRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Pushes the JetBrains MCP endpoint configured via
 * {@code vance.foot.ide.intellij-mcp.url} (set by
 * {@code --intellij-mcp[=<url>]} or {@code --intellij-mcp-default}) to
 * the brain right after welcome. The brain registers it as a
 * {@code mcp_server} ServerToolDocument so the LLM gets access to all
 * IntelliJ tools (run/debug/refactor/build/database/…).
 *
 * <p>No-op when the URL is not configured. Idempotent — re-running with
 * the same URL is a server-side no-op; a different URL updates the
 * existing document.
 */
@Service
@Slf4j
public class IntellijMcpRegistrationService {

    private static final Duration REGISTER_TIMEOUT = Duration.ofSeconds(10);

    private final FootConfig config;
    private final ConnectionService connection;
    private final ChatTerminal terminal;

    /**
     * {@code @Lazy} on {@link ConnectionService} mirrors the same trick
     * {@code WelcomeHandler} uses to break the
     * {@code ConnectionService → MessageDispatcher → handlers}
     * dependency cycle.
     */
    public IntellijMcpRegistrationService(FootConfig config,
                                          @Lazy ConnectionService connection,
                                          ChatTerminal terminal) {
        this.config = config;
        this.connection = connection;
        this.terminal = terminal;
    }

    /**
     * Sends the register frame if the bridge is configured. Best-effort —
     * any failure logs a warning and the foot keeps running; the user
     * just won't see the IntelliJ tools in this session.
     */
    public void registerIfConfigured() {
        String url = config.getIde().getIntellijMcp().getUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        if (!connection.isOpen()) {
            log.debug("intellij-mcp-register skipped — not connected");
            return;
        }
        try {
            connection.request(
                    MessageType.INTELLIJ_MCP_REGISTER,
                    IntellijMcpRegisterRequest.builder().url(url).build(),
                    Object.class,
                    REGISTER_TIMEOUT);
            log.info("intellij-mcp-register: brain accepted url='{}'", url);
            terminal.info("IntelliJ MCP: registered " + url + " — IDE tools now available to the LLM.");
        } catch (Exception e) {
            log.warn("intellij-mcp-register failed: {}", e.toString());
            terminal.warn("IntelliJ MCP register failed: " + e.getMessage());
        }
    }
}
