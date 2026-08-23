package de.mhus.vance.foot.command;

import de.mhus.vance.foot.remote.RemoteControlGate;
import de.mhus.vance.foot.remote.RemoteControlService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code /remote} — control who may drive this CLI client from elsewhere.
 *
 * <ul>
 *   <li>{@code /remote} / {@code /remote status} — mode, approval, watchers.</li>
 *   <li>{@code /remote allow} — permit remote input for the rest of this
 *       process. The answer to the hint printed when a watcher attaches while
 *       the client is in {@code ask} mode.</li>
 *   <li>{@code /remote deny} — revoke that approval; output keeps streaming.</li>
 *   <li>{@code /remote on} / {@code /remote off} — announce this client or make
 *       it invisible. {@code off} also drops any approval.</li>
 * </ul>
 *
 * <p>Worth being blunt about in the help text: a watcher with input rights can
 * run anything this foot can, which includes shell commands on this machine.
 * That is why {@code allow} is an act, not a default.
 */
@Component
public class RemoteSlashCommand implements SlashCommand {

    private final RemoteControlGate gate;
    private final RemoteControlService remote;
    private final ChatTerminal terminal;

    public RemoteSlashCommand(RemoteControlGate gate,
                              RemoteControlService remote,
                              ChatTerminal terminal) {
        this.gate = gate;
        this.remote = remote;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "remote";
    }

    @Override
    public String description() {
        return "Remote control of this client: status | allow | deny | on | off.";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("action", List.of("status", "allow", "deny", "on", "off")));
    }

    @Override
    public void execute(List<String> args) {
        String action = args.isEmpty() ? "status" : args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> printStatus();
            case "allow" -> {
                if (gate.approve()) {
                    terminal.info("Remote input allowed for this session. Revoke with /remote deny.");
                    remote.publishState();
                } else {
                    terminal.error("Remote control is off — enable it first with /remote on.");
                }
            }
            case "deny" -> {
                gate.revoke();
                terminal.info("Remote input revoked. Output keeps streaming to attached watchers.");
                remote.publishState();
            }
            case "on" -> {
                gate.setMode(RemoteControlGate.MODE_ASK);
                remote.announceNow();
                terminal.info("Remote control on (ask mode) — input still needs /remote allow.");
            }
            case "off" -> {
                gate.setMode(RemoteControlGate.MODE_OFF);
                remote.shutdownChannel();
                terminal.info("Remote control off — this client is no longer listed.");
            }
            default -> terminal.error("Usage: /remote [status|allow|deny|on|off].");
        }
    }

    private void printStatus() {
        terminal.info("Remote control: mode=" + gate.mode()
                + ", input=" + (gate.isInputAllowed() ? "allowed" : "blocked")
                + ", watchers=" + remote.watcherCount());
        if (gate.isEnabled() && !gate.isInputAllowed()) {
            terminal.info("  Run /remote allow to let an attached watcher type here.");
        }
    }
}
