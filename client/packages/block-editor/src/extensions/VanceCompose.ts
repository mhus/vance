import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceComposeNodeView from './VanceComposeNodeView.vue';

/**
 * A single produced output, with its content URL already resolved by the
 * host (the block-editor stays decoupled from tenant/REST).
 */
export interface ComposeOutputView {
  path: string;
  /** {@code vance-workspace:/<dir>/<rel>} URI; the host renderer resolves content. */
  uri: string;
  kind?: string;
  mime?: string;
  title?: string;
}

export interface ComposeTaskView {
  status: string;
  error?: string;
  log?: string;
  outputs?: ComposeOutputView[];
}

/**
 * Result of a compose run/poll, as the host hands it back to the NodeView.
 * Runs are async: {@code running} + {@code runId} means the host should be
 * polled via {@code pollCompose(runId)}; a terminal result carries
 * {@code tasks}. {@code tail}/{@code currentTask*} are live progress.
 */
export interface ComposeRunResult {
  success?: boolean;
  workspace?: string;
  error?: string;
  tasks?: ComposeTaskView[];
  runId?: string;
  running?: boolean;
  status?: string;
  currentTaskIndex?: number;
  currentTaskType?: string;
  tail?: string[];
}

/**
 * Tiptap node for {@code ```vance-compose} blocks — an inline Damogran
 * compose task cell. The fence body IS the compose YAML (edited inline in
 * the workbook); running posts that YAML to the host {@code runCompose}
 * callback and the returned outputs render underneath, notebook-style.
 * The block-editor stays decoupled from REST — running + output-URL
 * resolution live in the host.
 */
export const VanceCompose = Node.create({
  name: 'vanceCompose',
  group: 'block',
  atom: false,
  content: '',
  draggable: false,
  selectable: false,

  addAttributes() {
    return {
      yaml: { default: '' }, // the raw compose manifest (fence body)
    };
  },

  parseHTML() {
    return [{ tag: 'aside[data-vance-compose]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-compose': '',
        class: 'vance-compose',
      }),
    ];
  },

  addOptions() {
    return {
      /** Host-provided run: start the compose (async); returns inline result or a runId. */
      runCompose: null as null | ((yaml: string) => Promise<ComposeRunResult>),
      /** Host-provided poll: fetch an in-flight run's status/tail/result by id. */
      pollCompose: null as null | ((runId: string) => Promise<ComposeRunResult>),
      /** Host-provided cancel: stop an in-flight run (kills the current exec). */
      cancelCompose: null as null | ((runId: string) => Promise<ComposeRunResult>),
      /**
       * Host-injected renderer for a single output ({@code vance-face}'s
       * ComposeOutput, via provide/inject) — mounted per artifact so the block
       * previews outputs exactly like the Cortex view. Lazy accessor.
       */
      composeOutputComponent: null as null | (() => import('vue').Component | null),
      /** Project id for the output renderer (workspace-file URL resolution). */
      projectId: '' as string,
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceComposeNodeView as never);
  },
});
