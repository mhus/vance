package de.mhus.vance.foot.command;

import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * JLine 3 {@link Completer} for the REPL: completes slash-command names
 * on the first token, falls back to per-command {@link ArgSpec} for
 * subsequent tokens.
 *
 * <p>v1 covers the static suggestions: command names, ENUM-kind arg
 * choices. Dynamic kinds (process, session, skill, …) emit no
 * candidates today — Tab simply does nothing for them. The dispatch
 * happens through the spec, so a future cache on top of {@code SessionService}
 * or a brain round-trip can be wired in here without touching the
 * commands themselves.
 *
 * <p>{@link CommandService} is injected lazily because the
 * {@code CommandService} bean is built from all {@link SlashCommand}
 * beans — eager injection here is safe (we are not a {@code SlashCommand}),
 * but the lazy proxy keeps the wiring symmetric with {@code HelpCommand}
 * and avoids surprising future restructures.
 */
@Component
public class SlashCompleter implements Completer {

    private final CommandService commandService;
    private final SuggestionCache suggestionCache;

    public SlashCompleter(@Lazy CommandService commandService, SuggestionCache suggestionCache) {
        this.commandService = commandService;
        this.suggestionCache = suggestionCache;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        int wordIndex = line.wordIndex();
        if (wordIndex < 0) return;

        if (wordIndex == 0) {
            completeCommandName(line.word(), candidates);
            return;
        }
        completeArgument(line, candidates);
    }

    private void completeCommandName(String word, List<Candidate> candidates) {
        if (!word.startsWith("/")) {
            // Free-chat input — leave Tab to insert a literal tab if the
            // line reader is configured for it. We don't suggest commands
            // mid-sentence in v1 (Claude Code's MidInput slash is a Bonus).
            return;
        }
        for (SlashCommand cmd : commandService.all()) {
            String full = "/" + cmd.name();
            // value=full so the inserted text replaces the partial slash;
            // descr shows the one-line description in JLine's menu view
            // when multiple completions are listed; complete=true so JLine
            // appends a space after the name (ready for arg input).
            candidates.add(new Candidate(
                    full, full, null, cmd.description(), null, null, true));
        }
    }

    private void completeArgument(ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        if (words.isEmpty()) return;
        String first = words.get(0);
        if (!first.startsWith("/")) return;

        SlashCommand cmd = commandService.find(first.substring(1));
        if (cmd == null) return;

        List<ArgSpec> specs = cmd.argSpec();
        if (specs.isEmpty()) return;

        int argIdx = line.wordIndex() - 1;
        // Variadic tail: clamp to last spec so a trailing message argument
        // keeps the same completion behavior on every word.
        ArgSpec arg = specs.get(Math.min(argIdx, specs.size() - 1));

        List<String> values = valuesFor(arg);
        for (String choice : values) {
            candidates.add(new Candidate(choice, choice, null, null, null, null, true));
        }
    }

    /**
     * Resolve a spec to its current set of completion strings. ENUM uses
     * the spec's static choices; the dynamic kinds defer to {@link SuggestionCache}
     * which returns immediately with stale data and refreshes in the
     * background — so the first Tab on an empty cache returns nothing,
     * the second returns the just-fetched values.
     */
    private List<String> valuesFor(ArgSpec arg) {
        return switch (arg.kind()) {
            case ENUM -> arg.choices();
            case PROCESS -> suggestionCache.processes();
            case SESSION -> suggestionCache.sessions();
            case PROJECT -> suggestionCache.projects();
            case PROJECT_GROUP -> suggestionCache.projectGroups();
            // SKILL is per-process scoped — out of v1 cache scope.
            case SKILL, FREE -> List.of();
        };
    }
}
