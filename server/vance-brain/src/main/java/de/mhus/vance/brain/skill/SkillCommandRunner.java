package de.mhus.vance.brain.skill;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandDispatcher;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fires a skill's {@code activate:} / {@code deactivate:} command
 * sequence through the {@link EngineCommandDispatcher}. Best-effort:
 * every command in the sequence is dispatched even if an earlier one
 * failed, so a partially-applied {@code deactivate:} still runs its
 * remaining cleanup steps. See {@code planning/engine-commands.md} §4.
 *
 * <p>Caller-provided lane discipline: this must be invoked on the
 * process lane (via the WS handler's lane task or from within a turn),
 * so a command can't race an in-flight turn.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillCommandRunner {

    private final EngineCommandDispatcher dispatcher;

    /**
     * Dispatches every command in {@code commands}. {@code phase} is
     * {@code "activate"} / {@code "deactivate"} for logging only.
     */
    public List<EngineCommandResult> run(
            ThinkProcessDocument process,
            List<EngineCommand> commands,
            String phase,
            String skillName) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<EngineCommandResult> results = new ArrayList<>(commands.size());
        for (EngineCommand command : commands) {
            EngineCommandResult result = dispatcher.dispatch(process, command);
            results.add(result);
            if (result.outcome() == EngineCommandOutcome.ERROR) {
                log.warn("Skill '{}' {} command '{}' → ERROR: {} (id='{}')",
                        skillName, phase, command.name(), result.message(), process.getId());
            } else {
                log.info("Skill '{}' {} command '{}' → {} (id='{}')",
                        skillName, phase, command.name(), result.outcome(), process.getId());
            }
        }
        return results;
    }
}
