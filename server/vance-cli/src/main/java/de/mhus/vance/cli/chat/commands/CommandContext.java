package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.cli.VanceCliConfig;
import de.mhus.vance.cli.chat.ConnectionManager;

/**
 * Capabilities a {@link Command} can use. Kept as an interface so commands do
 * not depend on the concrete {@code ChatCommand} orchestrator and can be tested
 * with a plain fake.
 */
public interface CommandContext {

    /** Info line in the chat history (grey, leading "·"). */
    void info(String text);

    /** System line (cyan, leading "*"). For lifecycle events. */
    void system(String text);

    /** Error line (red, leading "!!"). */
    void error(String text);

    /** Outbound-message line (green, leading "→"). */
    void sent(String text);

    ConnectionManager connection();

    CommandRegistry registry();

    VanceCliConfig config();

    /** Wipes the history panel. */
    void clearHistory();

    /** Request a clean exit of the TUI. Must be safe to call repeatedly. */
    void quit();
}
