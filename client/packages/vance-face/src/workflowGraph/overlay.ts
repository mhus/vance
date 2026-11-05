/**
 * What a run adds to the drawing of its workflow: per state, whether it has
 * been entered, how it ended, and whether the run stands at it right now.
 *
 * <p>The graph draws the definition and nothing else; this is the only thing
 * a caller can lay on top. It is plain data on purpose — the component that
 * draws it knows nothing about runs, steps or sources, and the run view
 * knows nothing about nodes.
 */
export type GraphStateTone =
  /** The state ended with an outcome that counts as success. */
  | 'ok'
  /** The state ended with any other outcome (error, timeout, rejected, …). */
  | 'other'
  /** The run is executing this state right now. */
  | 'current'
  /** The run stands before this state and has not started it (debug halt). */
  | 'halted';

export interface GraphStateRun {
  tone: GraphStateTone;
  /** How often the state was entered — more than once in a loop. */
  entries: number;
  /** How the latest entry ended; absent while it is still running or halted. */
  outcome?: string;
  /** How long the latest entry took, in milliseconds. */
  durationMs?: number;
  /** The latest entry did not run: its result was given to the run. */
  simulated?: boolean;
}

/** State name → what happened there. States the run never reached are absent. */
export type GraphOverlay = Record<string, GraphStateRun>;
