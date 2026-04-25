package de.mhus.vance.foot.ui;

/**
 * Which surface currently owns the terminal.
 *
 * <ul>
 *   <li>{@link #CHAT} — JLine 3 REPL active, {@code readLine} loop running.</li>
 *   <li>{@link #FULLSCREEN} — Lanterna {@code Screen} active in the alternate
 *       screen buffer; JLine paused.</li>
 * </ul>
 */
public enum UiMode {
    CHAT,
    FULLSCREEN
}
