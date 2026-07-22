import { reactive } from 'vue';
import type { Editor } from '@tiptap/core';
import type { ComposeRunResult } from '../extensions/VanceCompose';
import { clearComposeManaged, writeComposeOutputs } from '../extensions/composeOutputs';
import { COMPOSE_NODE_NAMES, isAutoRunDisabled } from './composeFamily';

/** REST surface the batch drives — same shape the per-block run uses. */
export interface ComposeBatchHost {
  runCompose: (yaml: string) => Promise<ComposeRunResult>;
  pollCompose: (runId: string) => Promise<ComposeRunResult>;
  cancelCompose?: (runId: string) => Promise<ComposeRunResult>;
}

/** Live state of a page's "Run All Until" — shared by every block on the editor. */
export interface ComposeBatchState {
  /** A batch is iterating right now. */
  active: boolean;
  /** Document position of the block currently running (so it can light up). */
  currentPos: number | null;
  /** Server runId of the current step (enables cancel). */
  currentRunId: string | null;
  /** "(i/n)" progress label. */
  label: string | null;
  /** Live tail of the current step (rendered by the running block). */
  progress: ComposeRunResult | null;
  /** Position of the block a batch halted on (failure), so it can show the error. */
  failedPos: number | null;
  /** Error message of the halted step. */
  error: string | null;
  /** Set by {@link abort}; the loop stops after the current step. */
  aborted: boolean;
}

const POLL_MS = 3000;
const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

// Per-editor batch state: a Run-All-Until is a page-level thing, so every block's
// NodeView must observe the *same* state (the one currently running lights up,
// the rest wait). Keyed weakly by the editor so it's collected with the editor.
const states = new WeakMap<Editor, ComposeBatchState>();

function stateFor(editor: Editor): ComposeBatchState {
  let s = states.get(editor);
  if (!s) {
    s = reactive<ComposeBatchState>({
      active: false,
      currentPos: null,
      currentRunId: null,
      label: null,
      progress: null,
      failedPos: null,
      error: null,
      aborted: false,
    });
    states.set(editor, s);
  }
  return s;
}

/**
 * Page-level batch operations shared by every compose-family block (the full
 * `vance-compose` and the `vance-compose-*` script blocks): "Run All Until" runs
 * each block from the top through the trigger in document order, sequentially —
 * each awaits the previous — and persists every block's outputs back to *its*
 * position; "Clear All Output" drops the managed outputs of all of them.
 *
 * <p>Because the state is per-editor, whichever block is running lights up
 * (via {@link ComposeBatchState#currentPos}) while the others wait, instead of
 * all the activity showing on the block whose menu triggered the batch.
 */
export function useComposeBatch(editor: Editor, host: ComposeBatchHost) {
  const state = stateFor(editor);

  function persist(pos: number, src: string, res: ComposeRunResult) {
    const outputs = (res.tasks ?? []).flatMap((t) =>
      (t.outputs ?? []).map((o) => ({ path: o.path, uri: o.uri, kind: o.kind, title: o.title })));
    const newYaml = res.success ? writeComposeOutputs(src, outputs) : clearComposeManaged(src);
    editor.commands.command(({ tr }) => {
      tr.setNodeAttribute(pos, 'yaml', newYaml);
      return true;
    });
  }

  /** Run one block's YAML to completion, streaming its tail into the batch state. */
  async function step(src: string): Promise<ComposeRunResult> {
    const res = await host.runCompose(src);
    if (!res.running || !res.runId) return res;
    state.currentRunId = res.runId;
    try {
      for (;;) {
        if (state.aborted) return { running: false, success: false, error: 'cancelled' };
        await delay(POLL_MS);
        const p = await host.pollCompose(res.runId);
        state.progress = p;
        if (!p.running) return p;
      }
    } finally {
      state.currentRunId = null;
    }
  }

  async function runAllUntil(throughPos: number): Promise<void> {
    if (state.active) return;
    const blocks: { pos: number; yaml: string }[] = [];
    editor.state.doc.descendants((n, pos) => {
      if (COMPOSE_NODE_NAMES.has(n.type.name) && pos <= throughPos) {
        const src = (n.attrs?.yaml as string | undefined) ?? '';
        if (!isAutoRunDisabled(src)) blocks.push({ pos, yaml: src });
      }
      return true;
    });
    if (!blocks.length) return;

    state.active = true;
    state.aborted = false;
    state.progress = null;
    state.failedPos = null;
    state.error = null;
    try {
      for (let i = 0; i < blocks.length; i += 1) {
        if (state.aborted) break;
        const b = blocks[i];
        state.currentPos = b.pos;
        state.label = `Run All Until… (${i + 1}/${blocks.length})`;
        state.progress = null;
        // Re-read the current YAML — a prior step's persist is an attr-only edit
        // (positions don't shift), so the collected pos is still valid.
        const node = editor.state.doc.nodeAt(b.pos);
        const src = (node?.attrs?.yaml as string | undefined) ?? b.yaml;
        const res = await step(src);
        persist(b.pos, src, res);
        if (!res.success) {
          state.failedPos = b.pos;
          state.error = res.error ?? 'Block fehlgeschlagen';
          break;
        }
      }
    } finally {
      state.active = false;
      state.currentPos = null;
      state.label = null;
      state.progress = null;
    }
  }

  function clearAllOutput(): void {
    state.failedPos = null;
    state.error = null;
    const positions: number[] = [];
    editor.state.doc.descendants((n, pos) => {
      if (COMPOSE_NODE_NAMES.has(n.type.name)) positions.push(pos);
      return true;
    });
    // Attr-only edits don't shift positions, so one command clears them all.
    editor.commands.command(({ tr }) => {
      for (const pos of positions) {
        const node = tr.doc.nodeAt(pos);
        if (node) {
          tr.setNodeAttribute(pos, 'yaml', clearComposeManaged((node.attrs?.yaml as string | undefined) ?? ''));
        }
      }
      return true;
    });
  }

  async function abort(): Promise<void> {
    state.aborted = true;
    const rid = state.currentRunId;
    if (rid && host.cancelCompose) {
      try { await host.cancelCompose(rid); } catch { /* best-effort — the loop guard still winds down */ }
    }
  }

  return { state, runAllUntil, clearAllOutput, abort };
}
