package de.mhus.vance.foot.command;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import de.mhus.vance.foot.ui.InterfaceService;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code /demo} — proves the JLine ↔ Lanterna hybrid pattern works on the
 * current host. Pauses JLine, opens a Lanterna window in the alternate screen
 * buffer, returns to the REPL on close.
 */
@Component
public class DemoCommand implements SlashCommand {

    private final InterfaceService ui;

    public DemoCommand(InterfaceService ui) {
        this.ui = ui;
    }

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public String description() {
        return "Open a Lanterna demo window — proof of the hybrid TUI.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        ui.runFullscreen(session -> {
            BasicWindow window = new BasicWindow("Vance Foot — Lanterna demo");
            window.setHints(Set.of(Window.Hint.CENTERED));

            Panel content = new Panel();
            content.setLayoutManager(new LinearLayout(com.googlecode.lanterna.gui2.Direction.VERTICAL));
            content.addComponent(new Label(
                    "JLine is paused. Lanterna owns the screen.\n"
                            + "Press <Close> or Esc to return to the chat REPL."));
            content.addComponent(new Button("Close", window::close));
            window.setComponent(content);

            session.gui().addWindowAndWait(window);
        });
    }
}
