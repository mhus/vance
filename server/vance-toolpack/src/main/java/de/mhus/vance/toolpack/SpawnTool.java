package de.mhus.vance.toolpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Tool} that spawns a new Process, Workflow-Run,
 * Scheduler entry, Event subscription or Hook — anything that creates
 * additional execution behind the scenes.
 *
 * <p>The {@code ScopeLevel.TRIGGER_SCOPED} sandbox (see
 * {@code specification/trigger-actions.md} §8) refuses to dispatch
 * {@code @SpawnTool}-annotated tools, because a trigger-scoped script
 * (scheduler tick, event hit) lacks the surrounding Process/Workflow
 * that gives an LLM-issued spawn its semantics. Wer von einem Trigger
 * aus etwas spawnen will, baut einen Workflow.
 *
 * <p>An annotation is the right place for this rather than a registry
 * because every spawn-capable tool advertises the trait directly and
 * the {@code AllSpawnToolsAnnotatedTest} can spot drift via reflection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SpawnTool {
}
