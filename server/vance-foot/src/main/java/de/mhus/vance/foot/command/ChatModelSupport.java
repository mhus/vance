package de.mhus.vance.foot.command;

import de.mhus.vance.api.chatmodel.ChatModelDto;
import de.mhus.vance.api.chatmodel.ChatModelListDto;
import de.mhus.vance.api.chatmodel.ChatModelPricingDto;
import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.api.command.ProcessCommandRequest;
import de.mhus.vance.api.command.ProcessCommandResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing of {@code /model}, {@code /models} and {@code /ui-models}:
 * the selectable-model list (REST, same as the Web-UI picker), the
 * {@code //model} / {@code //thinking} engine commands, and the formatting
 * all three render. The brain validates every choice again — nothing here
 * is enforcement.
 */
@Component
public class ChatModelSupport {

    /** The picker's "no override" choice — {@code //model clear}. */
    public static final String RECIPE_DEFAULT = "";

    private final ConnectionService connection;
    private final SessionService sessions;
    private final BrainRestClientService rest;
    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatModelSupport(
            ConnectionService connection, SessionService sessions, BrainRestClientService rest, ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.rest = rest;
        this.terminal = terminal;
    }

    /** One engine command of an apply: verb plus its text argument. */
    public record Step(String verb, String text) {}

    /** A refused or failed engine command; the message is the brain's. */
    public static class ModelCommandException extends Exception {
        public ModelCommandException(String message) {
            super(message);
        }
    }

    /** The active process, or {@code null} after printing why there is none. */
    public @Nullable String requireActiveProcess() {
        if (sessions.current() == null) {
            terminal.error("No bound session — /connect, then /session-resume or /session-create.");
            return null;
        }
        String process = sessions.activeProcess();
        if (process == null || process.isBlank()) {
            terminal.error("No active process — /process <name> first.");
            return null;
        }
        return process;
    }

    /** Project of the bound session, or {@code null} without one. */
    public @Nullable String boundProject() {
        SessionService.BoundSession bound = sessions.current();
        return bound == null ? null : bound.projectId();
    }

    public ChatModelListDto listModels(String projectId) throws Exception {
        return rest.chatModels(projectId);
    }

    /**
     * Sends {@code //verb text} to {@code process}. Returns the reply
     * message and the {@link ChatModelStateDto} it carries ({@code null}
     * for {@code //thinking}, which replies without a state).
     */
    public Reply run(String process, String verb, String text) throws Exception {
        ProcessCommandRequest req = ProcessCommandRequest.builder()
                .processName(process)
                .command(verb)
                .params(text.isEmpty() ? Map.of() : Map.of("text", text))
                .build();
        ProcessCommandResponse resp = connection.request(
                MessageType.PROCESS_COMMAND, req, ProcessCommandResponse.class, ChatInputService.COMMAND_TIMEOUT);
        if (resp.getOutcome() != EngineCommandOutcome.OK) {
            throw new ModelCommandException(resp.getMessage() == null ? "//" + verb + " failed" : resp.getMessage());
        }
        ChatModelStateDto state =
                resp.getValue() == null ? null : json.convertValue(resp.getValue(), ChatModelStateDto.class);
        return new Reply(resp.getMessage(), state);
    }

    /** Reply of one engine command. */
    public record Reply(@Nullable String message, @Nullable ChatModelStateDto state) {}

    // ──────────────────── pure helpers (unit-tested) ────────────────────

    /** Every whitespace-separated term must occur in spec, instance or model name. */
    static List<ChatModelDto> filter(List<ChatModelDto> models, @Nullable String filter) {
        if (filter == null || filter.isBlank()) {
            return models;
        }
        String[] terms = filter.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<ChatModelDto> out = new ArrayList<>();
        for (ChatModelDto m : models) {
            String hay =
                    (m.getSpec() + " " + m.getProviderInstance() + " " + m.getModelName()).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String t : terms) {
                if (!hay.contains(t)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(m);
            }
        }
        return out;
    }

    /** {@code 3.00 / 15.00 USD} per million tokens (input / output); {@code no price} without one. */
    static String formatPrice(@Nullable ChatModelPricingDto p) {
        if (p == null) {
            return "no price";
        }
        return amount(p.getInputPerMTok()) + " / " + amount(p.getOutputPerMTok()) + " " + p.getCurrency();
    }

    private static String amount(double v) {
        return String.format(Locale.ROOT, v > 0 && v < 0.1 ? "%.3f" : "%.2f", v);
    }

    static String formatContext(int tokens) {
        if (tokens >= 1_000_000) {
            double m = tokens / 1_000_000.0;
            return (m == Math.floor(m) ? String.valueOf((long) m) : String.format(Locale.ROOT, "%.1f", m)) + "M";
        }
        if (tokens >= 1000) {
            return Math.round(tokens / 1000.0) + "K";
        }
        return String.valueOf(tokens);
    }

    /** One list line: marker, spec, size, context, reasoning flag, price. */
    static String formatRow(ChatModelDto m, boolean current) {
        return (current ? "* " : "  ") + m.getSpec()
                + "  [" + ("SMALL".equals(m.getSize()) ? "small" : "large")
                + ", " + formatContext(m.getContextWindow()) + " ctx"
                + (m.isThinking() ? ", reasoning" : "") + "]  "
                + formatPrice(m.getPricing()) + " /1M";
    }

    /** The picker's starting selection: the override, else the recipe default. */
    static String initialSelection(ChatModelStateDto state) {
        return state.isModelOverridden() ? state.getModel() : RECIPE_DEFAULT;
    }

    /**
     * Whether the level can be chosen for {@code selection}. Unknown models
     * (a recipe default outside the list) stay enabled.
     */
    static boolean supportsThinking(String selection, ChatModelStateDto state, List<ChatModelDto> models) {
        String spec = RECIPE_DEFAULT.equals(selection) ? state.getRecipeModel() : selection;
        if (spec == null) {
            return true;
        }
        for (ChatModelDto m : models) {
            if (m.getSpec().equals(spec)) {
                return m.isThinking();
            }
        }
        return true;
    }

    /**
     * The level the picker starts on. An {@code off} override on a model that
     * cannot reason has no effect (earlier pickers wrote one whenever such a
     * model was picked), so the picker starts on the recipe's level instead
     * — moving to a reasoning model then returns to it unless the user picks
     * another. Same rule as the Web-UI picker
     * ({@code modelPicker.ts#initialThinking}).
     */
    static String initialThinking(ChatModelStateDto state) {
        boolean ineffectiveOff = state.isThinkingOverridden()
                && "off".equals(state.getThinking())
                && !state.isModelSupportsThinking()
                && state.getModelError() == null;
        return ineffectiveOff && state.getRecipeThinking() != null ? state.getRecipeThinking() : state.getThinking();
    }

    /**
     * Commands that move the process from {@code state} to the picked model
     * and level — model first, unchanged values send nothing. Same plan as
     * the Web-UI picker ({@code modelPicker.ts#commandsFor}):
     *
     * <ul>
     *   <li>{@code supportsThinking} false (the picked model cannot reason):
     *       no level command — the process keeps its level for the next
     *       model that can;</li>
     *   <li>a changed level equal to the recipe's is sent as
     *       {@code //thinking clear}, not pinned, so the process follows the
     *       recipe again.</li>
     * </ul>
     */
    static List<Step> stepsFor(ChatModelStateDto state, String selection, String thinking, boolean supportsThinking) {
        List<Step> out = new ArrayList<>();
        if (RECIPE_DEFAULT.equals(selection)) {
            if (state.isModelOverridden()) {
                out.add(new Step("model", "clear"));
            }
        } else if (!state.isModelOverridden() || !state.getModel().equals(selection)) {
            out.add(new Step("model", selection));
        }
        if (supportsThinking && !thinking.equals(state.getThinking())) {
            out.add(new Step("thinking", thinking.equals(state.getRecipeThinking()) ? "clear" : thinking));
        }
        return out;
    }
}
