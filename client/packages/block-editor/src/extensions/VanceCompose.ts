import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceComposeNodeView from './VanceComposeNodeView.vue';

/**
 * A single produced output, with its content URL already resolved by the
 * host (the block-editor stays decoupled from tenant/REST).
 */
export interface ComposeOutputView {
  path: string;
  kind?: string;
  title?: string;
  /** Absolute workspace-file URL, ready for {@code <img src>} / {@code <a href>}. */
  href: string;
}

export interface ComposeTaskView {
  status: string;
  error?: string;
  log?: string;
  outputs?: ComposeOutputView[];
}

/** Result of running a compose, as the host hands it back to the NodeView. */
export interface ComposeRunResult {
  success: boolean;
  workspace?: string;
  error?: string;
  tasks: ComposeTaskView[];
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
      /** Host-provided run: execute the inline compose YAML, resolve outputs. */
      runCompose: null as null | ((yaml: string) => Promise<ComposeRunResult>),
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceComposeNodeView as never);
  },
});
