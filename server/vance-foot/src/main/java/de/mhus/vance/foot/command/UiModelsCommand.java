package de.mhus.vance.foot.command;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import de.mhus.vance.api.chatmodel.ChatModelDto;
import de.mhus.vance.api.chatmodel.ChatModelListDto;
import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-models} — fullscreen model + reasoning picker, the terminal
 * twin of the Web-UI dialog (planning/model-picker.md §6): filter line, the
 * selectable models with size, context, reasoning flag and price, the
 * recipe default as first row, and the reasoning level below. Apply sends
 * {@code //model} / {@code //thinking} — the brain validates again.
 */
@Component
public class UiModelsCommand implements SlashCommand {

    private final ChatModelSupport support;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public UiModelsCommand(ChatModelSupport support, ChatTerminal terminal, InterfaceService ui) {
        this.support = support;
        this.terminal = terminal;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ui-models";
    }

    @Override
    public String description() {
        return "Pick the active process's model and reasoning level in a fullscreen UI.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String process = support.requireActiveProcess();
        String project = support.boundProject();
        if (process == null || project == null) {
            return;
        }
        ChatModelListDto list;
        ChatModelStateDto state;
        try {
            list = support.listModels(project);
            state = support.run(process, "model", "get").state();
        } catch (ChatModelSupport.ModelCommandException e) {
            terminal.error("/ui-models: " + e.getMessage());
            return;
        }
        if (state == null) {
            terminal.error("/ui-models: the brain reported no model state");
            return;
        }
        ChatModelStateDto initial = state;
        List<String> outcome = new ArrayList<>();
        ui.runFullscreen(session -> {
            View view = new View(session.gui(), process, list, initial, outcome);
            session.gui().addWindowAndWait(view.window);
        });
        outcome.forEach(terminal::info);
    }

    private final class View {

        private final WindowBasedTextGUI gui;
        private final String process;
        private final ChatModelListDto list;
        private final ChatModelStateDto state;
        private final List<String> outcome;

        private final BasicWindow window = new BasicWindow("Model and reasoning");
        private final TextBox filterBox = new TextBox();
        private final ActionListBox listBox = new ActionListBox();
        private final Label selectedLabel = new Label("");
        private final ComboBox<String> levelBox = new ComboBox<>();
        private final Label levelHint = new Label("");
        private String selection;

        View(
                WindowBasedTextGUI gui,
                String process,
                ChatModelListDto list,
                ChatModelStateDto state,
                List<String> outcome) {
            this.gui = gui;
            this.process = process;
            this.list = list;
            this.state = state;
            this.outcome = outcome;
            this.selection = ChatModelSupport.initialSelection(state);
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(new Label("Current: " + state.getModel()
                    + (state.isModelOverridden() ? " (override)" : " (recipe default)")
                    + " · reasoning: " + state.getThinking()
                    + "  —  applies from the next turn"));

            Panel filterRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
            filterRow.addComponent(new Label("Filter:"));
            filterBox.setPreferredSize(new TerminalSize(40, 1));
            filterBox.setTextChangeListener((text, byUser) -> rebuildList());
            filterRow.addComponent(filterBox);
            root.addComponent(filterRow);

            Border listBorder = listBox.withBorder(
                    Borders.singleLine("Models — Enter selects, price input / output per 1M tokens"));
            listBorder.setLayoutData(
                    LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(listBorder);
            root.addComponent(selectedLabel);

            Panel levelRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
            levelRow.addComponent(new Label("Reasoning:"));
            for (String level : list.getThinkingLevels()) {
                levelBox.addItem(level);
            }
            levelBox.setSelectedItem(state.getThinking());
            levelRow.addComponent(levelBox);
            levelRow.addComponent(levelHint);
            root.addComponent(levelRow);

            Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("Apply", this::apply));
            actions.addComponent(new Button("Cancel", window::close));
            root.addComponent(actions);

            window.setComponent(root);
            rebuildList();
            updateSelection();
            window.setFocusedInteractable(filterBox);
        }

        private void rebuildList() {
            listBox.clearItems();
            String recipe = state.getRecipeModel() == null ? "" : " (" + state.getRecipeModel() + ")";
            listBox.addItem(
                    marker(ChatModelSupport.RECIPE_DEFAULT) + "Recipe default" + recipe,
                    () -> select(ChatModelSupport.RECIPE_DEFAULT));
            for (ChatModelDto m : ChatModelSupport.filter(list.getModels(), filterBox.getText())) {
                listBox.addItem(
                        marker(m.getSpec())
                                + ChatModelSupport.formatRow(m, false).substring(2),
                        () -> select(m.getSpec()));
            }
        }

        private String marker(String spec) {
            return spec.equals(selection) ? "* " : "  ";
        }

        private void select(String spec) {
            int index = listBox.getSelectedIndex();
            selection = spec;
            rebuildList();
            listBox.setSelectedIndex(index);
            updateSelection();
        }

        private void updateSelection() {
            selectedLabel.setText(
                    "Selected: " + (ChatModelSupport.RECIPE_DEFAULT.equals(selection) ? "recipe default" : selection));
            boolean thinking = ChatModelSupport.supportsThinking(selection, state, list.getModels());
            levelBox.setEnabled(thinking);
            levelHint.setText(thinking ? "" : "  this model has no adjustable reasoning");
        }

        private void apply() {
            boolean thinking = ChatModelSupport.supportsThinking(selection, state, list.getModels());
            String level = thinking && levelBox.getSelectedItem() != null ? levelBox.getSelectedItem() : "off";
            List<ChatModelSupport.Step> steps = ChatModelSupport.stepsFor(state, selection, level);
            try {
                for (ChatModelSupport.Step step : steps) {
                    ChatModelSupport.Reply reply = support.run(process, step.verb(), step.text());
                    if (reply.message() != null) {
                        outcome.add("//" + step.verb() + ": " + reply.message());
                    }
                }
            } catch (Exception e) {
                showError(gui, "Apply failed", e.getMessage());
                return;
            }
            if (steps.isEmpty()) {
                outcome.add("model unchanged");
            }
            window.close();
        }
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
