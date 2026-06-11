<script setup lang="ts">
/**
 * Cortex Help-Tab. Loads a per-document help markdown via
 * {@link useHelp} (same composable EditorShell's help drawer uses) and
 * renders it through MarkdownView. Reloads automatically when
 * {@code helpPath} changes so switching tabs surfaces the relevant
 * doc-kind help without a manual click.
 *
 * <p>Source files live in the brain at {@code help/{lang}/{path}}.
 * Missing files surface as a friendly "no help yet" hint rather than a
 * crash — encourages incremental coverage as doc kinds settle.
 */
import { onMounted, watch } from 'vue';
import { MarkdownView } from '@/components';
import { useHelp } from '@/composables/useHelp';

interface Props {
  helpPath: string | null;
}

const props = defineProps<Props>();

const help = useHelp();

function reload(): void {
  if (props.helpPath) {
    void help.load(props.helpPath);
  }
}

onMounted(reload);

watch(() => props.helpPath, reload);
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <div class="px-3 py-2 border-b border-base-300 text-xs opacity-60 font-mono">
      {{ helpPath ?? '(no help path)' }}
    </div>
    <div class="flex-1 min-h-0 overflow-y-auto p-3">
      <div v-if="help.loading.value" class="text-xs opacity-60">
        Loading help…
      </div>
      <div v-else-if="help.error.value" class="text-xs opacity-60">
        No help available for this document type yet.
      </div>
      <div v-else-if="!help.content.value" class="text-xs opacity-60">
        (empty)
      </div>
      <MarkdownView v-else :source="help.content.value" />
    </div>
  </div>
</template>
