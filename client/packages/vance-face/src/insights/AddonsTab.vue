<script setup lang="ts">
import { computed, onMounted } from 'vue';
import type { AddonInsightDto } from '@vance/generated';
import { ChecksumStatus } from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useAddonInsights } from '@/composables/useAddons';

const state = useAddonInsights();

onMounted(() => {
  void state.load();
});

function refresh(): void {
  void state.load();
}

// ─── Status helpers ──────────────────────────────────────────────────

interface StatusBadge {
  label: string;
  cssClass: string;
  title: string;
}

/**
 * Effective deployment status — combines DB-enabled, on-disk unpacked
 * and Spring-bean-registered into one badge.
 *
 *   loaded   = enabled + bean active + unpacked .vab
 *   built-in = enabled + bean active without an unpacked .vab —
 *              addon is shipped inside the brain image
 *   disabled = admin flipped enabled=false
 *   broken   = enabled + unpacked but no bean (Spring rejected addon)
 *   missing  = enabled but no bundle and no bean
 */
function deploymentStatus(addon: AddonInsightDto): StatusBadge {
  if (!addon.enabled) {
    return { label: 'disabled', cssClass: 'badge-status badge-status--stopped',
      title: 'enabled=false in db.addons' };
  }
  if (addon.beanRegistered && addon.unpacked) {
    return { label: 'loaded', cssClass: 'badge-status badge-status--running',
      title: 'unpacked + Spring bean active' };
  }
  if (addon.unpacked && !addon.beanRegistered) {
    return { label: 'broken', cssClass: 'badge-status badge-status--stale',
      title: 'unpacked but Spring did not register the VanceAddon bean' };
  }
  if (addon.beanRegistered && !addon.unpacked) {
    return { label: 'built-in', cssClass: 'badge-status badge-status--running',
      title: 'addon ships inside the brain image (no separate .vab bundle)' };
  }
  return { label: 'missing', cssClass: 'badge-status badge-status--stale',
    title: 'no on-disk bundle and no Spring bean' };
}

function checksumBadge(addon: AddonInsightDto): StatusBadge | null {
  switch (addon.checksumStatus) {
    case ChecksumStatus.VERIFIED:
      return { label: 'verified', cssClass: 'badge-status badge-status--running',
        title: 'on-disk .vab hash matches the configured checksum' };
    case ChecksumStatus.MISMATCH:
      return { label: 'mismatch', cssClass: 'badge-status badge-status--stale',
        title: 'on-disk .vab hash does NOT match — entrypoint should have refused this addon' };
    case ChecksumStatus.UNVERIFIED:
      return { label: 'unverified', cssClass: 'badge-status badge-status--starting',
        title: 'checksum set but no source .vab cached to verify against' };
    case ChecksumStatus.NONE:
    default:
      return null;
  }
}

/** "bundled:xyz" → "bundled", "builtin:xyz" → "built-in", URLs → "url". */
function sourceLabel(addon: AddonInsightDto): string {
  if (addon.path.startsWith('bundled:')) return 'bundled';
  if (addon.path.startsWith('builtin:')) return 'built-in';
  return 'url';
}

function sourceDetail(addon: AddonInsightDto): string {
  if (addon.path.startsWith('bundled:')) return addon.path.substring('bundled:'.length);
  if (addon.path.startsWith('builtin:')) return addon.path.substring('builtin:'.length);
  return addon.path;
}

// ─── Time formatting ─────────────────────────────────────────────────

function fmtTime(value: string | Date | undefined | null): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString().replace('T', ' ').slice(0, 19);
  return String(value).replace('T', ' ').slice(0, 19);
}

// ─── Aggregates for the toolbar ──────────────────────────────────────

const loadedCount = computed(() => state.addons.value.filter(a => a.loaded).length);
const disabledCount = computed(() => state.addons.value.filter(a => !a.enabled).length);
const brokenCount = computed(() =>
  state.addons.value.filter(a => a.enabled && a.unpacked && !a.beanRegistered).length);
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <!-- ─── Toolbar ─── -->
    <div class="flex flex-wrap items-end gap-3 text-sm">
      <VButton variant="ghost" size="sm" @click="refresh">Refresh</VButton>
      <div class="text-xs opacity-60 ml-auto">
        {{ state.addons.value.length }} addon{{ state.addons.value.length === 1 ? '' : 's' }}
        · {{ loadedCount }} loaded
        <span v-if="disabledCount > 0">· {{ disabledCount }} disabled</span>
        <span v-if="brokenCount > 0" class="text-red-600">· {{ brokenCount }} broken</span>
      </div>
    </div>

    <div v-if="state.loading.value" class="text-sm opacity-60">Loading addons…</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <VEmptyState
      v-else-if="state.addons.value.length === 0"
      :headline="'No addons'"
      :body="'The addons collection is empty — no first-party addons bundled, none installed via vance-anus.'"
    />

    <table v-else class="table table-sm">
      <thead>
        <tr>
          <th class="w-44">Addon</th>
          <th class="w-28">Status</th>
          <th class="w-28">Source</th>
          <th class="w-28">Version</th>
          <th>Notes</th>
          <th class="w-32">Unpacked</th>
          <th class="w-24">Checksum</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="addon in state.addons.value" :key="addon.name">
          <td class="text-sm">
            <span class="font-medium">{{ addon.displayName }}</span>
            <div class="text-[10px] opacity-50 font-mono">{{ addon.name }}</div>
          </td>
          <td>
            <span
              :class="deploymentStatus(addon).cssClass"
              :title="deploymentStatus(addon).title"
            >{{ deploymentStatus(addon).label }}</span>
          </td>
          <td class="text-xs">
            <span class="opacity-80">{{ sourceLabel(addon) }}</span>
            <div class="text-[10px] opacity-50 font-mono truncate" :title="sourceDetail(addon)">
              {{ sourceDetail(addon) }}
            </div>
          </td>
          <td class="font-mono text-xs">{{ addon.version ?? '—' }}</td>
          <td class="text-xs">
            <span v-if="addon.status" class="addon-status">{{ addon.status }}</span>
            <span v-else class="opacity-30">—</span>
          </td>
          <td class="text-xs opacity-70" :title="fmtTime(addon.unpackedAt)">
            {{ addon.unpackedAt ? fmtTime(addon.unpackedAt) : '—' }}
          </td>
          <td class="text-xs">
            <span
              v-if="checksumBadge(addon)"
              :class="checksumBadge(addon)!.cssClass"
              :title="checksumBadge(addon)!.title"
            >{{ checksumBadge(addon)!.label }}</span>
            <span v-else class="opacity-50">—</span>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="text-[11px] opacity-60">
      Read-only view. Use <span class="font-mono">vance-anus addon …</span> for changes.
    </div>
  </div>
</template>

<style scoped>
.badge-status {
  display: inline-block;
  padding: 0.05rem 0.45rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: lowercase;
  letter-spacing: 0.02em;
  background: rgba(127, 127, 127, 0.18);
}
.badge-status--running  { background: rgba(34, 197, 94, 0.22);  color: #16a34a; }
.badge-status--starting { background: rgba(234, 179, 8, 0.22);  color: #b45309; }
.badge-status--stopped  { background: rgba(127, 127, 127, 0.22); }
.badge-status--stale    { background: rgba(239, 68, 68, 0.22);  color: #b91c1c; }

.addon-status {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
