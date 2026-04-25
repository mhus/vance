package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code vance-foot chat} — enters the JLine REPL. With {@code --connect}
 * the WebSocket is opened before the prompt appears; otherwise the user can
 * trigger it via {@code /connect}.
 */
@Component
@Command(
        name = "chat",
        mixinStandardHelpOptions = true,
        description = "Open the chat REPL.")
public class ChatRunCommand implements Callable<Integer> {

    @Option(names = "--connect",
            description = "Open the WebSocket connection on startup.")
    boolean autoConnect;

    private final ChatRepl repl;
    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public ChatRunCommand(ChatRepl repl, ConnectionService connection, ChatTerminal terminal) {
        this.repl = repl;
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public Integer call() throws Exception {
        if (autoConnect) {
            try {
                connection.connect();
            } catch (Exception e) {
                terminal.error("Auto-connect failed: " + e.getMessage()
                        + " — type /connect to retry.");
            }
        }
        repl.run();
        return 0;
    }
}
