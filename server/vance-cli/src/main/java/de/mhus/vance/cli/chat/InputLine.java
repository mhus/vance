package de.mhus.vance.cli.chat;

import com.consolemaster.AnsiColor;
import com.consolemaster.AnsiFormat;
import com.consolemaster.Canvas;
import com.consolemaster.Event;
import com.consolemaster.EventHandler;
import com.consolemaster.Graphics;
import com.consolemaster.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Single-line text input with cursor, in-line editing and persistent history.
 * Console-master ships a read-only {@code Text} widget but no line editor, so
 * this class renders the buffer itself and reacts to {@link KeyEvent}s dispatched
 * through the focus chain.
 *
 * <p>Keys:
 * <ul>
 *   <li>printable characters — insert at cursor</li>
 *   <li>BACKSPACE / DELETE — delete before / at cursor</li>
 *   <li>ARROW_LEFT / ARROW_RIGHT / HOME / END — move cursor</li>
 *   <li>ARROW_UP / ARROW_DOWN — walk history</li>
 *   <li>ENTER — emit value through {@code onSubmit}, reset buffer, push to history</li>
 * </ul>
 */
public class InputLine extends Canvas implements EventHandler {

    private static final String PROMPT = "> ";

    private final StringBuilder buffer = new StringBuilder();
    private final List<String> history = new ArrayList<>();

    /** Index into {@link #history} while browsing (0 = oldest). -1 means "not browsing". */
    private int historyCursor = -1;
    /** Caret position inside {@link #buffer}, always 0..buffer.length(). */
    private int cursor = 0;

    private @Nullable Consumer<String> onSubmit;

    public InputLine(String name) {
        super(name, 1, 1);
        setCanFocus(true);
    }

    public void setOnSubmit(Consumer<String> onSubmit) {
        this.onSubmit = onSubmit;
    }

    @Override
    public void paint(Graphics graphics) {
        graphics.clear();
        int w = getWidth();
        if (w <= 0) {
            return;
        }

        AnsiColor promptColor = isHasFocus() ? AnsiColor.BRIGHT_YELLOW : AnsiColor.BRIGHT_BLACK;
        graphics.drawStyledString(0, 0, PROMPT, promptColor, null, AnsiFormat.BOLD);

        int fieldX = PROMPT.length();
        int fieldW = Math.max(0, w - fieldX);
        if (fieldW == 0) {
            return;
        }

        // Scroll the visible window so the cursor is always inside it.
        int visibleStart = 0;
        if (cursor >= fieldW) {
            visibleStart = cursor - fieldW + 1;
        }
        int visibleEnd = Math.min(buffer.length(), visibleStart + fieldW);
        String shown = buffer.substring(visibleStart, visibleEnd);

        // Draw buffer, padding with spaces so the line background covers the whole field.
        StringBuilder padded = new StringBuilder(fieldW);
        padded.append(shown);
        while (padded.length() < fieldW) {
            padded.append(' ');
        }
        graphics.drawStyledString(fieldX, 0, padded.toString(), AnsiColor.BRIGHT_WHITE, null);

        // Draw cursor as inverted char at its position within the visible window.
        if (isHasFocus()) {
            int cx = fieldX + (cursor - visibleStart);
            char under = (cursor < buffer.length()) ? buffer.charAt(cursor) : ' ';
            graphics.drawStyledChar(cx, 0, under, AnsiColor.BLACK, AnsiColor.BRIGHT_WHITE);
        }
    }

    @Override
    public void handleEvent(Event event) {
        if (!(event instanceof KeyEvent key) || event.isConsumed()) {
            return;
        }

        if (key.isSpecialKey()) {
            switch (key.getSpecialKey()) {
                case ENTER -> submit();
                case BACKSPACE -> deleteBefore();
                case DELETE -> deleteAt();
                case ARROW_LEFT -> cursor = Math.max(0, cursor - 1);
                case ARROW_RIGHT -> cursor = Math.min(buffer.length(), cursor + 1);
                case HOME -> cursor = 0;
                case END -> cursor = buffer.length();
                case ARROW_UP -> walkHistory(-1);
                case ARROW_DOWN -> walkHistory(1);
                default -> {
                    return; // let other keys bubble (e.g. TAB, F-keys)
                }
            }
            key.consume();
            return;
        }

        if (key.isCharacter() && !key.isHasCtrl() && !key.isHasAlt()) {
            char ch = key.getCharacter();
            if (ch >= 32 && ch != 127) {
                buffer.insert(cursor, ch);
                cursor++;
                historyCursor = -1;
                key.consume();
            }
        }
    }

    private void submit() {
        String value = buffer.toString();
        buffer.setLength(0);
        cursor = 0;
        historyCursor = -1;
        if (!value.isEmpty()) {
            if (history.isEmpty() || !history.get(history.size() - 1).equals(value)) {
                history.add(value);
            }
        }
        Consumer<String> cb = onSubmit;
        if (cb != null) {
            cb.accept(value);
        }
    }

    private void deleteBefore() {
        if (cursor > 0) {
            buffer.deleteCharAt(cursor - 1);
            cursor--;
        }
    }

    private void deleteAt() {
        if (cursor < buffer.length()) {
            buffer.deleteCharAt(cursor);
        }
    }

    private void walkHistory(int direction) {
        if (history.isEmpty()) {
            return;
        }
        if (historyCursor == -1) {
            historyCursor = history.size();
        }
        int next = historyCursor + direction;
        if (next < 0) {
            next = 0;
        }
        if (next > history.size()) {
            next = history.size();
        }
        historyCursor = next;

        buffer.setLength(0);
        if (historyCursor < history.size()) {
            buffer.append(history.get(historyCursor));
        }
        cursor = buffer.length();
    }
}
