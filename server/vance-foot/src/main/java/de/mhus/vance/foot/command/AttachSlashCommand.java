package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /attach} — stage a local file to ride along with the next chat
 * message, the CLI counterpart to dropping a file into the web composer.
 *
 * <ul>
 *   <li>{@code /attach <path>} — stage a file (repeatable).</li>
 *   <li>{@code /attach} — list what is staged.</li>
 *   <li>{@code /attach clear} — discard the queue.</li>
 * </ul>
 *
 * <p>The file is read at send time, uploaded as a project document and
 * referenced by id; the model receives it as an image / PDF / text
 * content block. Attachments ride on the turn that submitted them and
 * no other — to refer back later, attach again.
 *
 * <p><b>No sandbox gate here, on purpose.</b> The foot sandbox
 * ({@code ClientSecurityService}) exists to stop the <em>brain</em> from
 * reading arbitrary files off this machine. {@code /attach} is the user
 * typing a path on their own keyboard — gating it would ask the user for
 * permission to do what they just asked for. The brain-driven path
 * (a {@code client_file_*} tool) stays gated; do not "harmonise" the two.
 */
@Component
public class AttachSlashCommand implements SlashCommand {

    private final PendingAttachmentService pending;
    private final ChatTerminal terminal;

    public AttachSlashCommand(PendingAttachmentService pending, ChatTerminal terminal) {
        this.pending = pending;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "attach";
    }

    @Override
    public String description() {
        return "Stage a file for the next message: /attach <path>, /attach to list, "
                + "/attach clear to discard.";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.free("path"));
    }

    @Override
    public void execute(List<String> args) {
        if (args.isEmpty()) {
            list();
            return;
        }
        if (args.size() == 1 && "clear".equalsIgnoreCase(args.get(0))) {
            int dropped = pending.clear();
            terminal.info(dropped == 0
                    ? "Nothing was staged."
                    : "Discarded " + dropped + " staged attachment"
                            + (dropped == 1 ? "" : "s") + ".");
            return;
        }
        // Join the tokens: the dispatcher splits on whitespace, and a
        // path with spaces is the normal case on a desktop.
        String raw = String.join(" ", args).trim();
        try {
            Path staged = pending.stage(Path.of(expandHome(raw)));
            terminal.info("📎 " + staged + " — rides with your next message ("
                    + pending.count() + " staged).");
        } catch (IllegalArgumentException e) {   // covers InvalidPathException
            terminal.error("Cannot attach: " + e.getMessage());
        }
    }

    private void list() {
        List<Path> staged = pending.staged();
        if (staged.isEmpty()) {
            terminal.info("No attachments staged. Use /attach <path>.");
            return;
        }
        terminal.info("Staged for the next message:");
        for (Path p : staged) {
            terminal.info("  📎 " + p);
        }
    }

    /** {@code ~} is shell syntax — the dispatcher hands it over unexpanded. */
    private static String expandHome(String raw) {
        if (raw.equals("~")) {
            return System.getProperty("user.home", "~");
        }
        if (raw.startsWith("~/")) {
            return System.getProperty("user.home", "~") + raw.substring(1);
        }
        return raw;
    }
}
