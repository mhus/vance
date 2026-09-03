<script setup lang="ts">
import {
  computed,
  inject,
  onBeforeUnmount,
  onMounted,
  provide,
  reactive,
  ref,
  toRaw,
  watch,
  type Ref,
} from 'vue';
import {
  VAlert,
  VButton,
  VEmptyState,
  useAppEntry,
  useDocumentPrefixReaction,
} from '@vance/components';
import WidgetNode from './WidgetNode.vue';
import { Sandbox, type SandboxHost } from './sandbox';
import { PATCHES, applyPatch, patchHides, type PatchMap, type WidgetPatch } from './patches';
import { getTenantId, getUsername } from '@vance/shared';
import {
  DocumentAccess,
  callRest,
  loadScript,
  loadView,
  rebuildApp,
  releaseStatus,
  requestRelease,
  scanApp,
  type ReleaseStatus,
} from './api';
import {
  ActionNotAllowedError,
  actionAllowed,
  describeView,
  type AppAgentApi,
} from './agentApi';
import type { AppScan } from './generated/bistromath/AppScan';
import type { RenderedView } from './generated/bistromath/RenderedView';
import type { ViewAction } from './generated/bistromath/ViewAction';
import { useT } from './i18n';

/**
 * Mount for an `app: custom` folder — the Bistromath runtime.
 *
 * <p>What makes this different from every other app kind in the tree: it has no
 * idea what the app does. A view document is a widget tree, `main.js` is the
 * behaviour, and this file only fetches, renders and relays.
 *
 * <p><b>Order of operations matters.</b> The view is rendered *before* the
 * program runs: `init()` is async, and waiting for it would leave the app blank
 * until the first document read comes back. So the page paints, then `init()`
 * fills the state it reads.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string; title?: string | null };
}>();

const folder = computed(() => {
  const p = props.document.path;
  const i = p.lastIndexOf('/');
  return i < 0 ? '' : p.slice(0, i);
});

const t = useT();

const scan = ref<AppScan | null>(null);
const view = ref<RenderedView | null>(null);
const error = ref<string | null>(null);
const notice = ref<string | null>(null);
const busy = ref(false);

/**
 * The state the widgets read.
 *
 * <p>It lives here, host-side and reactive, not in the guest — the renderer has
 * to react to it, and marshalling on write is once per `set` instead of once
 * per render. The guest's own variables stay in the guest; that is the program's
 * bookkeeping and no widget can see it.
 */
const state = reactive<Record<string, unknown>>({});

/**
 * Runtime changes the program made to the rendered view.
 *
 * <p>Beside the fetched tree, never inside it — so a reload always means "what
 * the document says", and that stays the way out of any confusing runtime state.
 * Provided rather than passed down: every node needs it.
 *
 * <p>Cleared when the view changes, because a patch names a widget of *this*
 * view and the next one has never heard of it. The program is told through
 * `onViewOpened`, which is what makes re-applying possible.
 */
const patches = ref<PatchMap>({});
provide(PATCHES, patches);

/**
 * The container the guest's frame lives in, for as long as the app is open.
 *
 * <p>Rendered unconditionally and never moved. An `<iframe>` reloads when it is
 * re-parented, which would restart the program — so the frame is created *in*
 * this div and stays; whether it is seen, and how tall, is decided by styling
 * the div. That is also why the drawing surface is one per app and sits where
 * the app puts it rather than where a widget would.
 */
const guestSlot = ref<HTMLElement | null>(null);

/**
 * The height the current view asks for: a number of pixels, `fill`, or nothing.
 *
 * <p>A view without `region:` leaves the div at zero height, so an app that
 * only uses widgets is unchanged — the guest is there, just not seen.
 */
const region = computed<string | null>(() => {
  const asked = view.value?.root.region ?? null;
  if (asked === null) return null;
  // The tenant may withhold a drawing surface. Not about raw DOM — the guest
  // has its own document anyway — but about what it can *paint*: pixels that
  // look like Vance. A withheld surface is reported rather than silently
  // dropped, because an author staring at a blank area would otherwise look
  // for the bug in their own code.
  if (scan.value && scan.value.policy.mode !== 'ALLOWED' && !scan.value.policy.surface) {
    return null;
  }
  return asked;
});

/** Whether the open view asked for a surface the tenant withholds. */
const surfaceWithheld = computed(() =>
  Boolean(view.value?.root.region)
  && Boolean(scan.value)
  && scan.value!.policy.mode !== 'ALLOWED'
  && !scan.value!.policy.surface);

let sandbox: Sandbox | null = null;

/**
 * The app's document surface, and its version memory.
 *
 * <p>Per app instance: a write is conditional on the version this app read, so
 * two apps — or two tabs — never share one and never talk each other into
 * believing a document is unchanged.
 */
const docs = new DocumentAccess(props.document.projectId);

/**
 * Where we are inside the app, as the host stores it: `<view>` or
 * `<view>/<rowKey>`. The app owns what a handle means — that is the contract of
 * `useAppEntry` — and a detail view has to say *which* record.
 */
const { entry, report } = useAppEntry(() => props.document.id);

/**
 * The open chat's session, when the surface around this app has one.
 *
 * <p>Injected because only the Cortex knows: an app opened in a chatless tab
 * has none, and the same app in a tab with a chat beside it does. `null` is a
 * normal answer, not a failure.
 */
const cortexSession = inject<Ref<string | null> | null>('vance:session-id', null);

const handle = computed(() => splitEntry(entry.value).handle);
const recordKey = computed(() => splitEntry(entry.value).recordKey);

function splitEntry(raw: string | null): { handle: string | null; recordKey: string | null } {
  if (!raw) return { handle: null, recordKey: null };
  const i = raw.indexOf('/');
  if (i < 0) return { handle: raw, recordKey: null };
  return { handle: raw.slice(0, i), recordKey: raw.slice(i + 1) || null };
}

/**
 * What the program may know about itself and cannot change while it runs.
 *
 * <p>Handed over before the first line of program code, so it reads as plain
 * properties. It answers questions the program could not otherwise ask — its own
 * folder, for a link or a message; the project and tenant, for anything it
 * displays — and it answers none it could act on: every call still goes through
 * `vance.*` and is authorised there with the reader's real session.
 *
 * <p>`user` is information, **not** a permission. A program that hides something
 * from a name is decoration; what a reader may actually see is decided by the
 * permission system on every call the host makes.
 */
const appContext = computed<Record<string, unknown>>(() => ({
  folder: folder.value,
  project: props.document.projectId,
  tenant: getTenantId(),
  user: getUsername(),
  docPath: props.document.path,
  docId: props.document.id ?? null,
}));

/**
 * Refuse a document write the tenant does not permit.
 *
 * <p>Documents are a **separate host surface** from `vance.rest`, so the
 * policy's route list never touched them — a restricted app could still delete
 * its own folder. Reading stays open: "may show, may not change" is the
 * distinction, not "may not see".
 *
 * <p>Thrown, not returned as a null: a write that quietly does nothing is the
 * failure that looks like success, and the program would report a saved record.
 */
function requireWritable(what: string): void {
  const policy = scan.value?.policy;
  if (!policy || policy.mode === 'ALLOWED' || policy.documentsWritable) return;
  throw new Error(
    `This tenant allows the app to read documents but not to ${what} them.`
      + ' A tenant admin decides that in _vance/config/applications.yaml.',
  );
}

// ── the host API the program is allowed to reach ───────────────────

const host: SandboxHost = {
  stateSet(key, value) {
    state[key] = value;
  },
  stateGet(key) {
    // `toRaw` first: a reactive proxy is not structured-cloneable in every
    // engine, and what crosses to the guest has to survive postMessage. The
    // raw object is what the guest — or a form — put here in the first place.
    return toRaw(state)[key];
  },
  documentsList(path) {
    return docs.list(resolve(path)).catch(rethrow(path));
  },
  documentsRead(path) {
    return docs.read(resolve(path)).catch(rethrow(path));
  },
  documentsWrite(path, content, opts) {
    requireWritable('write');
    return docs
      .write(resolve(path), content, (opts ?? {}) as { force?: boolean })
      .catch(rethrow(path));
  },
  documentsCreate(path, content) {
    requireWritable('create');
    return docs.create(resolve(path), content).catch(rethrow(path));
  },
  documentsDelete(path) {
    requireWritable('delete');
    return docs.delete(resolve(path)).catch(rethrow(path));
  },
  restCall(method, path, body) {
    // No `resolve()` here, on purpose: a REST path is not a document path. The
    // app folder is the root of the document grammar, not of the API — folding
    // one into the other would make `documents/folder` mean
    // `apps/mine/documents/folder`, which is not a route.
    // The declaration comes from the scan, so an edited manifest takes effect on
    // the next open rather than needing a rebuild — same as every other
    // manifest key.
    // Policy before declaration is the order inside `vetRestPath`; here both
    // simply travel. `restFamilies` is null unless the tenant restricted this
    // app, so an unrestricted tenant costs nothing.
    return callRest(method, path, body, scan.value?.rest ?? null,
      scan.value?.policy?.restFamilies ?? null);
  },
  appCurrent() {
    // What a constant cannot carry: the reader has moved since the program
    // started, or opened a chat beside it.
    return {
      view: view.value?.handle ?? null,
      session: cortexSession?.value ?? null,
    };
  },
  viewPatch(id, changes) {
    patches.value = applyPatch(patches.value, id, (changes ?? {}) as WidgetPatch);
  },
  viewReset(id) {
    if (id === undefined) {
      patches.value = {};
      return;
    }
    const next = { ...patches.value };
    delete next[id];
    patches.value = next;
  },
  uiNotify(text) {
    notice.value = text;
  },
  uiShow(target) {
    report(target, 'push');
  },
};

/**
 * A path the program named, against the app folder.
 *
 * <p>Relative stays inside the app; a leading `/` is the project root — the
 * same grammar every authored reference in Vancetope uses. So a mounted
 * document is `/_ext/<mount>/…`: without the slash it would resolve inside the
 * app folder, which is why the error names what was tried (see below).
 *
 * <p>Clamped to this project — reading another project's documents needs that
 * project's READ, a second authorisation this build does not do.
 *
 * <p>The query is split off first and re-attached: it belongs to the document's
 * content, not to its path, and normalising slashes across a query value would
 * corrupt it.
 */
function resolve(pathWithQuery: string): string {
  const raw = String(pathWithQuery).trim();
  const cut = raw.indexOf('?');
  const path = cut < 0 ? raw : raw.slice(0, cut);
  const query = cut < 0 ? '' : raw.slice(cut);
  const resolved = path.startsWith('/')
    ? path.replace(/^\/+/, '')
    : `${folder.value}/${path}`.replace(/\/{2,}/g, '/');
  return resolved + query;
}

/**
 * Add the one hint that saves a debugging session.
 *
 * <p>`_ext/…` without a leading slash resolves inside the app folder and comes
 * back as "no document at apps/mine/_ext/…", which reads like the mount is
 * broken rather than like the path is relative. The reserved namespaces are the
 * only place this bites, so the hint is attached there and nowhere else.
 */
function rethrow(original: string) {
  return (e: unknown): never => {
    const msg = e instanceof Error ? e.message : String(e);
    if (/^_(ext|vance)\//.test(original.trim())) {
      throw new Error(
        `${msg} — '${original}' was resolved inside the app folder. ` +
          `A project-root path needs a leading slash: '/${original.trim()}'.`,
      );
    }
    throw new Error(msg);
  };
}

// ── reacting to writes ─────────────────────────────────────────────

/**
 * The app watches its own folder.
 *
 * <p>Two different things can change under it and they deserve two different
 * answers. A document that **is** the app — the manifest, a view, the program —
 * means the app itself was edited, so it reloads: that is what the Rebuild
 * button does, and having to press it after every edit was the friction this
 * removes. Anything else is **data**, and only the program knows what to do
 * about it, so it gets `onDocumentChanged(paths)` and decides.
 *
 * <p>Self-writes do not come back: the REST client carries the connection's
 * `editorId` and the server skips the writer. Without that, a program that
 * saves a record would be told about its own save and could answer by saving
 * again.
 */
const watchedPrefix = computed(() => (folder.value ? `${folder.value}/` : null));

useDocumentPrefixReaction({
  prefix: watchedPrefix,
  onRemoteChange: (paths) => {
    if (paths.some(isDefinition)) {
      // The app was edited. Re-scan, re-open, restart — a program left running
      // against a view that no longer matches it is the confusing state.
      void load();
      return;
    }
    void sandbox?.invokeHook('onAppDocumentChanged', [paths]);
  },
});

/**
 * Is this path part of the app's definition rather than its data?
 *
 * <p>Answered from the scan, not from a folder convention — §4.1 refused
 * prescribed folders, and guessing from a path would resurrect them. The one
 * case this misses is a **newly added** view: it is in no scan yet, so it
 * counts as data. Rebuild still covers that, and it is a rarer act than saving
 * a record.
 */
function isDefinition(path: string): boolean {
  const s = scan.value;
  if (path === props.document.path) return true;
  if (!s) return false;
  if (s.programPath && path === s.programPath) return true;
  return s.views.some((v) => v.path === path);
}

// ── lifecycle ──────────────────────────────────────────────────────

/**
 * A reset the host renders outside this component.
 *
 * <p>Outside on purpose: a program can hide its own toolbar with a patch, or
 * take the page with a drawing surface. The one way out must not be something
 * the app can break — so the Cortex header holds the button and this component
 * only says what it does.
 *
 * <p>**Reset is not Rebuild.** Rebuild re-reads the documents and re-validates
 * them, which is the authoring loop and needs write access. Reset throws away
 * what the *program* did — state, patches, the running guest — and starts it
 * again from the documents as they are. A reader with no write access can still
 * get out.
 */
const registerReset = inject<((fn: null | (() => void)) => void) | null>(
  'vance:register-reset',
  null,
);

/**
 * Lets the chat beside this app read and drive it — see `agentApi.ts` for what
 * is allowed and why.
 *
 * <p>Injected like the reset button: the surrounding page owns the WS and the
 * tool registration, and a federated addon cannot import from it. Handing over
 * an object is the seam.
 */
const registerAgent = inject<((api: AppAgentApi | null) => void) | null>(
  'vance:register-app-agent',
  null,
);

/**
 * What the agent may do. Deliberately built from the same pieces the reader
 * uses: `state` is the same object the widgets read, and an action goes through
 * the same `onAction` a click goes through — including its error notice.
 */
const agentApi: AppAgentApi = {
  describe() {
    return describeView(
      folder.value,
      view.value,
      (scan.value?.views ?? []).map((v) => v.handle),
      toRaw(state),
      (node) => patchHides(node, patches.value),
    );
  },
  stateGet(key) {
    const raw = toRaw(state);
    if (key === undefined || key === null || key === '') return { ...raw };
    return raw[key];
  },
  stateSet(key, value) {
    // No handler is run. That is what makes an agent's write free of a
    // declaration: it changes what is on screen and nothing else, so a person
    // still presses the button. If it fired `on: change`, a half-filled form
    // would run the app's logic three times and the write would need the same
    // gate an action needs.
    state[key] = value;
  },
  async reload() {
    // The same `load()` the ⟳ button runs — one way back, not two.
    await load();
  },
  async action(id, args) {
    if (!actionAllowed(view.value, id)) throw new ActionNotAllowedError(id);
    const node = findNode(view.value?.root ?? null, id);
    const click = node?.on?.click;
    if (!click) {
      throw new Error(`'${id}' has no click handler, so there is nothing to press. `
        + 'Set its state key instead.');
    }
    notice.value = null;
    await onAction(click, args && args.length ? String(args[0]) : undefined);
    if (notice.value) throw new Error(notice.value);
  },
};

/** The node with this id, or null. */
function findNode(node: RenderedView['root'] | null, id: string): RenderedView['root'] | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const child of node.children ?? []) {
    const hit = findNode(child, id);
    if (hit) return hit;
  }
  return null;
}

onMounted(() => {
  registerReset?.(() => {
    void load();
  });
  registerAgent?.(agentApi);
  void load();
});

onBeforeUnmount(() => {
  registerReset?.(null);
  registerAgent?.(null);
  void teardown();
});

// A link click while the tab is already open changes `entry` without
// remounting, so the view has to follow it. The program keeps running — a view
// switch is navigation inside one app, not a restart.
watch(handle, (next, previous) => {
  if (next === previous) return;
  if (!scan.value || scan.value.views.length === 0) return;
  void openView(next);
});

/** The tenant's answer about this app, when opening it was refused. */
const release = ref<ReleaseStatus | null>(null);
const releaseBusy = ref(false);
const releaseSent = ref<string | null>(null);

function statusOf(e: unknown): number | null {
  return e && typeof (e as { status?: unknown }).status === 'number'
    ? (e as { status: number }).status : null;
}

async function loadReleaseStatus(): Promise<void> {
  try {
    release.value = await releaseStatus(props.document.projectId, folder.value);
  } catch {
    // Best effort: without it the reader still sees why the app did not open,
    // just without the offer. A second error on top of the first would only
    // bury the first.
    release.value = null;
  }
}

async function askForRelease(): Promise<void> {
  releaseBusy.value = true;
  try {
    const receipt = await requestRelease(props.document.projectId, folder.value);
    releaseSent.value = receipt.message;
    await loadReleaseStatus();
  } catch (e) {
    releaseSent.value = message(e);
  } finally {
    releaseBusy.value = false;
  }
}

async function load(): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    const s = await scanApp(props.document.projectId, folder.value);
    scan.value = s;
    // An app with no view is a normal early state, not a failure: asking the
    // server for "the landing view" would answer with an error, and a red alert
    // on first open reads as broken rather than empty.
    if (s.views.length === 0) {
      view.value = null;
      return;
    }
    const wanted =
      handle.value && s.views.some((v) => v.handle === handle.value)
        ? handle.value
        : s.landingHandle;
    await openView(wanted ?? null);
    await boot(s);
  } catch (e) {
    error.value = message(e);
    // A policy refusal is not an error the reader can fix by trying again, so
    // it gets a different shape: what the tenant decided, and — if the tenant
    // set up a path — a way to ask. The status comes from its own route rather
    // than from reading this message.
    if (statusOf(e) === 403) await loadReleaseStatus();
  } finally {
    busy.value = false;
  }
}

async function openView(target: string | null): Promise<void> {
  error.value = null;
  // A patch names a widget of the view being left; the next view has never
  // heard of it. Cleared here rather than merged, so a stale patch cannot
  // silently apply to a same-named widget somewhere else.
  patches.value = {};
  try {
    view.value = await loadView(props.document.projectId, folder.value, target);
  } catch (e) {
    view.value = null;
    error.value = message(e);
    return;
  }
  // Told after the view is up, so a patch in the hook lands on something that
  // exists. Silent when the program has no such hook.
  void sandbox?.invokeHook('onAppViewOpened', [view.value.handle]);
}

/**
 * Fetch everything the app loads and start it. An app without a program renders
 * and sits still.
 *
 * <p>The guest has one global scope and no module system, so "loading" means
 * evaluating the list the server resolved, in order: libraries first, then the
 * app's own scripts, then the program. One eval per file, so a syntax error
 * names the document it is in.
 */
async function boot(s: AppScan): Promise<void> {
  await teardown();
  const list = s.requires.scripts;
  if (list.length === 0) return;

  const parts: { path: string; source: string }[] = [];
  for (const entry of list) {
    try {
      parts.push({
        path: entry.path,
        source: await loadScript(props.document.projectId, entry.path),
      });
    } catch (e) {
      // Named, and then abandoned: a program missing a library it uses fails at
      // the first call with a message nobody can trace, so it is better not to
      // start than to start half-loaded.
      notice.value = `Could not read '${entry.path}': ${message(e)}`;
      return;
    }
  }

  const box = new Sandbox({
    host,
    onError: (msg) => {
      notice.value = msg;
    },
    // Handed over once. The frame is created here and never leaves, so a view
    // that asks for a surface later needs no second frame — the div grows.
    mount: guestSlot.value,
    context: appContext.value,
  });
  sandbox = box;
  try {
    await box.startAll(parts);
  } catch (e) {
    notice.value = `The program failed to start: ${message(e)}`;
    return;
  }
  startRefresh(s.refreshSeconds ?? null);
}

// ── periodic refresh ───────────────────────────────────────────────

let refreshTimer: ReturnType<typeof setInterval> | null = null;
let refreshInFlight = false;

/**
 * Call `onAppRefresh()` every `refresh:` seconds, while it makes sense to.
 *
 * <p>Two things it deliberately does not do.
 *
 * <p><b>It does not fire in a hidden tab.</b> A background tab polling a server
 * forever is pure waste, and nobody is reading the result — the point of a
 * refresh is that somebody is looking. `document.hidden` is checked at each
 * tick rather than by listening for visibility changes, because the answer only
 * matters at the moment the tick would fire.
 *
 * <p><b>It does not queue.</b> A call still running when the next tick arrives
 * skips that tick: an interval shorter than the work would otherwise build a
 * backlog that never drains, and the honest behaviour there is a slower
 * effective rate, not a growing queue.
 *
 * <p>Started after the program is up — so it can never overtake
 * `onAppInit()` — and only when the program actually defines the hook, so a
 * manifest with `refresh:` and a program without the function is silent rather
 * than a repeating error.
 */
function startRefresh(seconds: number | null): void {
  stopRefresh();
  if (!seconds || seconds <= 0) return;
  if (!sandbox?.has('onAppRefresh')) return;
  refreshTimer = setInterval(() => {
    if (refreshInFlight) return;
    if (typeof document !== 'undefined' && document.hidden) return;
    const box = sandbox;
    if (!box?.alive) return;
    refreshInFlight = true;
    void box.invokeHook('onAppRefresh').finally(() => {
      refreshInFlight = false;
    });
  }, seconds * 1000);
}

function stopRefresh(): void {
  if (refreshTimer !== null) clearInterval(refreshTimer);
  refreshTimer = null;
  refreshInFlight = false;
}

async function teardown(): Promise<void> {
  stopRefresh();
  const box = sandbox;
  sandbox = null;
  if (box) await box.dispose();
  for (const key of Object.keys(state)) delete state[key];
}

// ── events ─────────────────────────────────────────────────────────

function onNavigate(target: string, key?: string): void {
  report(key ? `${target}/${key}` : target, 'push');
}

/**
 * Dispatch a widget's action.
 *
 * <p>A `rowClick` that navigates carries the clicked record's key. Convention
 * rather than a template in the document: a rowClick already knows its row, and
 * `navigate:edit/{key}` would be an expression language nobody asked for.
 */
async function onAction(action: ViewAction, key?: string): Promise<void> {
  notice.value = null;
  switch (action.kind) {
    case 'NAVIGATE':
      if (action.target) onNavigate(action.target, key);
      break;
    case 'RELOAD':
      await load();
      break;
    case 'SCRIPT':
      if (!sandbox?.alive) {
        notice.value = 'This app has no running program, so the control does nothing.';
        return;
      }
      if (!action.function) return;
      try {
        await sandbox.invoke(action.function);
      } catch (e) {
        notice.value = `${action.raw}: ${message(e)}`;
      }
      break;
  }
}

/**
 * A form edit. The only path by which anything other than the program writes
 * state — and it writes the same keys the program does, on purpose: the
 * program reads back what the reader typed with `vance.state.get(key)`, so
 * there is one place a value lives, not a form model beside it.
 */
function onStateEdit(key: string, value: unknown): void {
  state[key] = value;
}

async function onRebuild(): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    const s = await rebuildApp(props.document.projectId, folder.value);
    scan.value = s;
    await openView(handle.value ?? s.landingHandle ?? null);
    // A rebuild restarts the program: somebody just edited something, and a
    // stale program that ignores the edit is the bug one hunts longest.
    await boot(s);
  } catch (e) {
    error.value = message(e);
    // A policy refusal is not an error the reader can fix by trying again, so
    // it gets a different shape: what the tenant decided, and — if the tenant
    // set up a path — a way to ask. The status comes from its own route rather
    // than from reading this message.
    if (statusOf(e) === 403) await loadReleaseStatus();
  } finally {
    busy.value = false;
  }
}

const problems = computed<string[]>(() => scan.value?.problems ?? []);

/**
 * The load-order panel — the "what actually loads" answer.
 *
 * <p>It exists because the list is assembled from three places (the manifest,
 * each view, every script header) and written down in none of them. Warnings
 * and misses already reach the problems strip; what only this panel can show is
 * the **order**, and where each file came from — a library resolving to
 * `bundled` is the addon's copy, and knowing that is the difference between
 * "my override works" and "my override is at the wrong path".
 */
const showLoads = ref(false);

function message(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) return String((e as Error).message);
  return String(e);
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-3 p-3">
    <div class="flex flex-wrap items-center gap-2">
      <span class="font-semibold">{{ scan?.title ?? document.title ?? t('bistromath.app.fallbackTitle') }}</span>

      <template v-if="scan && scan.views.length > 1">
        <VButton
          v-for="v in scan.views"
          :key="v.handle"
          size="sm"
          :variant="view?.handle === v.handle ? 'primary' : 'ghost'"
          @click="onNavigate(v.handle)"
        >
          {{ v.title ?? v.handle }}
        </VButton>
      </template>

      <span class="flex-1" />

      <VButton
        v-if="scan"
        variant="ghost"
        size="sm"
        :title="t('bistromath.app.loadsTip', { count: scan.requires.scripts.length })"
        @click="showLoads = !showLoads"
      >
        {{ t('bistromath.app.loads', { count: scan.requires.scripts.length }) }}
      </VButton>

      <VButton
        variant="ghost"
        size="sm"
        :disabled="busy"
        :title="t('bistromath.app.rebuildTip')"
        @click="onRebuild()"
      >
        {{ t('bistromath.app.rebuild') }}
      </VButton>
    </div>

    <VAlert v-if="error" variant="error" class="whitespace-pre-line">{{ error }}</VAlert>

    <!-- A refusal by policy, with the way out when the tenant offers one. Not
         merged into the error above: "this did not work" and "here is who
         decides" are different sentences, and the second one is actionable. -->
    <VAlert v-if="release && release.mode !== 'ALLOWED'" variant="info">
      <div class="flex flex-col gap-2">
        <div>{{ release.reason ?? t('bistromath.app.releaseDefault') }}</div>
        <div v-if="releaseSent" class="opacity-80">{{ releaseSent }}</div>
        <div v-if="release.canRequest && !releaseSent">
          <VButton size="sm" :disabled="releaseBusy" @click="askForRelease">
            {{ releaseBusy ? t('bistromath.app.sending') : t('bistromath.app.requestRelease') }}
          </VButton>
        </div>
      </div>
    </VAlert>

    <!-- Order matters and is invisible in the documents, so it is numbered. -->
    <div
      v-if="showLoads && scan"
      class="rounded border border-base-300 p-3 text-sm"
    >
      <div class="mb-2 font-semibold">{{ t('bistromath.app.loadOrder') }}</div>
      <ol v-if="scan.requires.scripts.length > 0" class="flex list-decimal flex-col gap-1 pl-6">
        <li v-for="entry in scan.requires.scripts" :key="entry.path">
          <code class="font-mono">{{ entry.path }}</code>
          <span v-if="entry.name" class="opacity-70">
            — {{ entry.name }}@{{ entry.version }} ({{ entry.origin }})</span>
          <span v-else class="opacity-70"> — {{ entry.kind }}</span>
          <span v-if="entry.askedBy" class="opacity-50"> ← {{ entry.askedBy }}</span>
        </li>
      </ol>
      <p v-else class="opacity-70">{{ t('bistromath.app.noProgram') }}</p>
      <p v-if="scan.requires.missing.length > 0" class="mt-2 text-error whitespace-pre-line">{{
        scan.requires.missing.join('\n')
      }}</p>
    </div>
    <VAlert v-if="notice" variant="warning" class="whitespace-pre-line">{{ notice }}</VAlert>

    <!-- Said rather than silently dropped: an author staring at a missing area
         would otherwise look for the bug in their own view. -->
    <VAlert v-if="surfaceWithheld" variant="info">
      {{ t('bistromath.app.surfaceWithheldPre') }}<code>region:</code>{{
        t('bistromath.app.surfaceWithheldMid') }}
      <code>_vance/config/applications.yaml</code>.
    </VAlert>

    <!-- What the scan had to refuse: an unusable file name, a colliding handle,
         a `landing` that names nothing. Beside the page, never instead of it. -->
    <VAlert v-if="problems.length > 0" variant="info" class="whitespace-pre-line">{{
      problems.join('\n')
    }}</VAlert>
    <VAlert
      v-if="view && view.notes.length > 0"
      variant="info"
      class="whitespace-pre-line"
      >{{ view.notes.join('\n') }}</VAlert
    >

    <div class="min-h-0 flex-1 overflow-y-auto">
      <VEmptyState
        v-if="!view && !error && !busy"
        :headline="t('bistromath.app.noViewHeadline')"
        :body="t('bistromath.app.noViewBody', { folder })"
      />
      <WidgetNode
        v-else-if="view"
        :node="view.root"
        :state="state"
        :record-key="recordKey"
        :resolve="resolve"
        @action="onAction"
        @state="onStateEdit"
      />
    </div>

    <!-- The program's own document, made visible. Always in the tree so the
         frame inside it is never re-parented; sized to nothing when the view
         asks for no surface. -->
    <div
      ref="guestSlot"
      :class="region === 'fill' ? 'min-h-0 flex-1' : ''"
      :style="region && region !== 'fill' ? { height: `${region}px` } : { height: '0' }"
    />
  </div>
</template>
