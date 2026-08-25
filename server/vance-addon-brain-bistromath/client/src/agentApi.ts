import type { RenderedView } from './generated/bistromath/RenderedView';
import type { ViewNode } from './generated/bistromath/ViewNode';

/**
 * What the chat beside an app may do with it.
 *
 * <p><b>The sandbox is not in the way here, and that is worth saying.</b> The
 * direction of travel is inward — brain → host → guest — and the host already
 * holds the state (reactive, host-side) and already invokes guest functions by
 * name. So reading and writing state involves the guest not at all, and pressing
 * a button is the same `invoke` that runs `init`.
 *
 * <p><b>Read by default, act by declaration.</b> Three levels, and the middle
 * one is the one worth arguing about:
 *
 * <ul>
 *   <li><b>Reading</b> is free. The agent learns what a reader can already see.
 *   <li><b>Setting state</b> is free, because it is *visible* — the widget
 *       changes on screen — and it commits nothing: a person still presses the
 *       button. Filling a form is exactly this, since form values live in state.
 *   <li><b>Triggering an action</b> needs `agent: true` on the widget. It does
 *       whatever the app may do, on the agent's initiative, possibly while
 *       nobody is looking. Deny is the only defensible default, and the
 *       declaration belongs in the document where a reviewer of the app reads
 *       it.
 * </ul>
 *
 * <p><b>Not browser automation.</b> No DOM, no coordinates, no clicking whatever
 * happens to be under a point. The agent drives the app through its *declared*
 * surface — state keys and declared actions. The other thing is a much larger
 * security story and a different product.
 */
export interface AppAgentApi {
  /** What is open and what can be done with it. */
  describe(): AppDescription;
  /** One state key, or every key when none is named. */
  stateGet(key?: string | null): unknown;
  /** Set one state key. Visible immediately; commits nothing. */
  stateSet(key: string, value: unknown): void;
  /** Trigger a declared action. Rejects unless the widget carries `agent: true`. */
  action(id: string, args?: unknown[]): Promise<void>;
  /**
   * Re-read the app from its documents and restart its program.
   *
   * <p>Needs no declaration, and that is not an oversight: it is the way *back*.
   * Everything an agent can do without asking — setting state, patching a
   * widget — is undone by it, so denying it would leave a confused app with no
   * exit that does not involve the reader. It commits nothing and discards
   * nothing that was ever written down.
   */
  reload(): Promise<void>;
}

export interface AppDescription {
  app: string;
  view: string | null;
  viewLabel: string | null;
  /**
   * The rendered view as an indented tree — **the thing to read.**
   *
   * <p>Shaped after a browser accessibility snapshot, and for the same reason:
   * a model operates a surface well when it can *see* it, and badly when it has
   * to correlate two flat lists. Each line is a widget with its id as the
   * handle, its label, the state key it reads and that key's current value.
   *
   * <p><b>No query language, deliberately.</b> The browser tooling this imitates
   * has no XPath and no CSS either — you read the tree and name a handle. A
   * selector syntax would be a second thing to get wrong, and it would buy
   * nothing here: a widget id is unique within a view and the parser enforces
   * it, so the handle a reader needs is already written in the document.
   */
  snapshot: string;
  /** Every state key a widget reads, so the agent knows what it may set. */
  stateKeys: string[];
  /** Widgets with an action, and whether an agent may trigger it. */
  actions: { id: string; label: string | null; type: string; agent: boolean }[];
  /** Views the app offers, for a `uiShow`-style hint in the answer. */
  views: string[];
}

/** Raised when a widget exists but was not opened to agents. */
export class ActionNotAllowedError extends Error {
  constructor(id: string) {
    super(
      `'${id}' is not open to agents. Add \`agent: true\` to that widget in the view `
        + 'document if it should be.',
    );
    this.name = 'ActionNotAllowedError';
  }
}

/**
 * Walk the view once, collecting what an agent needs to know.
 *
 * <p>Derived from the parsed tree rather than declared a second time: a list an
 * author had to maintain beside the widgets would drift, and the drift would
 * show up as an agent confidently addressing a widget that no longer exists.
 */
export function describeView(
  app: string,
  view: RenderedView | null,
  views: string[],
  state: Record<string, unknown> = {},
  hidden: (node: ViewNode) => boolean = () => false,
): AppDescription {
  const stateKeys: string[] = [];
  const actions: AppDescription['actions'] = [];
  const seenKey = new Set<string>();

  const walk = (node: ViewNode): void => {
    for (const key of [node.from, node.show]) {
      if (key && !seenKey.has(key)) {
        seenKey.add(key);
        stateKeys.push(key);
      }
    }
    if (node.id && node.on && Object.keys(node.on).length > 0) {
      actions.push({
        id: node.id,
        label: node.label ?? node.text ?? null,
        type: node.type,
        agent: node.agent === true,
      });
    }
    for (const child of node.children ?? []) walk(child);
  };
  if (view?.root) walk(view.root);

  return {
    app,
    view: view?.handle ?? null,
    viewLabel: view?.root?.label ?? null,
    snapshot: snapshotView(view, state, hidden),
    stateKeys,
    actions,
    views,
  };
}

/**
 * The view as an indented tree, one line per widget.
 *
 * <p>Reads like this:
 *
 * <pre>
 * page "Invoices"
 *   table #liste ← rows = [3 entries: key, customer, amount]
 *   select #status "Status" ← status = "open" (2 choices)
 *   text #hint "Nothing selected" (hidden)
 *   button #save "Save" → main.js:save [agent]
 *   button #wipe "Delete all" → main.js:wipe [closed]
 * </pre>
 *
 * <p><b>Hidden widgets are listed, marked.</b> A browser snapshot omits them —
 * but here a widget can be hidden *by the program*, possibly by the agent
 * itself a moment ago, and "it is not in the tree" would read as "it does not
 * exist". Knowing that it is there and invisible is the useful answer.
 */
export function snapshotView(
  view: RenderedView | null,
  state: Record<string, unknown>,
  hidden: (node: ViewNode) => boolean,
): string {
  if (!view?.root) return '(no view loaded)';
  const lines: string[] = [];

  const walk = (node: ViewNode, depth: number): void => {
    const parts: string[] = [node.type];
    if (node.id) parts.push('#' + node.id);

    // How this container arranges its children, said out loud.
    //
    // The tree already carries the structure, but only a reader who knows that
    // `row` means horizontal can see it — and an agent that does not will
    // happily report two boxes as being side by side when they are stacked.
    // Nesting plus a word is the whole fix; there is no geometry to report
    // beyond it (see the caveat in the manual: this is structure, not pixels).
    const arranged = arrangement(node);
    if (arranged) parts.push(arranged);

    const caption = node.label ?? node.text;
    if (caption) parts.push(JSON.stringify(shorten(caption, 60)));

    if (node.from) {
      parts.push('← ' + node.from + ' = ' + describeValue(state[node.from]));
    }
    if (node.options && node.options.length > 0) {
      parts.push('(' + node.options.length + ' choices)');
    }
    if (node.columns && node.columns.length > 0) {
      parts.push('columns: ' + node.columns.join(', '));
    }
    if (node.fields && node.fields.length > 0) {
      parts.push('fields: ' + node.fields.map((f) => f.name).join(', '));
    }

    const click = node.on?.click;
    if (click) {
      parts.push('→ ' + (click.raw ?? click.kind));
      // The permission, spelled out on the line that matters. Without it a model
      // has to look the widget up in a second list, which is exactly the
      // correlation this snapshot exists to remove.
      parts.push(node.agent === true ? '[agent]' : '[closed]');
    }
    if (node.show) parts.push('show: ' + node.show);
    if (hidden(node)) parts.push('(hidden by the program)');

    lines.push('  '.repeat(depth) + parts.join(' '));
    for (const child of node.children ?? []) walk(child, depth + 1);
  };

  walk(view.root, 0);
  return lines.join('\n');
}

/**
 * What a container does to its children, or `''` for a leaf.
 *
 * <p>Counts included, because "side by side: 4" is the sentence that tells a
 * reader whether a row has grown past what fits.
 */
function arrangement(node: ViewNode): string {
  const n = (node.children ?? []).length;
  if (n === 0) return '';
  switch (node.type) {
    case 'row':
    case 'toolbar':
      return '(side by side: ' + n + ')';
    case 'column':
    case 'page':
      return '(stacked: ' + n + ')';
    case 'card':
      return '(stacked in a box: ' + n + ')';
    case 'tabs':
      return '(one at a time: ' + n + ')';
    case 'repeat':
      return '(repeated per entry: ' + n + ' per row)';
    case 'dialog':
      return '(opens over the page: ' + n + ')';
    default:
      return '';
  }
}

/** A value as one short phrase — never the value itself when it is large. */
function describeValue(value: unknown): string {
  if (value === undefined) return '(not set)';
  if (value === null) return 'null';
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    const first = value[0];
    const keys = first && typeof first === 'object' && !Array.isArray(first)
      ? ': ' + Object.keys(first as Record<string, unknown>).slice(0, 6).join(', ')
      : '';
    return '[' + value.length + ' entries' + keys + ']';
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value as Record<string, unknown>);
    return keys.length === 0 ? '{}' : '{' + keys.slice(0, 6).join(', ') + '}';
  }
  if (typeof value === 'string') {
    return value.length > 60
      ? JSON.stringify(shorten(value, 60)) + ' (' + value.length + ' chars)'
      : JSON.stringify(value);
  }
  return String(value);
}

function shorten(text: string, max: number): string {
  const flat = String(text).replace(/\s+/g, ' ').trim();
  return flat.length <= max ? flat : flat.slice(0, max - 1) + '…';
}

/** Whether this widget id may be triggered by an agent. */
export function actionAllowed(view: RenderedView | null, id: string): boolean {
  let allowed = false;
  const walk = (node: ViewNode): void => {
    if (node.id === id && node.on && Object.keys(node.on).length > 0) {
      // `=== true` rather than truthy: the field is a real boolean from the
      // parser, and a permissive coercion is the wrong reflex on the one flag
      // that hands out a button.
      allowed = node.agent === true;
    }
    for (const child of node.children ?? []) walk(child);
  };
  if (view?.root) walk(view.root);
  return allowed;
}
