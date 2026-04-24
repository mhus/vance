package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.ProcessSpec;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.cli.VanceCliConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates or resumes a session and — in the same round-trip — spawns the
 * configured think-processes.
 *
 * <p>Arguments override (or fully replace) the {@code bootstrap} section of
 * the CLI config.
 *
 * <ul>
 *   <li>{@code /session-bootstrap} — pure config mode.
 *   <li>{@code /session-bootstrap <projectId>} — new session on that project;
 *       processes come from config (empty if none).
 *   <li>{@code /session-bootstrap <projectId> <engine:name>...} — new session
 *       + a list of processes. Fully overrides config processes; no
 *       {@code initialMessage}.
 * </ul>
 */
public class SessionBootstrapCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "session-bootstrap";
    }

    @Override
    public String description() {
        return "Bootstrap a session (create or resume) with processes — from config or args.";
    }

    @Override
    public String usage() {
        return "/session-bootstrap [projectId [engine:name ...]]";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        SessionBootstrapRequest request = buildRequest(ctx.config(), args);
        if (request == null) {
            ctx.error("No bootstrap config and no args — specify a projectId or set vance.bootstrap.* in config.");
            return;
        }
        String id = "session-bootstrap_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id, MessageType.SESSION_BOOTSTRAP, request);

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.SESSION_BOOTSTRAP
                + " projectId=" + request.getProjectId()
                + " sessionId=" + request.getSessionId()
                + " processes=" + request.getProcesses().size()
                + (request.getInitialMessage() != null ? " +initialMessage" : ""));
    }

    /**
     * Merges positional args into the config's bootstrap section. Args win
     * over config; if neither gives us a projectId or sessionId, returns
     * {@code null} (caller shows a helpful error).
     */
    static SessionBootstrapRequest buildRequest(VanceCliConfig cfg, String[] args) {
        VanceCliConfig.Bootstrap cfgBootstrap = cfg.getBootstrap();

        String projectId = args.length >= 1
                ? args[0]
                : (cfgBootstrap != null ? cfgBootstrap.getProjectId() : null);
        String sessionId = cfgBootstrap != null ? cfgBootstrap.getSessionId() : null;
        String initialMessage = cfgBootstrap != null ? cfgBootstrap.getInitialMessage() : null;

        List<ProcessSpec> processes;
        if (args.length >= 2) {
            processes = new ArrayList<>(args.length - 1);
            for (int i = 1; i < args.length; i++) {
                ProcessSpec spec = parseEngineColonName(args[i]);
                if (spec == null) {
                    return null;
                }
                processes.add(spec);
            }
            initialMessage = null; // args mode overrides config message too
        } else if (cfgBootstrap != null && cfgBootstrap.getProcesses() != null) {
            processes = new ArrayList<>();
            for (VanceCliConfig.Process p : cfgBootstrap.getProcesses()) {
                if (p.getEngine() == null || p.getEngine().isBlank()
                        || p.getName() == null || p.getName().isBlank()) {
                    continue;
                }
                processes.add(ProcessSpec.builder()
                        .engine(p.getEngine())
                        .name(p.getName())
                        .title(p.getTitle())
                        .goal(p.getGoal())
                        .build());
            }
        } else {
            processes = new ArrayList<>();
        }

        // Default case — nothing set: let the server pick a sensible
        // first project and spin up a zaphod chat so the user has something
        // to talk to out of the box.
        boolean empty = (projectId == null || projectId.isBlank())
                && (sessionId == null || sessionId.isBlank());
        if (empty && processes.isEmpty()) {
            processes.add(ProcessSpec.builder()
                    .engine("zaphod")
                    .name("chat")
                    .build());
        }
        return SessionBootstrapRequest.builder()
                .projectId(projectId)
                .sessionId(sessionId)
                .processes(processes)
                .initialMessage(initialMessage)
                .build();
    }

    private static ProcessSpec parseEngineColonName(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return null;
        }
        return ProcessSpec.builder()
                .engine(token.substring(0, colon))
                .name(token.substring(colon + 1))
                .build();
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        SessionBootstrapResponse resp = ctx.parseData(reply.getData(), SessionBootstrapResponse.class);
        if (resp == null) {
            ctx.error(name() + ": empty reply");
            return;
        }
        ctx.connection().bindSession(resp.getSessionId(), resp.getProjectId());
        // First created (or otherwise skipped-but-present) process becomes the
        // free-text target — users typing without a leading slash chat with it.
        BootstrappedProcess active = firstOf(resp.getProcessesCreated());
        if (active == null) {
            active = firstOf(resp.getProcessesSkipped());
        }
        if (active != null) {
            ctx.setActiveProcessName(active.getName());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("bootstrap: session ")
                .append(resp.isSessionCreated() ? "created" : "resumed")
                .append(" (").append(resp.getSessionId())
                .append(", project=").append(resp.getProjectId()).append(")");
        if (!resp.getProcessesCreated().isEmpty()) {
            sb.append(", created: ").append(formatBrief(resp.getProcessesCreated()));
        }
        if (!resp.getProcessesSkipped().isEmpty()) {
            sb.append(", skipped: ").append(formatBrief(resp.getProcessesSkipped()));
        }
        if (resp.getSteeredProcessName() != null) {
            sb.append(", steered: ").append(resp.getSteeredProcessName());
        }
        ctx.received(sb.toString());
    }

    private static BootstrappedProcess firstOf(List<BootstrappedProcess> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static String formatBrief(List<BootstrappedProcess> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            BootstrappedProcess p = list.get(i);
            sb.append(p.getEngine()).append(':').append(p.getName());
        }
        return sb.toString();
    }
}
