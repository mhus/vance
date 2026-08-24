<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { VAlert, VButton, VEmptyState, useAppEntry } from '@vance/components';
import WidgetNode from './WidgetNode.vue';
import { Sandbox, type SandboxHost } from './sandbox';
import { listDocuments, loadView, readDocument, rebuildApp, scanApp } from './api';
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
  documentsList(path) {
    return listDocuments(props.document.projectId, resolve(path)).catch(rethrow(path));
  },
  documentsRead(path) {
    return readDocument(props.document.projectId, resolve(path)).catch(rethrow(path));
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

/** Fetch the program and start it. An app without one renders and sits still. */
async function boot(s: AppScan): Promise<void> {
  await teardown();
  if (!s.programPath) return;
  let source: string;
  try {
    const raw = await readDocument(props.document.projectId, s.programPath);
    source = typeof raw === 'string' ? raw : String(raw);
  } catch (e) {
    notice.value = `Could not read the program '${s.programPath}': ${message(e)}`;
    return;
  }
  const box = new Sandbox({
    host,
    onError: (msg) => {
      notice.value = msg;
    },
  });
  sandbox = box;
  try {
    await box.start(source, s.programPath);
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
        @action="onAction"
      />
    </div>
  </div>
</template>
