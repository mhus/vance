package de.mhus.vance.foot.session;

import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import de.mhus.vance.foot.ui.Verbosity;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Drives the {@code --resume} startup path: list sessions from the
 * brain, narrow them by the user's filters, either pick the newest
 * (when {@code --last} is set) or open a Lanterna picker, then push
 * the chosen session back into the bootstrap config and trigger
 * {@link AutoBootstrapService#triggerNow()}.
 */
@Service
public class SessionResumeFlow {

    public enum Outcome {
        BOOTSTRAPPED,
        CANCELLED,
        NO_MATCH,
        LIST_FAILED
    }

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final InterfaceService interfaceService;
    private final FootConfig config;
    private final AutoBootstrapService bootstrap;
    private final BrainRestClientService rest;

    public SessionResumeFlow(ConnectionService connection,
                             ChatTerminal terminal,
                             InterfaceService interfaceService,
                             FootConfig config,
                             AutoBootstrapService bootstrap,
                             BrainRestClientService rest) {
        this.connection = connection;
        this.terminal = terminal;
        this.interfaceService = interfaceService;
        this.config = config;
        this.bootstrap = bootstrap;
        this.rest = rest;
    }

    /**
     * Runs the picker/auto-pick. Returns one of {@link Outcome}.
     *
     * <p>Filter rules:
     * <ul>
     *   <li>Profile filter = {@code config.getClient().getProfile()}
     *       (so {@code --profile=web} narrows to web sessions, etc.).
     *       Defaults to {@code "foot"}, which is what the user almost
     *       always wants when resuming.</li>
     *   <li>Project filter: {@code projectId} as passed, or, when
     *       {@code eddie} is true, {@code _user_<username>} derived
     *       from {@code config.getAuth().getUsername()}. {@code eddie}
     *       and an explicit {@code projectId} are mutually exclusive —
     *       the caller (CLI) is expected to reject that combination
     *       before getting here, but we double-check below.</li>
     *   <li>{@code bound == false} mandatory — bound sessions can't be
     *       safely resumed.</li>
     * </ul>
     */
    public Outcome run(boolean eddie, @Nullable String projectId, boolean last) {
        String wantedProfile = nonEmpty(config.getClient().getProfile(), "foot");

        String effectiveProjectId;
        if (eddie) {
            if (projectId != null && !projectId.isBlank()) {
                terminal.error("--eddie and --project are mutually exclusive.");
                return Outcome.NO_MATCH;
            }
            String user = nonEmpty(config.getAuth().getUsername(), "");
            if (user.isEmpty()) {
                terminal.error("Cannot apply --eddie filter: no vance.auth.username configured.");
                return Outcome.LIST_FAILED;
            }
            effectiveProjectId = "_user_" + user;
        } else {
            effectiveProjectId = (projectId == null || projectId.isBlank()) ? null : projectId;
        }

        SessionListResponse listResp;
        try {
            listResp = connection.request(
                    MessageType.SESSION_LIST,
                    SessionListRequest.builder()
                            .projectId(effectiveProjectId)
                            .build(),
                    SessionListResponse.class,
                    Duration.ofSeconds(10));
        } catch (Exception e) {
            terminal.error("Could not load session list: " + e.getMessage());
            return Outcome.LIST_FAILED;
        }

        List<SessionSummary> all = listResp.getSessions() == null
                ? List.of() : listResp.getSessions();
        List<SessionSummary> candidates = all.stream()
                .filter(s -> !s.isBound())                     // mandatory — bound sessions can't be resumed
                .filter(s -> matchesProfile(s, wantedProfile))
                .sorted(Comparator.comparingLong(SessionSummary::getLastActivityAt).reversed())
                .toList();

        if (candidates.isEmpty()) {
            terminal.error("No matching sessions to resume "
                    + "(profile=" + wantedProfile
                    + ", projectId=" + (effectiveProjectId == null ? "*" : effectiveProjectId)
                    + ", bound=false).");
            return Outcome.NO_MATCH;
        }

        SessionSummary picked;
        if (last) {
            picked = candidates.get(0);
            terminal.info("Resuming most recent: " + describe(picked));
        } else {
            picked = pickInteractively(candidates);
            if (picked == null) {
                terminal.info("Resume cancelled.");
                return Outcome.CANCELLED;
            }
            terminal.info("Resuming: " + describe(picked));
        }

        // Push the picked session into the bootstrap config and fire
        // the bootstrap path manually. AutoBootstrapService.triggerNow()
        // bypasses the SKIP_PROPERTY guard the resume flow set earlier.
        FootConfig.Bootstrap b = config.getBootstrap();
        if (b == null) {
            b = new FootConfig.Bootstrap();
            config.setBootstrap(b);
        }
        b.setProjectId(picked.getProjectId());
        b.setSessionId(picked.getSessionId());
        b.setInitialMessage(null);   // no kick-off message — resume only

        // Pull and replay recent messages BEFORE triggering bootstrap,
        // so the scrollback shows context-then-confirmation rather than
        // the brain spelling out the bind ahead of any history.
        int replayCount = Math.max(0, b.getReplayMessages());
        if (replayCount > 0) {
            replayHistory(picked.getSessionId(), replayCount);
        }

        bootstrap.triggerNow();
        return Outcome.BOOTSTRAPPED;
    }

    /**
     * Fetches the most recent {@code limit} chat messages for
     * {@code sessionId} via REST and emits them as styled static lines
     * so the user sees the conversation context before they start
     * typing into the resumed session.
     */
    private void replayHistory(String sessionId, int limit) {
        List<ChatMessageDto> messages;
        try {
            messages = rest.chatHistory(sessionId, limit);
        } catch (Exception e) {
            terminal.warn("Could not load recent chat history: " + e.getMessage());
            return;
        }
        if (messages.isEmpty()) {
            return;
        }
        terminal.println(Verbosity.INFO,
                "── recent chat (last %d) ──", messages.size());
        for (ChatMessageDto m : messages) {
            ChatRole role = m.getRole();
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) continue;
            if (role == ChatRole.USER) {
                // Same inverse-video shape ChatRepl uses for live submits.
                for (String segment : content.split("\n", -1)) {
                    terminal.println(Verbosity.INFO,
                            "\u001b[7m ❯ %s \u001b[0m", segment);
                }
            } else if (role == ChatRole.ASSISTANT) {
                terminal.chat(content);
            } else {
                terminal.println(Verbosity.INFO,
                        "\u001b[2m[%s] %s\u001b[0m",
                        role == null ? "system" : role.name().toLowerCase(),
                        content);
            }
        }
        terminal.println(Verbosity.INFO, "── end of recent chat ──");
    }

    private @Nullable SessionSummary pickInteractively(List<SessionSummary> candidates) {
        SessionSummary[] picked = new SessionSummary[] { null };
        try {
            interfaceService.runFullscreen(session ->
                    picked[0] = SessionPickerView.show(session.gui(), "Resume Session", candidates));
        } catch (Exception e) {
            terminal.error("Could not open session picker: " + e.getMessage());
            return null;
        }
        return picked[0];
    }

    private static boolean matchesProfile(SessionSummary s, String wantedProfile) {
        String profile = s.getProfile();
        if (profile == null || profile.isEmpty()) {
            // Older / unspecified sessions: only allow when we're
            // looking for foot — that's the historical default.
            return "foot".equals(wantedProfile);
        }
        return wantedProfile.equals(profile);
    }

    private static String nonEmpty(@Nullable String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String describe(SessionSummary s) {
        StringBuilder b = new StringBuilder();
        if (s.getIcon() != null && !s.getIcon().isBlank()) {
            b.append(s.getIcon()).append(' ');
        }
        if (s.getTitle() != null && !s.getTitle().isBlank()) {
            b.append(s.getTitle());
        } else if (s.getDisplayName() != null && !s.getDisplayName().isBlank()) {
            b.append(s.getDisplayName());
        } else {
            b.append("(unnamed)");
        }
        b.append(" [").append(s.getSessionId());
        if (s.getProjectId() != null) {
            b.append(" · project=").append(s.getProjectId());
        }
        b.append("]");
        return b.toString();
    }
}
