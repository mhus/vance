<script setup lang="ts">
import { computed } from 'vue';
import { CodeEditor } from '@/components';
import type { CortexDocument } from '../types';

interface Props {
  document: CortexDocument;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'update', text: string): void;
}>();

/**
 * Derive a language hint from the document's mime-type, falling back to
 * the path extension when the server didn't store one. CodeEditor's own
 * {@code languageFor} mapping interprets common mime-types directly, but
 * extension-derived mime-types give nicer defaults for files the server
 * left as {@code null} (e.g. uploaded snippets, markdown notes created
 * via API without explicit mime).
 */
const effectiveMimeType = computed<string>(() => {
  const explicit = props.document.mimeType;
  if (explicit) return explicit;
  const lower = props.document.path.toLowerCase();
  if (lower.endsWith('.md')) return 'text/markdown';
  if (lower.endsWith('.json')) return 'application/json';
  if (lower.endsWith('.yaml') || lower.endsWith('.yml')) return 'application/yaml';
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) return 'text/javascript';
  if (lower.endsWith('.ts')) return 'text/typescript';
  if (lower.endsWith('.py')) return 'text/x-python';
  if (lower.endsWith('.sh') || lower.endsWith('.bash')) return 'text/x-shellscript';
  return 'text/plain';
});
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <div class="flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm">
      <span class="font-mono opacity-80 truncate">{{ document.path }}</span>
      <span v-if="document.dirty" class="opacity-60">●</span>
      <span class="flex-1" />
      <span class="opacity-50 text-xs font-mono">{{ effectiveMimeType }}</span>
    </div>
    <div class="flex-1 min-h-0 overflow-hidden">
      <CodeEditor
        :model-value="document.inlineText"
        :mime-type="effectiveMimeType"
        @update:model-value="(v: string) => emit('update', v)"
      />
    </div>
  </div>
</template>
