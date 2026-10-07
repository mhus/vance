<script setup lang="ts">
/**
 * Right-panel help of the Settings page — loads the context's help file
 * via {@link useHelp} and renders it as Markdown. The path comes from
 * {@link resolveSettingsHelpPath}: per entry the Cortex doc-kind help,
 * per area a settings-area file, otherwise the page help. Reloads
 * automatically when the path changes, so walking areas or entries
 * flips the help without a click. A missing file is a hint, not an
 * error — coverage grows incrementally, file by file.
 */
import { onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { MarkdownView } from '@/components';
import { useHelp } from '@/composables/useHelp';

const props = defineProps<{
  /** Help file to show ({@code help/{lang}/} prefix is added by the loader). */
  helpPath: string;
}>();

const { t } = useI18n();
const help = useHelp();

function reload(): void {
  void help.load(props.helpPath);
}

onMounted(reload);
watch(() => props.helpPath, reload);
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <h3 class="px-3 pt-3 pb-2 text-xs uppercase tracking-wide opacity-60">
      {{ t('settings.help.title') }}
    </h3>
    <div class="flex-1 min-h-0 overflow-y-auto px-3 pb-3">
      <div v-if="help.loading.value" class="text-xs opacity-60">
        {{ t('settings.help.loading') }}
      </div>
      <div v-else-if="help.error.value" class="text-xs opacity-60">
        {{ t('settings.help.unavailable') }}
      </div>
      <div v-else-if="!help.content.value" class="text-xs opacity-60">
        {{ t('settings.help.empty') }}
      </div>
      <MarkdownView v-else :source="help.content.value" />
    </div>
  </div>
</template>
