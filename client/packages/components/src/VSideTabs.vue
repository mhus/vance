<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import VBadge from './VBadge.vue';

// Left-oriented tab navigation for settings-style views (profile,
// workspace settings): a vertical rail on the left, one content panel on
// the right. Each tab renders the named slot matching its id; all panels
// are mounted and toggled with v-show so form drafts survive tab switches
// (no remount). On small screens the rail collapses to a horizontal,
// scrollable row. See specification/web-ui.md §7.
//
// Content is provided per tab via a named slot keyed by the tab id:
//   <VSideTabs :tabs="tabs" v-model="active" sync-hash>
//     <template #identity> ... </template>
//     <template #speech>   ... </template>
//   </VSideTabs>

export interface SideTab {
  /** Stable id — also the slot name and (with sync-hash) the URL fragment. */
  id: string;
  label: string;
  /** Optional count/marker shown right of the label. */
  badge?: string | number;
}

interface Props {
  tabs: SideTab[];
  /** Active tab id (v-model). Falls back to the hash or the first tab. */
  modelValue?: string;
  /**
   * Mirror the active tab in `location.hash` so tabs are deep-linkable and
   * the browser back/forward buttons move between them.
   */
  syncHash?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  syncHash: false,
});

const emit = defineEmits<{ (e: 'update:modelValue', id: string): void }>();

function tabExists(id: string): boolean {
  return props.tabs.some((tab) => tab.id === id);
}

function hashId(): string {
  return decodeURIComponent(location.hash.replace(/^#/, ''));
}

function resolveInitial(): string {
  if (props.syncHash) {
    const fromHash = hashId();
    if (fromHash && tabExists(fromHash)) return fromHash;
  }
  if (props.modelValue && tabExists(props.modelValue)) return props.modelValue;
  return props.tabs[0]?.id ?? '';
}

const active = ref(resolveInitial());

function select(id: string): void {
  if (id === active.value || !tabExists(id)) return;
  active.value = id;
  emit('update:modelValue', id);
  // Assigning location.hash records a history entry; the resulting
  // hashchange is a no-op below because `active` already equals `id`.
  if (props.syncHash) location.hash = id;
}

// Parent-driven v-model changes keep the rail (and hash) in sync.
watch(
  () => props.modelValue,
  (next) => {
    if (next && tabExists(next) && next !== active.value) {
      active.value = next;
      if (props.syncHash) location.hash = next;
    }
  },
);

function onHashChange(): void {
  const fromHash = hashId();
  if (fromHash && tabExists(fromHash) && fromHash !== active.value) {
    active.value = fromHash;
    emit('update:modelValue', fromHash);
  }
}

onMounted(() => {
  // Tell the parent which tab we resolved to (hash may have won over the
  // initial v-model value).
  if (active.value && active.value !== props.modelValue) {
    emit('update:modelValue', active.value);
  }
  if (props.syncHash) {
    // Seed the hash when the URL arrived without one, so a reload or a
    // copied link lands on the same tab. replaceState never fires
    // hashchange, so there is no feedback loop here.
    if (!location.hash && active.value) {
      history.replaceState(
        null,
        '',
        `${location.pathname}${location.search}#${active.value}`,
      );
    }
    window.addEventListener('hashchange', onHashChange);
  }
});

onBeforeUnmount(() => {
  if (props.syncHash) window.removeEventListener('hashchange', onHashChange);
});
</script>

<template>
  <div class="flex flex-col md:flex-row gap-6">
    <nav
      class="flex md:flex-col gap-1 md:w-56 md:shrink-0 overflow-x-auto md:overflow-visible"
      aria-label="Sections"
    >
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        :class="[
          'flex items-center justify-between gap-2 rounded-lg px-3 py-2',
          'text-left text-sm whitespace-nowrap transition-colors',
          tab.id === active
            ? 'bg-base-300 font-semibold'
            : 'opacity-70 hover:opacity-100 hover:bg-base-200',
        ]"
        :aria-current="tab.id === active ? 'page' : undefined"
        @click="select(tab.id)"
      >
        <span>{{ tab.label }}</span>
        <VBadge
          v-if="tab.badge !== undefined && tab.badge !== '' && tab.badge !== 0"
          size="sm"
        >
          {{ tab.badge }}
        </VBadge>
      </button>
    </nav>

    <div class="min-w-0 flex-1">
      <template v-for="tab in tabs" :key="tab.id">
        <div v-show="tab.id === active">
          <slot :name="tab.id" :active="tab.id === active" />
        </div>
      </template>
    </div>
  </div>
</template>
