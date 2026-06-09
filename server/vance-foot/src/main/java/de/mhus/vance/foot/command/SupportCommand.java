package de.mhus.vance.foot.command;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import de.mhus.vance.api.fook.FookSubmissionRequestDto;
import de.mhus.vance.api.fook.FookSubmissionResponseDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import de.mhus.vance.foot.ui.Verbosity;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /support [text]} — file a bug report, feature request, or
 * piece of feedback about Vance. Two modes:
 *
 * <ul>
 *   <li><b>Inline</b> ({@code /support Brain crashes on boot}): the
 *       args are joined and submitted immediately. Right for short
 *       reports.</li>
 *   <li><b>Form</b> ({@code /support} alone): pauses JLine, opens a
 *       Lanterna multi-line {@link TextBox} centred on the screen.
 *       Save submits, Cancel/Esc aborts.</li>
 * </ul>
 *
 * <p>Submission goes to {@code POST /brain/{tenant}/fook/submit}.
 * Requires an active brain session (the token-mint runs as part of
 * {@code /connect}); if the user isn't connected the REST call fails
 * with a clear error.
 *
 * <p>The reporter doesn't grade type / title / severity — Fook
 * derives them server-side during triage. The result lands in the
 * user's inbox.
 */
@Component
public class SupportCommand implements SlashCommand {

    private final InterfaceService ui;
    private final ChatTerminal terminal;
    private final BrainRestClientService restClient;
    private final FootConfig config;

    public SupportCommand(
            InterfaceService ui,
            ChatTerminal terminal,
            BrainRestClientService restClient,
            FootConfig config) {
        this.ui = ui;
        this.terminal = terminal;
        this.restClient = restClient;
        this.config = config;
    }

    @Override
    public String name() {
        return "support";
    }

    @Override
    public String description() {
        return "Report a Vance bug, feature request, or feedback. "
                + "/support <text> submits inline; /support alone opens a form.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String inline = args.isEmpty() ? "" : String.join(" ", args).trim();
        String text;
        if (!inline.isEmpty()) {
            text = inline;
        } else {
            text = collectViaForm();
            if (text == null) {
                terminal.println(Verbosity.INFO, "/support cancelled.");
                return;
            }
        }
        if (text.isBlank()) {
            terminal.println(Verbosity.WARN, "/support: empty text — nothing submitted.");
            return;
        }
        submit(text);
    }

    /**
     * Show the Lanterna form and return the entered text, or
     * {@code null} if the user cancelled.
     */
    private @Nullable String collectViaForm() throws IOException {
        AtomicReference<@Nullable String> result = new AtomicReference<>();
        ui.runFullscreen(session -> {
            BasicWindow window = new BasicWindow("Vance — support request");
            window.setHints(Set.of(Window.Hint.CENTERED));
            window.setCloseWindowWithEscape(true);

            Panel content = new Panel();
            content.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            content.addComponent(new Label(
                    "Describe what happened, what you expected, or what you'd want."));
            content.addComponent(new Label(
                    "Fook reads this and picks the type, severity and title."));
            content.addComponent(new Label(""));

            TextBox textBox = new TextBox(new TerminalSize(72, 14), "");
            content.addComponent(textBox);

            Panel buttons = new Panel();
            buttons.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            buttons.addComponent(new Button("Submit", () -> {
                String t = textBox.getText();
                if (t != null) result.set(t);
                window.close();
            }));
            buttons.addComponent(new Button("Cancel", window::close));
            content.addComponent(buttons);

            window.setComponent(content);
            session.gui().addWindowAndWait(window);
        });
        return result.get();
    }

    /** POST the submission and print the outcome line. */
    private void submit(String text) {
        FookSubmissionRequestDto body = FookSubmissionRequestDto.builder()
                .text(text)
                .build();
        String path = "/brain/" + config.getAuth().getTenant() + "/fook/submit";
        try {
            FookSubmissionResponseDto resp = restClient.post(
                    path, body, FookSubmissionResponseDto.class);
            terminal.println(Verbosity.INFO,
                    "Support request submitted (id: %s). Fook is triaging; "
                            + "the outcome will land in your inbox.",
                    resp.getSubmissionId());
        } catch (Exception e) {
            terminal.println(Verbosity.ERROR,
                    "Failed to submit support request: %s", e.getMessage());
        }
    }
}
