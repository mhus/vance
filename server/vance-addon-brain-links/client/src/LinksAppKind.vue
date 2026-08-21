<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@vance/components';
import { safeUrl } from '@vance/shared';
import LinkPicture from './LinkPicture.vue';
import LinkEditDialog from './LinkEditDialog.vue';
import {
  addLink,
  rebuildLinks,
  removeLink,
  renameGroup,
  reorderLinks,
  scanLinks,
  setGroups,
  updateLink,
  type LinkFields,
} from './api';
import { forgetPreview, previewFor, requestPreview, teaserIsOwn, teaserOf } from './linkPreview';
import type { LinksView } from './generated/links/LinksView';
import type { LinkEntryView } from './generated/links/LinkEntryView';

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
const openMenu = ref<string | null>(null);
const dragged = ref<string | null>(null);
const selectedUrl = ref<string | null>(null);

type Section = { group: string; items: LinkEntryView[] };

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
  ((sel: { appDocId: string; selection: string } | null) => void) | null
>('vance:report-app-selection', null);

const selectedEntry = computed<LinkEntryView | null>(
  () => entries.value.find((e) => e.url === selectedUrl.value) ?? null,
);

watch(selectedEntry, (entry) => {
  if (!reportAppSelection) return;
  const appId = props.document.id;
  reportAppSelection(appId && entry ? { appDocId: appId, selection: entry.url } : null);
}, { immediate: true });

// Leaving the tab must retract the selection — a stale one would answer
// "this link" with a link nobody is looking at any more.
onBeforeUnmount(() => reportAppSelection?.(null));

function toggleSelect(entry: LinkEntryView): void {
  selectedUrl.value = selectedUrl.value === entry.url ? null : entry.url;
  openMenu.value = null;
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
  if (!window.confirm(`Remove “${label}” from this list?`)) return;
  await run(() => removeLink(props.document.projectId, folder.value, entry.url));
}

async function onSaveEdit(fields: LinkFields): Promise<void> {
  const entry = editing.value;
  if (!entry) return;
  await run(() => updateLink(props.document.projectId, folder.value, entry.url, fields));
  editing.value = null;
}

/** Ask the page again — the server-side preview cache holds a week. */
function onRefreshPreview(entry: LinkEntryView): void {
  forgetPreview(entry.url);
  requestPreview(entry.url);
  openMenu.value = null;
}

async function onNewGroup(): Promise<void> {
  const name = window.prompt('New group:');
  if (name === null || !name.trim()) return;
  const next = [...groups.value, name.trim()];
  await run(() => setGroups(props.document.projectId, folder.value, next));
}

async function onRenameGroup(group: string): Promise<void> {
  const next = window.prompt(`Rename “${group}” (empty dissolves the group):`, group);
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

function onDragStart(entry: LinkEntryView): void {
  dragged.value = entry.url;
}

async function onDropOnEntry(target: LinkEntryView): Promise<void> {
  const url = dragged.value;
  dragged.value = null;
  if (!url || url === target.url) return;
  await moveTo(url, target.url, target.group ?? '');
}

async function onDropOnSection(group: string): Promise<void> {
  const url = dragged.value;
  dragged.value = null;
  if (!url) return;
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
          placeholder="Paste a link — or several, one per line"
          :disabled="busy"
          @keyup.enter="addFromDraft()"
        />
      </div>
      <div class="w-48 flex-none">
        <VInput
          v-model="draftGroup"
          placeholder="Group (optional)"
          :suggestions="groups"
          :disabled="busy"
        />
      </div>
      <VButton variant="primary" :disabled="busy || !draft.trim()" @click="addFromDraft()">
        Add
      </VButton>
      <VButton variant="ghost" :disabled="busy" title="New group" @click="onNewGroup()">
        + Group
      </VButton>
      <VButton
        variant="ghost"
        :disabled="busy"
        title="Regenerate the _index.md link list"
        @click="onRebuild()"
      >
        ↻
      </VButton>
    </div>

    <VAlert v-if="error" variant="error" class="whitespace-pre-line">{{ error }}</VAlert>

    <!-- Filter + group chips -->
    <div class="flex flex-wrap items-center gap-2">
      <div class="w-64 flex-none">
        <VInput v-model="filter" size="sm" placeholder="Filter…" />
      </div>
      <VButton
        size="sm"
        :variant="activeGroup === null ? 'primary' : 'ghost'"
        @click="activeGroup = null"
      >
        All {{ entries.length }}
      </VButton>
      <VButton
        v-if="counts[''] > 0"
        size="sm"
        :variant="activeGroup === '' ? 'primary' : 'ghost'"
        @click="activeGroup = ''"
      >
        Ungrouped {{ counts[''] }}
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
          headline="No links yet"
          body="Paste a URL above. The title comes from the page; the teaser and the picture
                are read from it live, so a link is complete the moment you add it."
        />
        <VEmptyState
          v-else-if="filtered.length === 0"
          headline="Nothing matches"
          body="No link in this list matches the filter."
        />

        <template v-for="section in sections" :key="section.group || '__lead__'">
          <div
            v-if="section.group"
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
              title="Rename or dissolve this group"
              @click="onRenameGroup(section.group)"
            >
              ✎
            </VButton>
          </div>
          <div
            v-else
            class="px-1 pt-1"
            @dragover.prevent
            @drop="onDropOnSection('')"
          >
            <!-- The lead group has no heading — it is the absence of one. The
                 strip stays as a drop target so a link can be pulled out of
                 every group. -->
            <span class="text-xs opacity-30">no group</span>
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
            ]"
            draggable="true"
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

                <div v-if="(entry.tags ?? []).length > 0" class="flex flex-wrap gap-1">
                  <button
                    v-for="tag in entry.tags"
                    :key="tag"
                    class="text-xs opacity-50 hover:opacity-100"
                    @click.stop="filter = tag"
                  >
                    #{{ tag }}
                  </button>
                </div>
              </div>

              <div class="flex flex-none flex-col items-end gap-1">
                <VButton
                  size="xs"
                  variant="ghost"
                  class="opacity-0 group-hover/card:opacity-100"
                  title="Actions"
                  @click.stop="openMenu = openMenu === entry.url ? null : entry.url"
                >
                  ⋯
                </VButton>
                <div v-if="openMenu === entry.url" class="flex flex-col items-end gap-1">
                  <VButton size="xs" variant="ghost" @click.stop="editing = entry; openMenu = null">
                    Edit…
                  </VButton>
                  <VButton size="xs" variant="ghost" @click.stop="onRefreshPreview(entry)">
                    Refresh preview
                  </VButton>
                  <VButton size="xs" variant="ghost" @click.stop="onRemove(entry)">
                    Remove
                  </VButton>
                </div>
              </div>
            </div>
          </VCard>
        </template>
      </div>
    </div>

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
