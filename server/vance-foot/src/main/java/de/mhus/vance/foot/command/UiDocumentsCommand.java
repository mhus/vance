package de.mhus.vance.foot.command;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.api.documents.DocumentListResponse;
import de.mhus.vance.api.documents.DocumentSummary;
import de.mhus.vance.api.documents.DocumentUpdateRequest;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-documents} — fullscreen Lanterna document browser for the
 * project bound to the current session. Mirrors a subset of the web
 * UI's {@code documents.html} editor.
 *
 * <p>Actions on the selected row:
 * <ul>
 *   <li><b>View</b> — open the inline text in a scrollable read-only
 *       text box. Non-inline (binary) docs report their size and
 *       suggest the Download action instead.</li>
 *   <li><b>Download</b> — write the document content to a local file
 *       (default path = document's file name in the current working
 *       directory).</li>
 *   <li><b>Rename</b> — change the virtual path inside the project.
 *       The server resolves conflicts (409) which we surface as an
 *       error dialog.</li>
 *   <li><b>Delete</b> — soft-delete (move to project trash). Documents
 *       already in trash get hard-deleted; the server enforces that.</li>
 * </ul>
 */
@Component
public class UiDocumentsCommand implements SlashCommand {

    private final BrainRestClientService rest;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public UiDocumentsCommand(BrainRestClientService rest,
                              SessionService sessions,
                              ChatTerminal terminal,
                              InterfaceService ui) {
        this.rest = rest;
        this.sessions = sessions;
        this.terminal = terminal;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ui-documents";
    }

    @Override
    public String description() {
        return "Open the document browser for the current project (view / download / rename / delete).";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            terminal.error("/ui-documents requires a bound session — run /session-bootstrap or --resume first.");
            return;
        }
        String projectId = bound.projectId();
        ui.runFullscreen(session -> {
            View view = new View(session.gui(), projectId);
            view.refresh();
            session.gui().addWindowAndWait(view.window);
        });
    }

    /** Master view: header + list + action buttons. */
    private final class View {

        private final WindowBasedTextGUI gui;
        private final BasicWindow window;
        private final String projectId;
        private final Label header = new Label("");
        private final ActionListBox listBox = new ActionListBox();
        private List<DocumentSummary> items = List.of();

        View(WindowBasedTextGUI gui, String projectId) {
            this.gui = gui;
            this.projectId = projectId;
            this.window = new BasicWindow("Documents — " + projectId);
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(header);

            var listBorder = listBox.withBorder(Borders.singleLine("Items"));
            listBorder.setLayoutData(LinearLayout.createLayoutData(
                    LinearLayout.Alignment.Fill,
                    LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(listBorder);

            Panel actions = new Panel();
            actions.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("View",     this::viewSelected));
            actions.addComponent(new Button("Download", this::downloadSelected));
            actions.addComponent(new Button("Rename",   this::renameSelected));
            actions.addComponent(new Button("Delete",   this::deleteSelected));
            actions.addComponent(new Button("Refresh",  this::refresh));
            actions.addComponent(new Button("Close",    window::close));
            root.addComponent(actions);

            window.setComponent(root);
        }

        void refresh() {
            try {
                DocumentListResponse response = rest.listDocuments(projectId, null, null);
                items = response.getItems() == null ? List.of() : response.getItems();
            } catch (Exception e) {
                terminal.error("Failed to load documents: " + e.getMessage());
                items = List.of();
            }
            header.setText("project=" + projectId + " — " + items.size() + " doc(s)");
            listBox.clearItems();
            if (items.isEmpty()) {
                listBox.addItem("(no documents)", () -> {});
                return;
            }
            for (DocumentSummary d : items) {
                String row = formatRow(d);
                listBox.addItem(row, this::viewSelected);
            }
        }

        private @Nullable DocumentSummary selected() {
            int idx = listBox.getSelectedIndex();
            if (idx < 0 || idx >= items.size()) return null;
            return items.get(idx);
        }

        private void viewSelected() {
            DocumentSummary sel = selected();
            if (sel == null) return;
            try {
                DocumentDto full = rest.getDocument(sel.getId());
                String content;
                if (full.getInlineText() != null) {
                    content = full.getInlineText();
                } else {
                    content = "(non-inline document — " + full.getMimeType()
                            + ", " + full.getSize() + " bytes — use Download to fetch)";
                }
                showContentWindow(displayName(full), content);
            } catch (Exception e) {
                error("View failed: " + e.getMessage());
            }
        }

        private void downloadSelected() {
            DocumentSummary sel = selected();
            if (sel == null) return;
            String defaultName = sel.getName() == null ? "document" : sel.getName();
            String target = TextInputDialog.showDialog(gui, "Download",
                    "Save '" + defaultName + "' to (path):", defaultName);
            if (target == null || target.isBlank()) return;
            try {
                byte[] bytes = rest.downloadDocument(sel.getId());
                Path out = Path.of(target.trim());
                Files.write(out, bytes);
                info("Saved " + bytes.length + " bytes → " + out.toAbsolutePath());
            } catch (Exception e) {
                error("Download failed: " + e.getMessage());
            }
        }

        private void renameSelected() {
            DocumentSummary sel = selected();
            if (sel == null) return;
            String newPath = TextInputDialog.showDialog(gui, "Rename",
                    "New path inside project:", sel.getPath());
            if (newPath == null || newPath.isBlank() || newPath.equals(sel.getPath())) return;
            try {
                DocumentUpdateRequest req = DocumentUpdateRequest.builder()
                        .newPath(newPath.trim())
                        .build();
                rest.updateDocument(sel.getId(), req);
                info("Renamed → " + newPath.trim());
                refresh();
            } catch (Exception e) {
                error("Rename failed: " + e.getMessage());
            }
        }

        private void deleteSelected() {
            DocumentSummary sel = selected();
            if (sel == null) return;
            MessageDialogButton answer = MessageDialog.showMessageDialog(
                    gui, "Confirm delete",
                    "Delete '" + sel.getPath() + "'?\n\n"
                            + "Outside _bin/ → moved to project trash.\n"
                            + "Already in _bin/ → permanent.",
                    MessageDialogButton.Yes, MessageDialogButton.No);
            if (answer != MessageDialogButton.Yes) return;
            try {
                rest.deleteDocument(sel.getId());
                info("Deleted " + sel.getPath());
                refresh();
            } catch (Exception e) {
                error("Delete failed: " + e.getMessage());
            }
        }

        private void showContentWindow(String windowTitle, String content) {
            BasicWindow inner = new BasicWindow(windowTitle);
            inner.setHints(Set.of(Window.Hint.FULL_SCREEN));
            inner.setCloseWindowWithEscape(true);
            // Read-only multi-line text box: pre-fill, then disable editing.
            TextBox box = new TextBox(new TerminalSize(80, 20), content == null ? "" : content);
            box.setReadOnly(true);
            box.setLayoutData(LinearLayout.createLayoutData(
                    LinearLayout.Alignment.Fill,
                    LinearLayout.GrowPolicy.CanGrow));
            Panel p = new Panel();
            p.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            p.addComponent(box.withBorder(Borders.singleLine()));
            p.addComponent(new Button("Close", inner::close));
            inner.setComponent(p);
            gui.addWindowAndWait(inner);
        }

        private void info(String msg) {
            MessageDialog.showMessageDialog(gui, "Documents", msg, MessageDialogButton.OK);
        }

        private void error(String msg) {
            MessageDialog.showMessageDialog(gui, "Error", msg, MessageDialogButton.OK);
        }
    }

    private static String formatRow(DocumentSummary d) {
        return String.format("%-9s  %-40s  %s",
                humanSize(d.getSize()),
                truncate(d.getPath() == null ? "" : d.getPath(), 40),
                d.getTitle() == null ? "" : "\"" + truncate(d.getTitle(), 30) + "\"");
    }

    private static String displayName(DocumentDto d) {
        if (d.getTitle() != null && !d.getTitle().isBlank()) return d.getTitle();
        if (d.getName() != null && !d.getName().isBlank()) return d.getName();
        return d.getPath() == null ? "(unnamed)" : d.getPath();
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + "B";
        if (n < 1024 * 1024) return String.format("%.1fK", n / 1024.0);
        return String.format("%.1fM", n / (1024.0 * 1024));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
