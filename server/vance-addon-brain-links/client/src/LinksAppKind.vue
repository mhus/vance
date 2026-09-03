<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  VAlert, VBadge, VButton, VCard, VEmptyState, VInput, VShareButton, vanceRef,
} from '@vance/components';
import { safeUrl } from '@vance/shared';
import LinkPicture from './LinkPicture.vue';
import LinkEditDialog from './LinkEditDialog.vue';
import CaptureTokenDialog from './CaptureTokenDialog.vue';
import {
  addLink,
  rebuildLinks,
  removeLink,
  renameGroup,
  reorderLinks,
  scanLinks,
  setGroups,
  setLinkViewed,
  updateLink,
  type LinkFields,
} from './api';
import { forgetPreview, previewFor, requestPreview, teaserIsOwn, teaserOf } from './linkPreview';
import type { LinksView } from './generated/links/LinksView';
import type { LinkEntryView } from './generated/links/LinkEntryView';
import { useT } from './i18n';

/**
 * Mount for an `app: links` folder — one column of preview cards under group
 * headings, in the shape of a result list.
 *
 * One column rather than a grid, and the same reason the search app gives:
 * these are things to read, and a title with a teaser next to a thumbnail is
 * read; a wall of thumbnails is only looked at. The filter and the group
 * chips narrow what is shown; they never search anything remote, so typing
 * costs nothing.
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

const view = ref<LinksView | null>(null);
const entries = computed<LinkEntryView[]>(() => view.value?.entries ?? []);
const groups = computed<string[]>(() => view.value?.groups ?? []);

const error = ref<string | null>(null);
const busy = ref(false);
const filter = ref('');
const activeGroup = ref<string | null>(null);   // null = every group
const draft = ref('');
const draftGroup = ref('');
const editing = ref<LinkEntryView | null>(null);
const settingsOpen = ref(false);
const openMenu = ref<string | null>(null);
const dragged = ref<string | null>(null);
const selectedUrl = ref<string | null>(null);

type Section = { group: string; items: LinkEntryView[] };

/**
 * Two ways of looking at the same manifest.
 *
 * **List** is the curated shape: groups in their declared order, entries in the
 * order somebody dragged them into. **Stack** is the reading pile: flat, by
 * date, seen ones out of the way.
 *
 * Two modes rather than a sort dropdown on the grouped list, because the pile
 * has to cross the groups — a "newest first" that only reorders inside each
 * heading answers a question nobody asked, and the group order itself would
 * still be manual and therefore meaningless as a reading order.
 */
type Mode = 'list' | 'stack';
const mode = ref<Mode>('list');
const stackMode = computed(() => mode.value === 'stack');

/** Oldest-first clears a backlog, newest-first reads what just came in. */
const stackOrder = ref<'newest' | 'oldest'>('newest');
const showSeen = ref(false);

/**
 * URLs marked in this session, kept visible until the view is left or the
 * filter changes.
 *
 * Without it the pile has no undo: the click that marks an entry seen also
 * removes it from the only place the tick could be clicked again, so a slip
 * costs a trip through "Show seen". Holding them for the rest of the visit is
 * five lines and makes the ✓ safe to use quickly, which is the whole point of
 * the mode.
 */
const justMarked = ref<Set<string>>(new Set());

watch([filter, activeGroup, mode, showSeen], () => justMarked.value = new Set());

function viewed(entry: LinkEntryView): boolean {
  return !!entry.viewedAt;
}

/**
 * What the reader has picked out, for the chat beside the app.
 *
 * Only the **URL** travels. A hit in the search app has to carry its title
 * along because a search keeps nothing on our side — a link does: it is a row
 * in this manifest. Sending the handle and letting the server read the row off
 * the manifest keeps one authority for what the entry says, instead of a
 * second copy that is right until somebody edits the first.
 */
const reportAppSelection = inject<
  ((sel: {
    appDocId: string;
    selection: string;
    ref?: { label: string; vanceUri?: string; url?: string } | null;
  } | null) => void) | null
>('vance:report-app-selection', null);

const selectedEntry = computed<LinkEntryView | null>(
  () => entries.value.find((e) => e.url === selectedUrl.value) ?? null,
);

watch(selectedEntry, (entry) => {
  if (!reportAppSelection) return;
  const appId = props.document.id;
  if (!appId || !entry) {
    reportAppSelection(null);
    return;
  }
  reportAppSelection({
    appDocId: appId,
    selection: entry.url,
    // The durable half. Here the label is not a second copy of anything the
    // server would otherwise read — it is a snapshot for the day this row is
    // gone from the manifest, which is exactly when the reference has to
    // still say what it meant.
    ref: {
      label: entry.title?.trim() || entry.url,
      vanceUri: vanceRef({ path: `${folder.value}/_app.yaml`, entry: entry.url }),
      url: entry.url,
    },
  });
}, { immediate: true });

// Leaving the tab must retract the selection — a stale one would answer
// "this link" with a link nobody is looking at any more.
onBeforeUnmount(() => {
  reportAppSelection?.(null);
  clearTimeout(copiedTimer);
});

function toggleSelect(entry: LinkEntryView): void {
  selectedUrl.value = selectedUrl.value === entry.url ? null : entry.url;
  openMenu.value = null;
}

/**
 * What sharing an entry hands over: its own title, its URL, and whatever text
 * describes it — the typed teaser if there is one, else the reader's note.
 * Often there is neither, and title plus link is a complete subject. The live
 * preview text is deliberately not pulled in: it belongs to the page, and the
 * receiving side resolves it the same way.
 */
function shareSubject(entry: LinkEntryView) {
  return {
    title: entry.title ?? entry.host,
    link: entry.url,
    snippet: entry.teaser ?? entry.note ?? undefined,
  };
}

function isSelected(entry: LinkEntryView): boolean {
  return selectedUrl.value === entry.url;
}

const filtered = computed<LinkEntryView[]>(() => {
  const q = filter.value.trim().toLowerCase();
  return entries.value.filter((e) => {
    if (activeGroup.value !== null && (e.group ?? '') !== activeGroup.value) return false;
    if (!q) return true;
    return (
      (e.title ?? '').toLowerCase().includes(q) ||
      e.url.toLowerCase().includes(q) ||
      e.host.toLowerCase().includes(q) ||
      (e.teaser ?? '').toLowerCase().includes(q) ||
      (e.note ?? '').toLowerCase().includes(q) ||
      (e.group ?? '').toLowerCase().includes(q) ||
      (e.tags ?? []).some((t) => t.toLowerCase().includes(q))
    );
  });
});

/**
 * The ungrouped entries lead, then the groups in the order the manifest
 * declares. Groups are shown even when the filter emptied them only if they
 * still have entries — an empty heading during a search reads as a broken
 * filter, while an empty heading with no filter is a group waiting to be
 * filled and must stay visible as a drop target.
 */
const sections = computed<Section[]>(() => {
  if (stackMode.value) return [{ group: '', items: stackItems.value }];

  const searching = filter.value.trim().length > 0;
  const out: Section[] = [];
  const lead = filtered.value.filter((e) => !e.group);
  if (lead.length > 0) out.push({ group: '', items: lead });
  for (const g of groups.value) {
    if (activeGroup.value !== null && activeGroup.value !== g) continue;
    const items = filtered.value.filter((e) => e.group === g);
    if (items.length === 0 && searching) continue;
    out.push({ group: g, items });
  }
  return out;
});

/** How many are still waiting — the number the pile is actually about. */
const unseenCount = computed(() => entries.value.filter((e) => !viewed(e)).length);

/**
 * An empty pile means two different things and they must not read alike:
 * a filter that matched nothing is a dead end, an empty pile is finishing.
 */
const stackEmpty = computed(() => {
  if (showSeen.value || filter.value.trim()) {
    return {
      headline: t('links.app.nothingMatchesHeadline'),
      body: t('links.app.nothingMatchesBody'),
    };
  }
  return {
    headline: t('links.app.pileDoneHeadline'),
    body: t('links.app.pileDoneBody'),
  };
});

/**
 * The pile: unseen first, each half by date.
 *
 * Unseen-first rather than one date-sorted run, because with "Show seen" on the
 * two kinds interleaved would bury the three links left to read among fifty
 * that are done. Within a half the date decides, in the direction the reader
 * picked.
 *
 * An entry with no `addedAt` — a row somebody wrote into the YAML by hand —
 * sinks to the bottom of its half in manifest order. Guessing a date for it
 * would put it somewhere it does not belong and look deliberate.
 */
const stackItems = computed<LinkEntryView[]>(() => {
  const pool = filtered.value.filter(
    (e) => !viewed(e) || showSeen.value || justMarked.value.has(e.url),
  );
  const sign = stackOrder.value === 'newest' ? -1 : 1;

  const rank = (e: LinkEntryView) => (viewed(e) && !justMarked.value.has(e.url) ? 1 : 0);
  const withDate = (e: LinkEntryView) => (e.addedAt ? 0 : 1);

  return [...pool].sort((a, b) => {
    if (rank(a) !== rank(b)) return rank(a) - rank(b);
    if (withDate(a) !== withDate(b)) return withDate(a) - withDate(b);
    if (!a.addedAt || !b.addedAt) return 0;   // both undated: keep manifest order
    return sign * a.addedAt.localeCompare(b.addedAt);
  });
});

const counts = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {};
  for (const e of entries.value) {
    const key = e.group ?? '';
    map[key] = (map[key] ?? 0) + 1;
  }
  return map;
});

onMounted(load);

async function load(): Promise<void> {
  error.value = null;
  try {
    apply(await scanLinks(props.document.projectId, folder.value));
  } catch (e) {
    error.value = message(e);
  }
}

/**
 * Every server answer lands here. Besides storing the view it drops a
 * selection whose entry is gone — a removed link must not keep answering
 * "this link" in the chat beside the app.
 */
function apply(v: LinksView): void {
  view.value = v;
  if (selectedUrl.value && !v.entries.some((e) => e.url === selectedUrl.value)) {
    selectedUrl.value = null;
  }
}

async function run(fn: () => Promise<LinksView>): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    apply(await fn());
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
    openMenu.value = null;
  }
}

/**
 * Add what is in the paste field. Several lines are several links — pasting a
 * block of URLs is how a collection actually starts, and doing them one at a
 * time through the same endpoint keeps the server contract at one link per
 * call.
 */
async function addFromDraft(): Promise<void> {
  const urls = draft.value
    .split(/[\s]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  if (urls.length === 0) return;

  const group = draftGroup.value.trim() || null;
  busy.value = true;
  error.value = null;
  const failed: string[] = [];
  try {
    for (const url of urls) {
      try {
        apply(await addLink(props.document.projectId, folder.value, url, { group }));
      } catch (e) {
        // One bad line out of twenty must not discard the other nineteen.
        failed.push(`${url}: ${message(e)}`);
      }
    }
    draft.value = failed.length === urls.length ? draft.value : '';
    if (failed.length > 0) error.value = failed.join('\n');
  } finally {
    busy.value = false;
  }
}

async function onRemove(entry: LinkEntryView): Promise<void> {
  const label = entry.title ?? entry.host;
  if (!window.confirm(t('links.app.confirmRemove', { label }))) return;
  await run(() => removeLink(props.document.projectId, folder.value, entry.url));
}

async function onSaveEdit(fields: LinkFields): Promise<void> {
  const entry = editing.value;
  if (!entry) return;
  await run(() => updateLink(props.document.projectId, folder.value, entry.url, fields));
  editing.value = null;
}

/**
 * Mark seen, or put back on the pile.
 *
 * The wire value is stated rather than toggled, so a retried request lands in
 * the same state; the toggling happens here, where the current one is known.
 * The URL is remembered so the card does not vanish from under the click that
 * marked it — see {@link justMarked}.
 */
async function onToggleViewed(entry: LinkEntryView): Promise<void> {
  const next = !viewed(entry);
  if (next) justMarked.value = new Set(justMarked.value).add(entry.url);
  await run(() => setLinkViewed(props.document.projectId, folder.value, entry.url, next));
}

/**
 * Copy the entry's URL.
 *
 * The one errand a link list is constantly asked for that has nothing to do
 * with this app: the address, in the clipboard, to paste somewhere else. It
 * gets the always-reachable icon slot and sharing moves into the ⋯ menu — a
 * copy is one click and over, while sharing opens a dialog and asks questions,
 * which is what the rest of that menu does too.
 *
 * The confirmation is held per URL rather than as a flag, so two cards cannot
 * both claim to be the one that was copied. It shows as the button turning
 * primary and not as a ✓, because the tick right above it already means
 * something else on this card.
 */
const copiedUrl = ref<string | null>(null);
let copiedTimer: ReturnType<typeof setTimeout> | undefined;

async function onCopy(entry: LinkEntryView): Promise<void> {
  if (typeof navigator === 'undefined' || !navigator.clipboard) {
    // Says why instead of doing nothing: the clipboard is unavailable on
    // insecure origins, and a button that silently fails reads as a bug.
    error.value = t('links.app.clipboardUnavailable');
    return;
  }
  try {
    await navigator.clipboard.writeText(entry.url);
    error.value = null;
    copiedUrl.value = entry.url;
    clearTimeout(copiedTimer);
    copiedTimer = setTimeout(() => { copiedUrl.value = null; }, 1500);
  } catch (e) {
    error.value = message(e);
  }
}

/** Ask the page again — the server-side preview cache holds a week. */
function onRefreshPreview(entry: LinkEntryView): void {
  forgetPreview(entry.url);
  requestPreview(entry.url);
  openMenu.value = null;
}

async function onNewGroup(): Promise<void> {
  const name = window.prompt(t('links.app.promptNewGroup'));
  if (name === null || !name.trim()) return;
  const next = [...groups.value, name.trim()];
  await run(() => setGroups(props.document.projectId, folder.value, next));
}

async function onRenameGroup(group: string): Promise<void> {
  const next = window.prompt(t('links.app.promptRenameGroup', { group }), group);
  if (next === null) return;
  await run(() => renameGroup(props.document.projectId, folder.value, group, next.trim()));
  if (activeGroup.value === group) activeGroup.value = next.trim() || null;
}

async function onRebuild(): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    await rebuildLinks(props.document.projectId, folder.value);
    await load();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

// ── drag & drop: reorder within a group, move between groups ────────
//
// List mode only. In the pile the order is the date, so a drop there would
// either be ignored — a control that does nothing — or write an order the view
// does not show. Both handlers refuse rather than the template hiding them,
// because a drag can also arrive from outside this list.

function onDragStart(entry: LinkEntryView): void {
  dragged.value = entry.url;
}

async function onDropOnEntry(target: LinkEntryView): Promise<void> {
  const url = dragged.value;
  dragged.value = null;
  if (stackMode.value || !url || url === target.url) return;
  await moveTo(url, target.url, target.group ?? '');
}

async function onDropOnSection(group: string): Promise<void> {
  const url = dragged.value;
  dragged.value = null;
  if (stackMode.value || !url) return;
  await moveTo(url, null, group);
}

/**
 * Move an entry before `beforeUrl` (or to the end of `group`).
 *
 * A group change goes first and on its own, because the server re-anchors a
 * moved entry at the end of its new group — sending the order we computed
 * locally before that happened would fight it. Then the reorder states the
 * whole order explicitly.
 */
async function moveTo(url: string, beforeUrl: string | null, group: string): Promise<void> {
  const list = entries.value.map((e) => ({ ...e }));
  const from = list.findIndex((e) => e.url === url);
  if (from < 0) return;
  const [moved] = list.splice(from, 1);
  const groupChanged = (moved.group ?? '') !== group;
  moved.group = group || undefined;
  if (beforeUrl) {
    const to = list.findIndex((e) => e.url === beforeUrl);
    list.splice(to < 0 ? list.length : to, 0, moved);
  } else {
    list.push(moved);
  }
  const ordered = orderedUrls(list);

  busy.value = true;
  error.value = null;
  try {
    if (groupChanged) {
      apply(await updateLink(props.document.projectId, folder.value, url, { group }));
    }
    apply(await reorderLinks(props.document.projectId, folder.value, ordered));
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

/** Group-contiguous order, ungrouped first — the shape the server keeps. */
function orderedUrls(list: LinkEntryView[]): string[] {
  const buckets = new Map<string, string[]>();
  buckets.set('', []);
  for (const g of groups.value) buckets.set(g, []);
  for (const e of list) {
    const key = e.group ?? '';
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key)!.push(e.url);
  }
  return [...buckets.values()].flat();
}

// ── reading a card ─────────────────────────────────────────────────

/** Through safeUrl(): the URL was pasted, and a card is a thing to click. */
function href(entry: LinkEntryView): string | null {
  return safeUrl(entry.url);
}

function teaser(entry: LinkEntryView): string | null {
  return teaserOf(entry.teaser, entry.url);
}

function ownTeaser(entry: LinkEntryView): boolean {
  return teaserIsOwn(entry.teaser);
}

/** Source line: the page's own site name when it differs from the host. */
function metaLine(entry: LinkEntryView): string {
  const bits: string[] = [entry.host];
  const site = previewFor(entry.url)?.siteName;
  if (site && site.toLowerCase() !== entry.host.toLowerCase()) bits.push(site);
  if (entry.addedAt) bits.push(entry.addedAt.slice(0, 10));
  if (entry.viewedAt) bits.push(t('links.app.seenOn', { date: entry.viewedAt.slice(0, 10) }));
  return bits.join(' · ');
}

function message(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) return String((e as Error).message);
  return String(e);
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-3 p-3">
    <!-- Paste row. A text field rather than a dialog: adding a link is the
         one thing this app does constantly. -->
    <div class="flex items-start gap-2">
      <!-- Width goes on a wrapper, never on the field: VInput is `w-full` by
           design, and a `w-48` merged onto it loses to that `w-full` in the
           cascade — two of them side by side then fight over the whole row.
           Same convention as the search app's facet row. -->
      <div class="min-w-0 flex-1">
        <VInput
          v-model="draft"
          :placeholder="t('links.app.pastePlaceholder')"
          :disabled="busy"
          @keyup.enter="addFromDraft()"
        />
      </div>
      <div class="w-48 flex-none">
        <VInput
          v-model="draftGroup"
          :placeholder="t('links.app.groupPlaceholder')"
          :suggestions="groups"
          :disabled="busy"
        />
      </div>
      <VButton variant="primary" :disabled="busy || !draft.trim()" @click="addFromDraft()">
        {{ t('links.app.add') }}
      </VButton>
      <VButton variant="ghost" :disabled="busy" :title="t('links.app.newGroup')" @click="onNewGroup()">
        {{ t('links.app.newGroupButton') }}
      </VButton>
      <VButton
        variant="ghost"
        :disabled="busy"
        :title="t('links.app.rebuildTip')"
        @click="onRebuild()"
      >
        ↻
      </VButton>
      <!-- Capture access. Its own control rather than an item in a card's ⋯
           menu: it is about the list as a whole, not about any one link. -->
      <VButton
        variant="ghost"
        :title="t('links.app.captureTip')"
        @click="settingsOpen = true"
      >
        ⚙
      </VButton>
    </div>

    <VAlert v-if="error" variant="error" class="whitespace-pre-line">{{ error }}</VAlert>

    <!-- Mode, filter, group chips -->
    <div class="flex flex-wrap items-center gap-2">
      <!-- The pile leads with what is left to read, because that is the
           number the reader came for. -->
      <VButton
        size="sm"
        :variant="stackMode ? 'primary' : 'ghost'"
        :title="stackMode ? t('links.app.backToList') : t('links.app.readPile')"
        @click="mode = stackMode ? 'list' : 'stack'"
      >
        {{ stackMode ? t('links.app.list') : t('links.app.stack', { count: unseenCount }) }}
      </VButton>

      <template v-if="stackMode">
        <VButton
          size="sm"
          variant="ghost"
          :title="stackOrder === 'newest' ? t('links.app.newestFirst') : t('links.app.oldestFirst')"
          @click="stackOrder = stackOrder === 'newest' ? 'oldest' : 'newest'"
        >
          {{ stackOrder === 'newest' ? t('links.app.newest') : t('links.app.oldest') }}
        </VButton>
        <VButton
          size="sm"
          :variant="showSeen ? 'primary' : 'ghost'"
          @click="showSeen = !showSeen"
        >
          {{ t('links.app.showSeen') }}
        </VButton>
      </template>

      <div class="w-64 flex-none">
        <VInput v-model="filter" size="sm" :placeholder="t('links.app.filterPlaceholder')" />
      </div>
      <VButton
        size="sm"
        :variant="activeGroup === null ? 'primary' : 'ghost'"
        @click="activeGroup = null"
      >
        {{ t('links.app.all', { count: entries.length }) }}
      </VButton>
      <VButton
        v-if="counts[''] > 0"
        size="sm"
        :variant="activeGroup === '' ? 'primary' : 'ghost'"
        @click="activeGroup = ''"
      >
        {{ t('links.app.ungrouped', { count: counts[''] }) }}
      </VButton>
      <VButton
        v-for="g in groups"
        :key="g"
        size="sm"
        :variant="activeGroup === g ? 'primary' : 'ghost'"
        @click="activeGroup = g"
      >
        {{ g }} {{ counts[g] ?? 0 }}
      </VButton>
    </div>

    <!-- The list -->
    <div class="min-h-0 flex-1 overflow-y-auto">
      <div class="mx-auto flex w-full max-w-3xl flex-col gap-2">
        <VEmptyState
          v-if="entries.length === 0"
          :headline="t('links.app.emptyHeadline')"
          :body="t('links.app.emptyBody')"
        />
        <VEmptyState
          v-else-if="stackMode && stackItems.length === 0"
          :headline="stackEmpty.headline"
          :body="stackEmpty.body"
        />
        <VEmptyState
          v-else-if="!stackMode && filtered.length === 0"
          :headline="t('links.app.nothingMatchesHeadline')"
          :body="t('links.app.nothingMatchesBody')"
        />

        <template v-for="section in sections" :key="section.group || '__lead__'">
          <div
            v-if="section.group && !stackMode"
            class="group/heading flex items-center gap-2 px-1 pt-3 pb-1"
            @dragover.prevent
            @drop="onDropOnSection(section.group)"
          >
            <span class="text-xs font-semibold uppercase tracking-wide opacity-50">
              {{ section.group }}
            </span>
            <span class="text-xs opacity-40">{{ section.items.length }}</span>
            <VButton
              size="xs"
              variant="ghost"
              class="opacity-0 group-hover/heading:opacity-100"
              :title="t('links.app.renameGroupTip')"
              @click="onRenameGroup(section.group)"
            >
              ✎
            </VButton>
          </div>
          <div
            v-else-if="!stackMode"
            class="px-1 pt-1"
            @dragover.prevent
            @drop="onDropOnSection('')"
          >
            <!-- The lead group has no heading — it is the absence of one. The
                 strip stays as a drop target so a link can be pulled out of
                 every group. -->
            <span class="text-xs opacity-30">{{ t('links.app.noGroup') }}</span>
          </div>

          <!-- Clicking the card selects it, clicking again lets it go. The
               ring is the same one the search app puts on an opened hit, so
               "this is the one I mean" reads identically on both surfaces.
               Everything interactive inside stops the click, or opening a
               link would also change the selection. -->
          <VCard
            v-for="entry in section.items"
            :key="entry.url"
            :class="[
              'group/card cursor-pointer transition-all',
              isSelected(entry) ? 'ring-2 ring-primary' : 'hover:ring-1 hover:ring-base-300',
              // Dimmed rather than hidden or struck through: a seen link is
              // still a link somebody kept, and it stays clickable.
              viewed(entry) ? 'opacity-55 hover:opacity-100' : '',
            ]"
            :draggable="!stackMode"
            @click="toggleSelect(entry)"
            @dragstart="onDragStart(entry)"
            @dragover.prevent
            @drop="onDropOnEntry(entry)"
          >
            <div class="flex gap-3">
              <LinkPicture :url="entry.url" :image="entry.image" :alt="entry.title ?? ''" />

              <div class="flex min-w-0 flex-1 flex-col gap-1">
                <a
                  v-if="href(entry)"
                  :href="href(entry)!"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="font-semibold hover:underline"
                  @click.stop
                >
                  {{ entry.title ?? entry.host }}
                </a>
                <span v-else class="font-semibold">{{ entry.title ?? entry.host }}</span>

                <span class="text-xs opacity-60">{{ metaLine(entry) }}</span>

                <!-- The reader's own teaser is shown as theirs; the page's is
                     shown as the page's. Same slot, different weight, so it is
                     obvious which one an edit would replace. -->
                <p
                  v-if="teaser(entry)"
                  :class="['line-clamp-2 text-sm', ownTeaser(entry) ? 'opacity-90' : 'opacity-70']"
                >
                  {{ teaser(entry) }}
                </p>

                <p v-if="entry.note" class="line-clamp-2 text-sm italic opacity-70">
                  {{ entry.note }}
                </p>

                <!-- Pills, not #hashtags: a tag here is a thing somebody
                     attached to this link, and a pill reads as attached. One
                     hue (`info`, a blue) throughout — tinted normally, filled
                     when it carries the current filter, so on and off are the
                     same colour at two weights and not two colours. Clicking a
                     filled pill clears the filter: one that looks switched on
                     has to be switchable off, or it reads as broken. -->
                <div v-if="(entry.tags ?? []).length > 0" class="flex flex-wrap gap-1">
                  <button
                    v-for="tag in entry.tags"
                    :key="tag"
                    class="hover:opacity-80"
                    :title="filter === tag
                      ? t('links.app.clearFilter')
                      : t('links.app.filterByTag', { tag })"
                    @click.stop="filter = filter === tag ? '' : tag"
                  >
                    <VBadge size="sm" variant="info" :soft="filter !== tag">{{ tag }}</VBadge>
                  </button>
                </div>
              </div>

              <div class="flex flex-none flex-col items-end gap-1">
                <!-- The tick is the one control that stays visible always: in
                     the pile it is the whole interaction, and hunting for it on
                     hover would make working through a list tiring. -->
                <VButton
                  size="xs"
                  :variant="viewed(entry) ? 'primary' : 'ghost'"
                  :disabled="busy"
                  :title="viewed(entry) ? t('links.app.putBack') : t('links.app.markSeen')"
                  @click.stop="onToggleViewed(entry)"
                >
                  ✓
                </VButton>
                <!-- Copy the address. Revealed on hover like the ⋯ menu, and
                     kept visible while the entry is the selected one. Copied
                     shows as the button turning primary rather than as a ✓ —
                     the tick above it is already spoken for. -->
                <VButton
                  size="xs"
                  :variant="copiedUrl === entry.url ? 'primary' : 'ghost'"
                  :class="isSelected(entry) ? '' : 'opacity-0 group-hover/card:opacity-100'"
                  :title="copiedUrl === entry.url ? t('links.app.copied') : t('links.app.copyLink')"
                  @click.stop="onCopy(entry)"
                >
                  ⧉
                </VButton>
                <VButton
                  size="xs"
                  variant="ghost"
                  class="opacity-0 group-hover/card:opacity-100"
                  :title="t('links.app.actions')"
                  @click.stop="openMenu = openMenu === entry.url ? null : entry.url"
                >
                  ⋯
                </VButton>
                <div v-if="openMenu === entry.url" class="flex flex-col items-end gap-1">
                  <!-- Renders nothing when the host offers no sharing — the
                       menu then simply has one row fewer. -->
                  <VShareButton
                    :subject="shareSubject(entry)"
                    size="xs"
                    :label="t('links.app.share')"
                    show-label
                    @click.stop="openMenu = null"
                  />
                  <VButton size="xs" variant="ghost" @click.stop="editing = entry; openMenu = null">
                    {{ t('links.app.edit') }}
                  </VButton>
                  <VButton size="xs" variant="ghost" @click.stop="onRefreshPreview(entry)">
                    {{ t('links.app.refreshPreview') }}
                  </VButton>
                  <VButton size="xs" variant="ghost" @click.stop="onRemove(entry)">
                    {{ t('links.app.remove') }}
                  </VButton>
                </div>
              </div>
            </div>
          </VCard>
        </template>
      </div>
    </div>

    <CaptureTokenDialog
      v-if="settingsOpen"
      :project-id="props.document.projectId"
      :folder="folder"
      @close="settingsOpen = false"
    />

    <LinkEditDialog
      v-if="editing"
      :entry="editing"
      :groups="groups"
      :page-teaser="previewFor(editing.url)?.description ?? null"
      :page-image="previewFor(editing.url)?.image ?? null"
      :busy="busy"
      @save="onSaveEdit"
      @cancel="editing = null"
    />
  </div>
</template>
