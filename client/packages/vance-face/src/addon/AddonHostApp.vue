<script setup lang="ts">
import { markRaw, onMounted, shallowRef } from 'vue';
import type { Component } from 'vue';
import { loadRemote, registerRemotes } from '@module-federation/runtime';
import { EditorShell, VAlert } from '@/components';

interface AddonTile {
  label?: string;
  description?: string;
  minLevel?: string;
}
interface AddonEntry {
  name: string;
  path: string;
  tile?: AddonTile;
}

const title = shallowRef<string>('Addon');
const area = shallowRef<Component | null>(null);
const error = shallowRef<string>('');
const loading = shallowRef<boolean>(true);

/**
 * Generic host for a federated addon "area": `addon.html?addon=<id>` loads
 * the addon's `./area` expose and mounts it. The area owns its own in-page
 * URL state (query params), so browser back/forward and deep-links work
 * without the host mediating.
 */
onMounted(async () => {
  try {
    const id = new URLSearchParams(window.location.search).get('addon');
    if (!id) {
      error.value = 'No addon specified (?addon=<id> required).';
      return;
    }

    // Presence gate: only addons the server reports as installed are loadable.
    let addons: AddonEntry[] = [];
    try {
      const res = await fetch('/face/addons', { headers: { Accept: 'application/json' } });
      if (res.ok) addons = (await res.json()) as AddonEntry[];
    } catch {
      // fall through — treated as "not available"
    }
    const entry = addons.find((a) => a.name === id);
    if (!entry) {
      error.value = `Addon '${id}' is not available in this deployment.`;
      return;
    }
    title.value = entry.tile?.label ?? id;

    registerRemotes(
      [{ name: `vance_addon_${id}`, entry: `/addons/${id}/remoteEntry.js`, type: 'module' as const }],
      { force: true },
    );
    const mod = await loadRemote<{ default?: Component } | Component>(`vance_addon_${id}/area`);
    const resolved = (mod as { default?: Component })?.default ?? (mod as Component);
    if (!resolved) {
      error.value = `Addon '${id}' does not expose an area.`;
      return;
    }
    area.value = markRaw(resolved);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <EditorShell :title="title">
    <div class="p-4">
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <p v-else-if="loading" class="text-sm opacity-60 p-4">Loading…</p>
      <component :is="area" v-else-if="area" />
    </div>
  </EditorShell>
</template>
