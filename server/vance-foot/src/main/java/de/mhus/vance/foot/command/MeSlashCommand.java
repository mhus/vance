package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.WelcomeData;
import de.mhus.vance.foot.auth.AccessData;
import de.mhus.vance.foot.auth.AccessStore;
import de.mhus.vance.foot.auth.ProjectBinding;
import de.mhus.vance.foot.auth.ProjectBindingStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /me} — show the current identity in two parts:
 *
 * <ol>
 *   <li><b>Live</b> — who the Brain has actually authenticated us as on the
 *       open connection (from the WELCOME frame), plus the bound
 *       project/session and the live token expiry.</li>
 *   <li><b>Stored login config</b> — the persisted binding
 *       ({@code project.eddie.yaml}) and credential cache ({@code access.yaml}) in
 *       the active {@code .vancetope} directory.</li>
 * </ol>
 *
 * <p>The two can differ — e.g. the stored config names user X but the live
 * connection re-minted a dev token as user Y, or the config was edited after
 * connecting. {@code /me} shows both so the discrepancy is visible; it never
 * prints a token, only its expiry.
 */
@Component
public class MeSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final FootConfig config;
    private final SessionService sessions;
    private final VancePaths paths;
    private final ProjectBindingStore bindingStore;
    private final AccessStore accessStore;
    private final ChatTerminal terminal;

    public MeSlashCommand(ConnectionService connection,
                          FootConfig config,
                          SessionService sessions,
                          VancePaths paths,
                          ProjectBindingStore bindingStore,
                          AccessStore accessStore,
                          ChatTerminal terminal) {
        this.connection = connection;
        this.config = config;
        this.sessions = sessions;
        this.paths = paths;
        this.bindingStore = bindingStore;
        this.accessStore = accessStore;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "me";
    }

    @Override
    public String description() {
        return "Show who you're logged in as (live) and the stored login config — the two can differ.";
    }

    @Override
    public void execute(List<String> args) {
        renderLive();
        terminal.info("");
        renderStored();
    }

    private void renderLive() {
        WelcomeData welcome = connection.lastWelcome();
        if (connection.state() != ConnectionService.State.OPEN || welcome == null) {
            terminal.info("Logged in (live): not connected — run /connect (or /login).");
            return;
        }
        terminal.info("Logged in (live):");
        terminal.info(row("tenant", welcome.getTenantId()));
        terminal.info(row("user", user(welcome)));
        SessionService.BoundSession bound = sessions.current();
        if (bound != null) {
            String proj = bound.projectId();
            String sess = bound.title() != null && !bound.title().isBlank()
                    ? bound.sessionId() + " \"" + bound.title() + "\""
                    : bound.sessionId();
            terminal.info(row("project", proj));
            terminal.info(row("session", sess));
            String process = sessions.activeProcess();
            if (process != null && !process.isBlank()) {
                terminal.info(row("process", process));
            }
        } else {
            terminal.info(row("project", "— (no session bound)"));
        }
        if (welcome.getServer() != null) {
            terminal.info(row("brain", "Vance " + welcome.getServer().getVersion()
                    + " (protocol v" + welcome.getServer().getProtocolVersion() + ")"));
        }
        terminal.info(row("endpoint", config.getBrain().getWsBase()
                + "/brain/" + welcome.getTenantId() + "/ws"));
        terminal.info(row("token", "expires " + instant(connection.currentTokenExpiry())));
    }

    private void renderStored() {
        Path dir = paths.activeDir();
        String scope = paths.isActiveLocal() ? "project-local" : "global";
        Optional<ProjectBinding> bindingOpt = bindingStore.load(dir);
        Optional<AccessData> accessOpt = accessStore.load(dir);

        if (bindingOpt.isEmpty() && accessOpt.isEmpty()) {
            terminal.info("Stored login config: none in " + dir + " (run /login to create one).");
            return;
        }

        terminal.info("Stored login config (" + scope + ", " + dir + "):");
        bindingOpt.ifPresent(binding -> {
            ProjectBinding.Brain brain = binding.getBrain();
            if (brain != null) {
                terminal.info(row("brain http", brain.getHttpBase()));
                terminal.info(row("brain ws", brain.getWsBase()));
            }
            terminal.info(row("tenant", binding.getTenant()));
            terminal.info(row("username", binding.getUsername()));
            terminal.info(row("project", binding.getProject()));
        });
        accessOpt.ifPresent(access -> {
            if (bindingOpt.isEmpty()) {
                terminal.info(row("username", access.getUsername()));
            }
            terminal.info(row("access token", "expires " + instant(access.getAccessExpiresAt())));
            terminal.info(row("refresh token", "expires " + instant(access.getRefreshExpiresAt())));
        });

        maybeWarnDivergence(bindingOpt.orElse(null));
    }

    /**
     * Points out when the live identity and the stored binding disagree — the
     * common cause of "why am I acting as someone else than the config says".
     */
    private void maybeWarnDivergence(@Nullable ProjectBinding binding) {
        WelcomeData welcome = connection.lastWelcome();
        if (welcome == null || binding == null) {
            return;
        }
        boolean tenantDiffers = binding.getTenant() != null
                && !binding.getTenant().equals(welcome.getTenantId());
        boolean userDiffers = binding.getUsername() != null
                && !binding.getUsername().equals(welcome.getUserId());
        if (tenantDiffers || userDiffers) {
            terminal.warn("Note: the live connection differs from the stored config"
                    + (tenantDiffers ? " (tenant " + welcome.getTenantId()
                            + " vs " + binding.getTenant() + ")" : "")
                    + (userDiffers ? " (user " + welcome.getUserId()
                            + " vs " + binding.getUsername() + ")" : "") + ".");
        }
    }

    private static String user(WelcomeData welcome) {
        String u = welcome.getUserId();
        return welcome.getDisplayName() == null || welcome.getDisplayName().isBlank()
                ? u
                : u + " (" + welcome.getDisplayName() + ")";
    }

    /** Left-aligned {@code label : value} row; a blank/null value renders as {@code —}. */
    private static String row(String label, @Nullable String value) {
        String v = (value == null || value.isBlank()) ? "—" : value;
        return String.format("  %-14s %s", label, v);
    }

    private static String instant(@Nullable Long epochMillis) {
        return epochMillis == null ? "—" : Instant.ofEpochMilli(epochMillis).toString();
    }
}
