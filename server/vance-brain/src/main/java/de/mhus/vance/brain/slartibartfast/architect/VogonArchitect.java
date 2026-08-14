package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes a plan for <em>thinking work</em> — the kind a person starts in a
 * conversation and waits on.
 *
 * <p>Structurally identical to what {@link MagratheaArchitect} produces,
 * and validated by exactly the same parser: since the merge there is one
 * plan grammar, and a second one would only be a second thing to keep
 * correct. What differs is the advice. An automation is written around
 * <em>things that must happen</em> — commands, tools, retries, timers. A
 * Vogon plan is written around <em>judgements</em>: a worker produces
 * something, another reads it, the plan branches on how good it was and
 * goes round again if it has to.
 *
 * <p>That is why both presets survive the merge. They answer different
 * questions for the author, and folding them together would mean giving
 * the same advice to both — either shell-flavoured advice to someone
 * structuring an argument, or judgement-flavoured advice to someone
 * wiring up a nightly job.
 *
 * <p>The plan itself does not care: a Vogon plan may run a script, a
 * workflow may score an answer. Nothing here forbids either — this is
 * guidance, not validation.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class VogonArchitect extends MagratheaArchitect {

    public VogonArchitect(MagratheaWorkflowLoader workflowLoader, RecipeLoader recipeLoader) {
        super(workflowLoader, recipeLoader);
    }

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.VOGON_PLAN;
    }

    @Override
    public String artefactNoun() {
        return "plan";
    }

    @Override
    public String proposingSystemPrompt() {
        return """
                You are the PROPOSING node of the Slartibartfast engine.
                From the framed goal and the subgoals you produce a PLAN
                that will be run on behalf of a person, inside their
                session: they can be asked questions while it runs, and
                they get the result back in the conversation.

                Emit a JSON object:
                  { "name": "<kebab-case>", "yaml": "<plan yaml>",
                    "justifications": [...], "shapeRationale": "..." }

                The YAML is a state machine:
                  start: <state name>
                  states:
                    <name>:
                      type: agent_task | gate_task | condition_task |
                            tool_task | shell_task | script_task |
                            timer_task | workflow_task | terminal
                      on:    { <outcome>: <state> }
                      catch: { <error kind>: <state> }

                WRITING A PLAN FOR THINKING WORK

                Most states are agent_task: a worker recipe does a piece
                of the work and its answer is the material for the next
                step. Chain them by writing each one's output with
                `storeAs:` and reading it in the next one's prompt with
                ${state.<key>}.

                Judge the work rather than assuming it. An agent_task can
                declare how its answer should be read:

                  decide:                       # a classification
                    options: [ok, needs_work, unusable]
                  # the chosen word becomes the outcome, so route it in on:

                  score:                        # a graded judgement
                    bands:
                      - { atLeast: 0.7, outcome: approved }
                      - { atLeast: 0.2, outcome: revise }
                      - { default: true, outcome: rejected }
                  # the model answers with JSON containing score: 0.0–1.0

                Iterate deliberately. A revise-branch that routes back to
                the writing state is a loop, and a loop needs a bound:

                  writer:
                    type: agent_task
                    enterCounter: rounds        # counts entries
                    ...
                  review:
                    type: agent_task
                    score: { bands: [...] }
                    on: { approved: publish, revise: check_rounds }
                  check_rounds:
                    type: condition_task
                    transitions:
                      - if: "#state['rounds'] >= 4"
                        to: ask_human
                      - else: writer

                Put the counter's reset on the state that BEGINS the
                section (`resetCounters: [rounds]`), or a second pass
                through it inherits the first pass's count and gives up
                early.

                Ask the person when a judgement is genuinely theirs — a
                direction to take, an approval before something
                irreversible. Use gate_task; the question reaches them in
                the conversation and also waits in their inbox.

                A worker may be given the conversation it is working
                inside, with `inheritContext: summary` (or `all`,
                `last:<n>`). Use it where the step would otherwise be
                blind to what was already discussed.

                RULES
                - `start:` must name a declared state.
                - Every on/catch/transition target must be a declared state.
                - Every agent_task.recipe must be a recipe from the list
                  below; default to 'ford' when unsure.
                - Reach a terminal on every path — `type: terminal` with
                  `outcome: success|failure` and an optional `result:`,
                  which is what the person gets back.
                - Do not put a whole task into one giant prompt. If it has
                  stages, it has states.
                """;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nEmit a corrected plan YAML as a JSON object with a valid "
                + "name, a `start:` naming a declared state, a non-empty "
                + "`states:` map, every on/catch/transition target pointing "
                + "at a declared state, every agent_task.recipe referencing "
                + "a known recipe from the list above, and a terminal state "
                + "reachable on every path.";
    }
}
