package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ide.IdeBridgeService;
import de.mhus.vance.foot.ide.IdeLockfile;
import de.mhus.vance.foot.ide.IdeTools;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code /ide [files | diag [path]]} — surfaces the Claude Code IDE plugin
 * bridge to the user. Reports connection status, lists open editor tabs,
 * dumps diagnostics. Bridge needs to be enabled via
 * {@code vance-foot chat --intellij-claude}; without it the command prints
 * a hint and does nothing else.
 *
 * <p>Subcommand surface intentionally narrow in v1 — write-side tools
 * (open, diff, format) gate on the engine integration described in
 * planning §3.6.
 */
@Component
public class IdeSlashCommand implements SlashCommand {

    private final IdeBridgeService bridge;
    private final ChatTerminal terminal;

    public IdeSlashCommand(IdeBridgeService bridge, ChatTerminal terminal) {
        this.bridge = bridge;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "ide";
    }

    @Override
    public String description() {
        return "Show IDE bridge status; subcommands: files | diag [path].";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("subcommand", List.of("files", "diag")));
    }

    @Override
    public void execute(List<String> args) {
        if (args.isEmpty()) {
            printStatus();
            return;
        }
        switch (args.get(0).toLowerCase()) {
            case "files" -> printFiles();
            case "diag" -> printDiagnostics(args.size() > 1 ? args.get(1) : null);
            default -> terminal.error("Usage: /ide [files | diag [path]]");
        }
    }

    private void printStatus() {
        if (!bridge.isConnected()) {
            Optional<IdeLockfile> lf = bridge.activeLockfile();
            if (lf.isEmpty()) {
                terminal.info("IDE bridge: disabled or no Claude Code IDE plugin found "
                        + "(start with --intellij-claude to enable).");
            } else {
                terminal.warn("IDE bridge: not connected — last lockfile " + lf.get().path());
            }
            return;
        }
        IdeLockfile lf = bridge.activeLockfile().orElse(null);
        if (lf == null) {
            terminal.info("IDE bridge: connected.");
            return;
        }
        terminal.info("IDE bridge: connected to "
                + (lf.ideName() == null ? "IDE plugin" : lf.ideName())
                + " on port " + lf.port()
                + ", workspace " + lf.workspaceFolders());
    }

    private void printFiles() {
        Optional<IdeTools> tools = bridge.tools();
        if (tools.isEmpty()) {
            terminal.warn("IDE bridge not connected — try /ide for status.");
            return;
        }
        try {
            List<Path> files = tools.get().getOpenedFiles();
            if (files.isEmpty()) {
                terminal.info("No files open in the IDE.");
                return;
            }
            for (Path file : files) {
                terminal.info("  " + file);
            }
        } catch (Exception e) {
            terminal.error("get_all_opened_file_paths failed: " + e.getMessage());
        }
    }

    private void printDiagnostics(String pathArg) {
        Optional<IdeTools> tools = bridge.tools();
        if (tools.isEmpty()) {
            terminal.warn("IDE bridge not connected — try /ide for status.");
            return;
        }
        try {
            JsonNode result = tools.get().getDiagnostics(pathArg == null ? null : Path.of(pathArg));
            String text = IdeTools.extractTextContent(result);
            if (text == null || text.isBlank()) {
                terminal.info("No diagnostics.");
                return;
            }
            for (String line : text.split("\n")) {
                terminal.info(line);
            }
        } catch (Exception e) {
            terminal.error("getDiagnostics failed: " + e.getMessage());
        }
    }
}
