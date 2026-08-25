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
    stateKeys,
    actions,
    views,
  };
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
