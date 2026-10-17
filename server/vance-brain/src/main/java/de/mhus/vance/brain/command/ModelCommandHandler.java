package de.mhus.vance.brain.command;

import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ChatModelService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Generic {@code model} verb — switch the model of a running process
 * (engine-agnostic). Subcommands (in the command's {@code text} argument):
 *
 * <ul>
 *   <li>{@code <spec>} — set the override. {@code providerInstance:modelName}
 *       or an alias; validated by {@link ChatModelService#validate} (catalog,
 *       configured instance, {@code chat.allowed-models}) and stored
 *       <em>resolved</em>, so a later alias change does not silently move
 *       the user's choice;</li>
 *   <li>{@code get} (or empty) — show the effective model and reasoning level;</li>
 *   <li>{@code clear} — drop the override, back to the recipe's model.</li>
 * </ul>
 *
 * <p>Writes {@code engineParamOverrides.model}, which
 * {@link ChatBehaviorBuilder#readModelSpec} reads before the recipe's
 * {@code params.model}; while it is set the recipe's
 * {@code fallbackModels} are ignored. The override applies to this process
 * only — spawned workers keep their recipe model. Every reply carries the
 * resulting {@link ChatModelStateDto} as value.
 *
 * <p>Off the lane on purpose: the model picker asks and sets while a turn
 * is running, and must not wait for it. That is safe because the only
 * write is one atomic field update on the override map (no handler or
 * engine saves the whole process document over it), and the engine reads
 * the model fresh when it builds the next turn's chat.
 */
@Component
@RequiredArgsConstructor
public class ModelCommandHandler implements EngineCommandHandler {

    private final ThinkProcessService thinkProcessService;
    private final ChatModelService chatModelService;

    @Override
    public String verb() {
        return "model";
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String arg = argText(command);
        if (arg.isEmpty() || "get".equalsIgnoreCase(arg)) {
            ChatModelStateDto state = chatModelService.stateOf(process);
            return EngineCommandResult.ok(render(state), state);
        }
        if ("clear".equalsIgnoreCase(arg)) {
            return clear(process);
        }
        return set(process, arg);
    }

    private EngineCommandResult set(ThinkProcessDocument process, String spec) {
        ChatModelService.Selection selection;
        try {
            selection = chatModelService.validate(process, spec);
        } catch (ChatModelService.ModelNotSelectableException e) {
            return EngineCommandResult.error(e.getMessage());
        }
        String id = process.getId();
        if (id == null
                || !thinkProcessService.setEngineParamOverride(
                        id, ChatBehaviorBuilder.MODEL_OVERRIDE_KEY, selection.spec())) {
            return EngineCommandResult.error("process not found");
        }
        ChatModelStateDto state = stateAfterWrite(id, process);
        return EngineCommandResult.ok("model set to " + selection.spec() + " (from next turn)", state);
    }

    private EngineCommandResult clear(ThinkProcessDocument process) {
        String id = process.getId();
        if (id == null
                || !thinkProcessService.setEngineParamOverride(id, ChatBehaviorBuilder.MODEL_OVERRIDE_KEY, null)) {
            return EngineCommandResult.error("process not found");
        }
        ChatModelStateDto state = stateAfterWrite(id, process);
        return EngineCommandResult.ok("model override cleared — back to " + state.getModel(), state);
    }

    private ChatModelStateDto stateAfterWrite(String id, ThinkProcessDocument fallback) {
        return chatModelService.stateOf(thinkProcessService.findById(id).orElse(fallback));
    }

    static String render(ChatModelStateDto state) {
        String note;
        if (state.getModelError() != null) {
            note = " — model cannot be resolved: " + state.getModelError()
                    + (state.isModelOverridden() ? " (//model clear or pick another model)" : "");
        } else {
            note = state.isModelSupportsThinking() ? "" : " — model has no reasoning support";
        }
        return "model: " + state.getModel()
                + (state.isModelOverridden() ? " (runtime override)" : " (recipe default)")
                + " · thinking: " + state.getThinking()
                + (state.isThinkingOverridden() ? " (runtime override)" : " (recipe default)")
                + note;
    }

    private static String argText(EngineCommand command) {
        Object text = command.args().get("text");
        return text == null ? "" : text.toString().trim();
    }
}
