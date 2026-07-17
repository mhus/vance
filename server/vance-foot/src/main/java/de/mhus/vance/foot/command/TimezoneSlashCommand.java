package de.mhus.vance.foot.command;

import de.mhus.vance.api.profile.ProfileDto;
import de.mhus.vance.api.profile.ProfileSettingWriteRequest;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code /timezone [zone|auto]} — read or set the account's display
 * timezone. The value is stored server-side as the user setting
 * {@code display.timezone} (cascade user → tenant → UTC) and drives the
 * "current date" prompt block, the {@code current_time} tool default,
 * and the timezone a scheduler you create is pinned to.
 *
 * <ul>
 *   <li>{@code /timezone} — show the current setting and this machine's zone.</li>
 *   <li>{@code /timezone auto} — set it to this machine's zone.</li>
 *   <li>{@code /timezone Europe/Berlin} — set an explicit IANA zone.</li>
 * </ul>
 */
@Component
public class TimezoneSlashCommand implements SlashCommand {

    /**
     * Setting key. Literal on purpose — vance-foot depends on
     * {@code vance-api} only, so the {@code TimezoneResolver.Keys}
     * constant in {@code vance-shared} is off-limits here. Must stay in
     * sync with {@code TimezoneResolver.Keys.DISPLAY_TIMEZONE}.
     */
    private static final String TIMEZONE_KEY = "display.timezone";

    private final BrainRestClientService rest;
    private final FootConfig config;
    private final ChatTerminal terminal;

    public TimezoneSlashCommand(
            BrainRestClientService rest,
            FootConfig config,
            ChatTerminal terminal) {
        this.rest = rest;
        this.config = config;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "timezone";
    }

    @Override
    public String description() {
        return "Show or set your timezone (/timezone [<IANA zone>|auto]).";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("zone", List.of("auto")));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.isEmpty()) {
            showCurrent();
            return;
        }
        String arg = args.get(0).trim();
        String zone;
        if ("auto".equalsIgnoreCase(arg)) {
            zone = ZoneId.systemDefault().getId();
        } else {
            try {
                // Normalise through ZoneId so we store a canonical id and
                // reject typos before they reach the server.
                zone = ZoneId.of(arg).getId();
            } catch (DateTimeException e) {
                terminal.error("Unknown timezone '" + arg
                        + "' — use an IANA id like 'Europe/Berlin', or 'auto'.");
                return;
            }
        }
        setTimezone(zone);
        terminal.info("Timezone set to " + zone + ".");
    }

    private void showCurrent() throws Exception {
        String current = currentTimezone();
        String machine = ZoneId.systemDefault().getId();
        if (current == null || current.isBlank()) {
            terminal.info("Timezone: not set (server falls back to the tenant default or UTC). "
                    + "This machine: " + machine + ". Use '/timezone auto' to adopt it.");
        } else {
            terminal.info("Timezone: " + current + ". This machine: " + machine + ".");
        }
    }

    private @org.jspecify.annotations.Nullable String currentTimezone() throws Exception {
        ProfileDto profile = rest.get(profilePath(), ProfileDto.class);
        Map<String, String> settings = profile.getWebUiSettings();
        return settings == null ? null : settings.get(TIMEZONE_KEY);
    }

    private void setTimezone(String zone) throws Exception {
        rest.put(profilePath() + "/settings/" + TIMEZONE_KEY,
                ProfileSettingWriteRequest.builder().value(zone).build(),
                ProfileDto.class);
    }

    private String profilePath() {
        return "/brain/" + config.getAuth().getTenant() + "/profile";
    }
}
