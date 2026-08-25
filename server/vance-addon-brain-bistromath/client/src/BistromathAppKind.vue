<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, toRaw, watch } from 'vue';
import {
  VAlert,
  VButton,
  VEmptyState,
  useAppEntry,
  useDocumentPrefixReaction,
} from '@vance/components';
import WidgetNode from './WidgetNode.vue';
import { Sandbox, type SandboxHost } from './sandbox';
import { DocumentAccess, loadScript, loadView, rebuildApp, scanApp } from './api';
import type { AppScan } from './generated/bistromath/AppScan';
import type { RenderedView } from './generated/bistromath/RenderedView';
import type { ViewAction } from './generated/bistromath/ViewAction';

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

const handle = computed(() => splitEntry(entry.value).handle);
const recordKey = computed(() => splitEntry(entry.value).recordKey);

function splitEntry(raw: string | null): { handle: string | null; recordKey: string | null } {
  if (!raw) return { handle: null, recordKey: null };
  const i = raw.indexOf('/');
  if (i < 0) return { handle: raw, recordKey: null };
  return { handle: raw.slice(0, i), recordKey: raw.slice(i + 1) || null };
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
    return docs
      .write(resolve(path), content, (opts ?? {}) as { force?: boolean })
      .catch(rethrow(path));
  },
  documentsCreate(path, content) {
    return docs.create(resolve(path), content).catch(rethrow(path));
  },
  documentsDelete(path) {
    return docs.delete(resolve(path)).catch(rethrow(path));
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
    void sandbox?.invokeHook('onDocumentChanged', [paths]);
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

onMounted(load);

onBeforeUnmount(() => {
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
  } finally {
    busy.value = false;
  }
}

async function openView(target: string | null): Promise<void> {
  error.value = null;
  try {
    view.value = await loadView(props.document.projectId, folder.value, target);
  } catch (e) {
    view.value = null;
    error.value = message(e);
  }
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
  });
  sandbox = box;
  try {
    await box.startAll(parts);
  } catch (e) {
    notice.value = `The program failed to start: ${message(e)}`;
  }
}

async function teardown(): Promise<void> {
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
      <span class="font-semibold">{{ scan?.title ?? document.title ?? 'App' }}</span>

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
        :title="`${scan.requires.scripts.length} document(s) load for this app`"
        @click="showLoads = !showLoads"
      >
        Loads ({{ scan.requires.scripts.length }})
      </VButton>

      <VButton
        variant="ghost"
        size="sm"
        :disabled="busy"
        title="Re-read the views, restart the program"
        @click="onRebuild()"
      >
        Rebuild
      </VButton>
    </div>

    <VAlert v-if="error" variant="error" class="whitespace-pre-line">{{ error }}</VAlert>

    <!-- Order matters and is invisible in the documents, so it is numbered. -->
    <div
      v-if="showLoads && scan"
      class="rounded border border-base-300 p-3 text-sm"
    >
      <div class="mb-2 font-semibold">Loads, in this order</div>
      <ol v-if="scan.requires.scripts.length > 0" class="flex list-decimal flex-col gap-1 pl-6">
        <li v-for="entry in scan.requires.scripts" :key="entry.path">
          <code class="font-mono">{{ entry.path }}</code>
          <span v-if="entry.name" class="opacity-70">
            — {{ entry.name }}@{{ entry.version }} ({{ entry.origin }})</span>
          <span v-else class="opacity-70"> — {{ entry.kind }}</span>
          <span v-if="entry.askedBy" class="opacity-50"> ← {{ entry.askedBy }}</span>
        </li>
      </ol>
      <p v-else class="opacity-70">Nothing — this app has no program.</p>
      <p v-if="scan.requires.missing.length > 0" class="mt-2 text-error whitespace-pre-line">{{
        scan.requires.missing.join('\n')
      }}</p>
    </div>
    <VAlert v-if="notice" variant="warning" class="whitespace-pre-line">{{ notice }}</VAlert>

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
        headline="No view yet"
        :body="`This app is defined by its documents. A view is a document with \`$meta.kind: app-view\` — ask the chat beside this panel for one, or add it under ${folder}.`"
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
  </div>
</template>
