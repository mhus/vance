<script setup lang="ts">
/**
 * The launcher — starred documents, the editor list, addon tiles.
 *
 * <p>Split out of the old `IndexApp.vue`, which carried this and the login
 * form in one component behind a `mode` ref. The two had nothing in common
 * except the URL they happened to share: this one needs a tenant, a session
 * and the addon manifest, the login screen must work without any of them.
 *
 * <p>It is a route rather than its own page because of how this workspace is
 * actually used — the way through here is `documents → landing → inbox →
 * landing → chat`, so the landing is entered more often than any single
 * editor. Left outside the cluster it would have cost *two* full page loads
 * per editor switch instead of one.
 */
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { asUiLevel, getActiveUiLevel, loadAddonManifest, rankOf, type WebUiLevel } from '@/platform';
import { EditorShell, StarredTile } from '@/components';
import { useStarredStore } from '@/starred/starredStore';
import { editorHref } from '@/platform/editorHref';

const { t } = useI18n();

// Active UI level for tile filtering, read straight from the data cookie.
//
//   * standard — chat / documents / inbox  (everyday)
//   * expert   — + scopes / tools / insights / runs  (power user)
//   * admin    — + users  (tenant admin)
//
// Server-side authorization remains the authoritative gate; this just keeps
// the page tidy for accounts that never need the power tiles.
const uiLevel = ref<WebUiLevel>(getActiveUiLevel());

const showExpertTiles = computed(() => rankOf(uiLevel.value) >= rankOf('expert'));
const showAdminTiles = computed(() => rankOf(uiLevel.value) >= rankOf('admin'));

// Landing tiles contributed by installed addons (declarative, from
// /face/addons — no federation remote loaded here). Each is gated by the
// user's UI level just like the built-in tiles, and only appears when the
// addon is actually present, so e.g. the Simple-Auth "Permissions" tile is
// there iff the simpleauth addon is loaded.
interface AddonTileEntry {
  name: string;
  label: string;
  description?: string;
  minLevel: WebUiLevel;
}
const addonTiles = ref<AddonTileEntry[]>([]);
const visibleAddonTiles = computed(() =>
  addonTiles.value.filter((tile) => rankOf(uiLevel.value) >= rankOf(tile.minLevel)),
);

// Starred documents — the user's own tiles, above the fixed editor rows. Not
// gated by UI level: this is their curation, not a power-user surface.
//
// Fetched WITHOUT `all`, so hidden entries never reach this page. The filtering
// is the server's; a `v-if` here would mean the data travelled to a surface
// that only hides it again.
const starred = useStarredStore();
const starredTiles = computed(() => starred.items);

async function onRemoveStarred(project: string, path: string): Promise<void> {
  // The store refetches in the scope it was loaded with, so no second load here.
  await starred.unstar(project, path);
}

// Result of the last check, shown next to the heading. Without this trigger the
// broken-tile state would be unreachable for a human: the list deliberately does
// not resolve its entries on read, so nothing else on this page can discover a
// dead target.
const reconcileNotice = ref<string | null>(null);

async function onReconcile(): Promise<void> {
  reconcileNotice.value = null;
  try {
    const report = await starred.reconcile();
    const broken = (report.entries ?? []).filter(
      (e) => e.outcome === 'missing' || e.outcome === 'forbidden',
    ).length;
    const count = (report.entries ?? []).length;
    reconcileNotice.value = broken
      ? t('starred.reconcileChanged', { count, broken })
      : t('starred.reconcileOk', { count });
  } catch (e) {
    reconcileNotice.value = e instanceof Error ? e.message : 'Check failed.';
  }
}

onMounted(async () => {
  // Re-read on every visit rather than once per session: the reader can change
  // their UI level in the profile page, which is outside this bundle and
  // returns here by a full page load — but a second tab would not.
  uiLevel.value = getActiveUiLevel();
  // No addons / offline reads as an empty manifest — no tiles.
  addonTiles.value = (await loadAddonManifest())
    .filter((e) => e.tile?.label)
    .map((e) => ({
      name: e.name,
      label: e.tile!.label as string,
      description: e.tile!.description,
      minLevel: asUiLevel(e.tile!.minLevel),
    }));
  await starred.load(/* all */ false, /* force */ true);
});
</script>

<template>
  <EditorShell :title="$t('common.home')">
    <!-- Starred tiles sit above the fixed editor rows and share their
         container width: a wider box here would put the two blocks on
         different left edges, which reads as a misalignment rather than a
         design. Four tiles across is ~175px each — enough for a truncated
         label, which is all a tile carries.
         Responsive rather than a hard four-per-row: four columns are
         unreadable on a phone and inside the Facelift WebView. -->
    <div v-if="starredTiles.length" class="container mx-auto px-4 pt-8 max-w-3xl">
      <div class="mb-4 flex items-baseline gap-3">
        <h2 class="text-lg font-semibold">{{ $t('starred.sectionTitle') }}</h2>
        <button
          type="button"
          class="text-xs opacity-60 underline-offset-2 hover:underline hover:opacity-100"
          :disabled="starred.busy"
          @click="onReconcile()"
        >{{ $t('starred.reconcile') }}</button>
        <span v-if="reconcileNotice" class="text-xs opacity-70">{{ reconcileNotice }}</span>
      </div>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        <StarredTile
          v-for="tile in starredTiles"
          :key="`${tile.project} ${tile.path}`"
          :item="tile"
          :broken="starred.isBroken(tile.project, tile.path)"
          @remove="onRemoveStarred(tile.project, tile.path)"
        />
      </div>
    </div>

    <div class="container mx-auto px-4 py-8 max-w-3xl">
      <h2 class="text-lg font-semibold mb-4">{{ $t('index.sectionTitle') }}</h2>
      <ul class="flex flex-col gap-2">
          <!-- Standard tier — always visible. The three cluster editors are
               <RouterLink>s: staying inside the shell is the whole point.
               The tiles below them leave it, and are plain <a>. -->
          <li>
            <RouterLink class="tile-row" to="/chat">
              <div class="font-semibold">{{ $t('index.chat.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.chat.description') }}</div>
            </RouterLink>
          </li>
          <li>
            <RouterLink class="tile-row" to="/documents">
                <div class="font-semibold">{{ $t('index.documents.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.documents.description') }}</div>
            </RouterLink>
          </li>
          <li>
            <RouterLink class="tile-row" to="/inbox">
                <div class="font-semibold">{{ $t('index.inbox.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.inbox.description') }}</div>
            </RouterLink>
          </li>

          <!-- Expert tier — power-user surfaces (scopes / tools / insights). -->
          <li v-if="showExpertTiles">
            <a class="tile-row" :href="editorHref('scopes')">
                <div class="font-semibold">{{ $t('index.scopes.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.scopes.description') }}</div>
            </a>
          </li>
          <li v-if="showExpertTiles">
            <a class="tile-row" :href="editorHref('tools')">
                <div class="font-semibold">{{ $t('index.tools.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.tools.description') }}</div>
            </a>
          </li>
          <li v-if="showExpertTiles">
            <a class="tile-row" :href="editorHref('insights')">
                <div class="font-semibold">{{ $t('index.insights.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.insights.description') }}</div>
            </a>
          </li>
          <!-- Without a project in the query the run view opens on its own
               project sidebar, so the tile needs no parameter. -->
          <li v-if="showExpertTiles">
            <a class="tile-row" :href="editorHref('runs')">
                <div class="font-semibold">{{ $t('index.runs.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.runs.description') }}</div>
            </a>
          </li>
          <!-- Admin tier — tenant-management surfaces. The brain still
               denies non-admins at the REST layer; this is just a
               clutter filter. -->
          <li v-if="showAdminTiles">
            <a class="tile-row" :href="editorHref('users')">
                <div class="font-semibold">{{ $t('index.users.title') }}</div>
                <div class="text-sm opacity-70">{{ $t('index.users.description') }}</div>
            </a>
          </li>
          <li v-if="showAdminTiles">
            <a class="tile-row" :href="editorHref('tool-templates')">
                <div class="font-semibold">{{ $t('toolTemplates.pageTitle') }}</div>
                <div class="text-sm opacity-70">{{ $t('toolTemplates.intro') }}</div>
            </a>
          </li>

          <!-- Addon-contributed tiles (e.g. Simple-Auth "Permissions").
               Present only when the addon is installed; gated by UI level. -->
          <li v-for="tile in visibleAddonTiles" :key="tile.name">
            <a class="tile-row" :href="editorHref('addon', { addon: tile.name })">
                <div class="font-semibold">{{ tile.label }}</div>
                <div v-if="tile.description" class="text-sm opacity-70">{{ tile.description }}</div>
            </a>
          </li>
        </ul>
    </div>
  </EditorShell>
</template>

<style scoped>
/* Tailwind 4 no longer gives scoped <style> blocks implicit access to the
   theme/utilities for @apply — pull them in from the main stylesheet. */
@reference "../style/app.css";
/* Each tile is a bordered card — same look as a document-picker row
 * via VDataList. The whole tile is the link (block, no underline).
 * Hover lifts the border to primary, matching the documents/inbox
 * picker rows. {@code @apply} runs the Tailwind+DaisyUI tokens
 * through the build so the colors track the active theme. */
.tile-row {
  @apply block w-full bg-base-100 border border-base-300 rounded-lg shadow-sm p-4
         text-base-content no-underline cursor-pointer
         transition-colors hover:border-primary;
}
.tile-row:focus-visible {
  @apply outline outline-2 outline-primary outline-offset-2;
}
</style>
