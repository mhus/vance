<script setup lang="ts">
/**
 * Thin pass-through that mounts the DocumentTabShell. Kept as a named
 * integration point so CortexApp doesn't need to import the shell
 * directly — swapping the shell (e.g. introducing a tabs-as-windows
 * variant) stays a one-file change.
 */
import type { CortexDocument } from '../types';
import DocumentTabShell from './DocumentTabShell.vue';

interface Props {
  document: CortexDocument;
  /** Chat session id — forwarded to language-aware dialogs (Hactar). */
  sessionId?: string | null;
}

defineProps<Props>();

const emit = defineEmits<{
  (e: 'update', text: string): void;
}>();
</script>

<template>
  <DocumentTabShell
    :document="document"
    :session-id="sessionId ?? null"
    @update="(text: string) => emit('update', text)"
  />
</template>
