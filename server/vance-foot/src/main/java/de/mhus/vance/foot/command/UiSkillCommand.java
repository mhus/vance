package de.mhus.vance.foot.command;

import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.api.skills.ProcessSkillRequest;
import de.mhus.vance.api.skills.ProcessSkillResponse;
import de.mhus.vance.api.skills.SkillArgumentDto;
import de.mhus.vance.api.skills.SkillReferenceDocDto;
import de.mhus.vance.api.skills.SkillScriptDto;
import de.mhus.vance.api.skills.SkillSummaryDto;
import de.mhus.vance.api.skills.SkillTriggerDto;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-skill} — fullscreen Lanterna skill manager, the visual
 * counterpart of the web-UI skill panel: one row per skill (active and
 * available), Enter opens the detail view with the skill's full
 * read-only metadata, and the same three actions the panel offers —
 * activate (with an optional argument prompt), deactivate (honouring
 * recipe-bound skills, skills.md §7a) and the details themselves.
 *
 * <p>Row union mirrors the web panel: available skills plus
 * active-but-unlisted ones (a skill disabled in its source while
 * running must stay visible and clearable).
 *
 * <p>All mutations go through the {@code process-skill} channel on the
 * process lane — no LLM turn is fired by listing or clearing, and an
 * activation's possible {@code action:} turn shows up as work in the
 * chat, not here.
 */
@Component
public class UiSkillCommand implements SlashCommand {

    private static final Duration WS_TIMEOUT = Duration.ofSeconds(15);

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public UiSkillCommand(
            ConnectionService connection, SessionService sessions, ChatTerminal terminal, InterfaceService ui) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ui-skill";
    }

    @Override
    public String description() {
        return "Open the active process's skills in a fullscreen UI.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String processName = sessions.activeProcess();
        if (processName == null || processName.isBlank()) {
            terminal.error("No active process — use /process-activate first");
            return;
        }
        ui.runFullscreen(session -> {
            View view = new View(session.gui(), processName);
            view.refresh();
            session.gui().addWindowAndWait(view.window);
        });
    }

    /**
     * One row of the master list — summary plus its active ref, if any.
     * Package-private so row assembly and formatting are unit-testable.
     */
    record Row(SkillSummaryDto summary, @Nullable ActiveSkillRefDto ref) {}

    /** Master list of the active process's skills. */
    private final class View {

        private final WindowBasedTextGUI gui;
        private final BasicWindow window;
        private final Label header = new Label("");
        private final ActionListBox listBox = new ActionListBox();
        private List<Row> rows = List.of();

        private final String processName;

        View(WindowBasedTextGUI gui, String processName) {
            this.gui = gui;
            this.processName = processName;
            this.window = new BasicWindow("Skills");
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(header);

            Border listBorder = listBox.withBorder(Borders.singleLine("Skills"));
            listBorder.setLayoutData(
                    LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(listBorder);

            Panel actions = new Panel();
            actions.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("Refresh", this::refresh));
            actions.addComponent(new Button("Quit", window::close));
            root.addComponent(actions);

            window.setComponent(root);
        }

        void refresh() {
            ProcessSkillResponse response;
            try {
                response = sendSkillRequest(ProcessSkillRequest.builder()
                        .processName(processName)
                        .command(ProcessSkillCommand.LIST)
                        .build());
            } catch (Exception e) {
                rows = List.of();
                showError("Load failed", e.getMessage());
                rebuildList();
                return;
            }
            List<SkillSummaryDto> available = response == null || response.getAvailableSkills() == null
                    ? List.of()
                    : response.getAvailableSkills();
            List<ActiveSkillRefDto> active =
                    response == null || response.getActiveSkills() == null ? List.of() : response.getActiveSkills();
            rows = mergeRows(available, active);
            rebuildList();
        }

        private void rebuildList() {
            listBox.clearItems();
            if (rows.isEmpty()) {
                header.setText("No skills in this scope.");
                return;
            }
            long activeCount = rows.stream().filter(row -> row.ref() != null).count();
            header.setText(rows.size() + " skill" + (rows.size() == 1 ? "" : "s") + ", " + activeCount
                    + " active — Enter to open");
            for (Row row : rows) {
                listBox.addItem(formatRow(row), () -> openDetail(row));
            }
        }

        private void openDetail(Row row) {
            Detail detail = new Detail(gui, processName, row);
            gui.addWindowAndWait(detail.window);
            if (detail.mutated) {
                refresh();
            }
        }

        private void showError(String title, @Nullable String message) {
            UiSkillCommand.showError(gui, title, message);
        }
    }

    /** One skill: full read-only metadata and the panel's activate/deactivate actions. */
    private final class Detail {

        private final WindowBasedTextGUI gui;
        private final String processName;
        /** Swapped on refreshRow — the active marker follows the server state. */
        private Row row;

        private final BasicWindow window;
        private final Label statusLabel = new Label("");
        private final TextBox details;
        private boolean mutated = false;

        Detail(WindowBasedTextGUI gui, String processName, Row row) {
            this.gui = gui;
            this.processName = processName;
            this.row = row;
            this.window = new BasicWindow("Skill — " + row.summary().getTitle());
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            this.details = new TextBox("", TextBox.Style.MULTI_LINE);
            details.setReadOnly(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(statusLabel);

            Border box = details.withBorder(Borders.singleLine("Details"));
            box.setLayoutData(
                    LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(box);

            Panel actions = new Panel();
            actions.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("Activate", this::activate));
            actions.addComponent(new Button("Deactivate", this::deactivate));
            actions.addComponent(new Button("Close", window::close));
            root.addComponent(actions);

            window.setComponent(root);
            render();
        }

        private void render() {
            statusLabel.setText(statusLine());
            details.setText(renderDetails(row.summary()));
        }

        private String statusLine() {
            StringBuilder b = new StringBuilder();
            b.append("source=").append(Objects.toString(row.summary().getSource(), "?"));
            boolean macro = "shot".equals(row.summary().getLifecycle());
            b.append("  lifecycle=").append(macro ? "shot (macro)" : "sticky");
            ActiveSkillRefDto ref = row.ref();
            if (ref == null) {
                b.append("  [not active]");
            } else {
                b.append("  [active");
                if (ref.isOneShot()) {
                    b.append(", once");
                }
                if (ref.isFromRecipe()) {
                    b.append(", recipe-bound");
                }
                b.append(']');
            }
            if (!row.summary().isEnabled()) {
                b.append("  [disabled]");
            }
            return b.toString();
        }

        /**
         * Activation with an optional argument prompt — the CLI twin of
         * the web panel writing {@code /skill <name> } into the composer:
         * the brain decides whether the trailing text binds into the
         * skill's template or arrives as a plain user message (§6).
         */
        private void activate() {
            String args = TextInputDialog.showDialog(
                    gui,
                    "Activate " + row.summary().getName(),
                    "Skill arguments (optional, appended after the skill name):",
                    "");
            ProcessSkillResponse response;
            try {
                response = sendSkillRequest(ProcessSkillRequest.builder()
                        .processName(processName)
                        .command(ProcessSkillCommand.ACTIVATE)
                        .skillName(row.summary().getName())
                        .oneShot(false)
                        .args(args == null || args.isBlank() ? null : args.trim())
                        .build());
            } catch (Exception e) {
                showError("Activate failed", e.getMessage());
                return;
            }
            mutated = true;
            info(
                    "Activate",
                    SkillCommandHelper.activationMessage(
                            row.summary().getName(), response, false, args == null || args.isBlank()));
            refreshRow();
        }

        /** Same guard the web panel carries: recipe-bound skills stay (§7a). */
        private void deactivate() {
            ActiveSkillRefDto ref = row.ref();
            if (ref != null && ref.isFromRecipe()) {
                info("Deactivate", "Bundled by the recipe — stays active for the process lifetime.");
                return;
            }
            ProcessSkillResponse response;
            try {
                response = sendSkillRequest(ProcessSkillRequest.builder()
                        .processName(processName)
                        .command(ProcessSkillCommand.CLEAR)
                        .skillName(row.summary().getName())
                        .build());
            } catch (Exception e) {
                showError("Deactivate failed", e.getMessage());
                return;
            }
            mutated = true;
            info("Deactivate", SkillCommandHelper.clearMessage(row.summary().getName(), response));
            refreshRow();
        }

        /** Re-reads LIST so the active marker matches the server state. */
        private void refreshRow() {
            try {
                ProcessSkillResponse response = sendSkillRequest(ProcessSkillRequest.builder()
                        .processName(processName)
                        .command(ProcessSkillCommand.LIST)
                        .build());
                for (ActiveSkillRefDto ref : response.getActiveSkills() == null
                        ? List.<ActiveSkillRefDto>of()
                        : response.getActiveSkills()) {
                    if (ref.getName().equals(row.summary().getName())) {
                        row = new Row(row.summary(), ref);
                        render();
                        return;
                    }
                }
                row = new Row(row.summary(), null);
                render();
            } catch (Exception e) {
                // Non-fatal: the mutation reply already told the user what happened.
                render();
            }
        }

        private void showError(String title, @Nullable String message) {
            UiSkillCommand.showError(gui, title, message);
        }

        private void info(String title, String message) {
            new MessageDialogBuilder()
                    .setTitle(title)
                    .setText(message)
                    .addButton(MessageDialogButton.OK)
                    .build()
                    .showDialog(gui);
        }
    }

    // ── row assembly and formatting ────────────────────────────────────

    /**
     * Available skills plus active-but-unlisted ones — the same union the
     * web panel builds. An active skill that dropped out of the cascade
     * listing must stay visible and clearable.
     */
    static List<Row> mergeRows(List<SkillSummaryDto> available, List<ActiveSkillRefDto> active) {
        Map<String, Row> byName = new LinkedHashMap<>();
        for (SkillSummaryDto summary : available) {
            byName.put(summary.getName(), new Row(summary, null));
        }
        for (ActiveSkillRefDto ref : active) {
            Row existing = byName.get(ref.getName());
            byName.put(
                    ref.getName(),
                    existing == null ? new Row(activeOnlySummary(ref), ref) : new Row(existing.summary(), ref));
        }
        return List.copyOf(byName.values());
    }

    /** Fallback summary for an active skill with no cascade listing behind it. */
    private static SkillSummaryDto activeOnlySummary(ActiveSkillRefDto ref) {
        return SkillSummaryDto.builder()
                .name(ref.getName())
                .title(ref.getName())
                .enabled(true)
                .build();
    }

    static String formatRow(Row row) {
        StringBuilder b = new StringBuilder();
        b.append(row.ref() == null ? "  " : "✓ ");
        b.append(pad(row.summary().getName(), 24));
        ActiveSkillRefDto ref = row.ref();
        if (ref != null && ref.isOneShot()) {
            b.append("(once) ");
        }
        if (ref != null && ref.isFromRecipe()) {
            b.append("(recipe) ");
        }
        if ("shot".equals(row.summary().getLifecycle())) {
            b.append("(macro) ");
        }
        b.append('[').append(Objects.toString(row.summary().getSource(), "?")).append("] ");
        String description = row.summary().getDescription();
        if (description != null && !description.isBlank()) {
            b.append(oneLine(description, 48));
        }
        return b.toString();
    }

    /**
     * The detail text — the CLI twin of the web panel's info modal:
     * triggers, arguments, tools, manuals, reference docs, scripts and
     * the activate/deactivate command sequences (skills.md §2).
     */
    static String renderDetails(SkillSummaryDto skill) {
        StringBuilder b = new StringBuilder();
        b.append("Title: ").append(Objects.toString(skill.getTitle(), skill.getName()));
        if (skill.getVersion() != null && !skill.getVersion().isBlank()) {
            b.append("  v").append(skill.getVersion());
        }
        b.append("\nName: ").append(skill.getName());
        b.append("\nSource: ").append(Objects.toString(skill.getSource(), "?"));
        b.append("\n");
        appendSection(
                b,
                "Description",
                skill.getDescription() == null || skill.getDescription().isBlank()
                        ? null
                        : List.of(skill.getDescription()));
        if ("shot".equals(skill.getLifecycle())) {
            appendSection(b, "Lifecycle", List.of("shot — fires once as a prompt/config macro, never becomes active."));
        } else {
            appendSection(
                    b, "Lifecycle", List.of("sticky — stays active until cleared, body injected into every turn."));
        }
        List<String> triggerTokens = new ArrayList<>();
        List<SkillTriggerDto> triggers = skill.getTriggers() == null ? List.of() : skill.getTriggers();
        for (SkillTriggerDto trigger : triggers) {
            if (trigger.getKeywords() != null) {
                triggerTokens.addAll(trigger.getKeywords());
            }
            if (trigger.getPattern() != null) {
                triggerTokens.add("/" + trigger.getPattern() + "/");
            }
        }
        if (!triggerTokens.isEmpty()) {
            appendSection(b, "Auto-activation", List.of(String.join(", ", triggerTokens)));
        }
        List<String> argumentLines = new ArrayList<>();
        List<SkillArgumentDto> declaredArguments = skill.getArguments() == null ? List.of() : skill.getArguments();
        for (SkillArgumentDto argument : declaredArguments) {
            StringBuilder line = new StringBuilder();
            line.append(argument.getName())
                    .append(" (")
                    .append(argument.getType())
                    .append(')');
            if (argument.isRequired()) {
                line.append(" *required*");
            }
            if (argument.getDescription() != null && !argument.getDescription().isBlank()) {
                line.append(" — ").append(argument.getDescription());
            }
            argumentLines.add(line.toString());
        }
        if (!argumentLines.isEmpty()) {
            argumentLines.add("→ append as trailing text: /skill " + skill.getName() + " <values…>");
        }
        appendSection(b, "Arguments", argumentLines);
        appendSection(b, "Tools", skill.getTools());
        appendSection(b, "Manuals", skill.getManualPaths());
        List<String> referenceDocLines = new ArrayList<>();
        List<SkillReferenceDocDto> referenceDocs =
                skill.getReferenceDocs() == null ? List.of() : skill.getReferenceDocs();
        for (SkillReferenceDocDto doc : referenceDocs) {
            StringBuilder line = new StringBuilder(doc.getTitle());
            if (doc.getSummary() != null && !doc.getSummary().isBlank()) {
                line.append(" — ").append(doc.getSummary());
            }
            line.append("  (")
                    .append(
                            doc.getLoadMode() == null
                                    ? "?"
                                    : doc.getLoadMode().name().toLowerCase())
                    .append(')');
            referenceDocLines.add(line.toString());
        }
        appendSection(b, "Reference docs", referenceDocLines);
        List<String> scriptLines = new ArrayList<>();
        List<SkillScriptDto> scripts = skill.getScripts() == null ? List.of() : skill.getScripts();
        for (SkillScriptDto script : scripts) {
            StringBuilder line = new StringBuilder();
            line.append("skill_").append(skill.getName()).append("__").append(script.getName());
            line.append(" (").append(Objects.toString(script.getTarget(), "?")).append(')');
            if (script.getDescription() != null && !script.getDescription().isBlank()) {
                line.append(" — ").append(script.getDescription());
            }
            scriptLines.add(line.toString());
        }
        appendSection(b, "Scripts", scriptLines);
        appendSection(b, "On activation", skill.getActivate());
        appendSection(b, "On deactivation", skill.getDeactivate());
        return b.toString();
    }

    private static void appendSection(StringBuilder b, String title, @Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        b.append("\n").append(title).append(":\n");
        for (String line : lines) {
            b.append("  ").append(line).append('\n');
        }
    }

    private static String pad(@Nullable String value, int width) {
        String s = value == null ? "" : value;
        if (s.length() >= width) {
            return s.substring(0, Math.max(0, width - 1)) + " ";
        }
        return s + " ".repeat(width - s.length());
    }

    /** Collapse newlines and clamp — list rows must stay single-line. */
    private static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 1)) + "…";
    }

    private ProcessSkillResponse sendSkillRequest(ProcessSkillRequest request) throws Exception {
        return connection.request(MessageType.PROCESS_SKILL, request, ProcessSkillResponse.class, WS_TIMEOUT);
    }

    private static void showError(WindowBasedTextGUI gui, String title, @Nullable String message) {
        new MessageDialogBuilder()
                .setTitle(title)
                .setText(message == null ? "(no message)" : message)
                .addButton(MessageDialogButton.OK)
                .build()
                .showDialog(gui);
    }
}
