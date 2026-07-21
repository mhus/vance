<script setup lang="ts">
/**
 * Read-only view for the `vance-compose-<lang>` script blocks on non-editor
 * surfaces (BlockView: read-only WorkPage view / detail pane). Shows the
 * manifest's title/description + the single task's script; running happens in
 * the editor. Everything is derived from `attrs.yaml` (BlockView passes only
 * `attrs`), so no fence prop is needed.
 */
import { computed } from 'vue';
import jsyaml from 'js-yaml';
import { extractScript } from './scriptComposeCodec';

const props = defineProps<{ attrs: Record<string, unknown> }>();

const yaml = computed<string>(() => (props.attrs?.yaml as string | null) ?? '');
const parsed = computed<Record<string, unknown>>(() => {
  try {
    const p = jsyaml.load(yaml.value);
    return p && typeof p === 'object' && !Array.isArray(p) ? (p as Record<string, unknown>) : {};
  } catch {
    return {};
  }
});

const taskType = computed<string | undefined>(() => {
  const tasks = parsed.value.tasks;
  return Array.isArray(tasks) && tasks[0] && typeof tasks[0] === 'object'
    ? ((tasks[0] as Record<string, unknown>).type as string | undefined)
    : undefined;
});

const script = computed<string>(() => {
  const field = taskType.value === 'exec' ? 'command'
    : taskType.value === 'spawn' ? 'prompt'
      : 'code';
  return extractScript(yaml.value, field);
});

const label = computed<string>(() => {
  const type = taskType.value;
  return type === 'js' ? 'Compose JS'
    : type === 'python' ? 'Compose Python'
      : type === 'exec' ? 'Compose Bash'
        : type === 'spawn' ? 'Compose Agent'
          : 'Compose';
});

const title = computed<string | undefined>(() =>
  typeof parsed.value.title === 'string' ? parsed.value.title : undefined);
const description = computed<string | undefined>(() =>
  typeof parsed.value.description === 'string' ? parsed.value.description : undefined);
</script>

<template>
  <aside class="scv">
    <div v-if="title" class="scv__title">{{ title }}</div>
    <div v-if="description" class="scv__desc">{{ description }}</div>
    <div class="scv__label">{{ label }}</div>
    <pre class="scv__src">{{ script }}</pre>
  </aside>
</template>

<style scoped>
.scv {
  margin: 0.6em 0;
  border: 1px solid oklch(var(--bc) / 0.15);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background: oklch(var(--bc) / 0.03);
}
.scv__title { font-weight: 600; }
.scv__desc { font-size: 0.82rem; opacity: 0.7; white-space: pre-line; }
.scv__label { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.03em; opacity: 0.55; margin-top: 0.3rem; }
.scv__src {
  margin: 0.2rem 0 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.82rem;
  white-space: pre;
  overflow-x: auto;
}
</style>
