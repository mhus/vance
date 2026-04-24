package de.mhus.vance.cli.debug;

import de.mhus.vance.cli.chat.ChatLine;
import java.util.List;

/**
 * Minimal read/write surface the {@link DebugRestServer} needs against the TUI.
 * Kept as an interface so the server has no direct dependency on the chat
 * implementation and stays trivially fakeable from tests.
 */
public interface DebugUiBridge {

    /** Immutable snapshot of all history lines currently held by the UI. */
    List<ChatLine> historySnapshot();

    /** Up to {@code limit} most-recent lines, oldest first. */
    List<ChatLine> historyTail(int limit);

    /** Current content of the input field (not yet submitted). */
    String currentInput();

    /**
     * Inject {@code text} into the input field. When {@code submit} is true,
     * the same code path as pressing ENTER is triggered.
     */
    void injectInput(String text, boolean submit);

    /** Request a TUI redraw — the server uses this after mutating the UI. */
    void requestRedraw();
}
