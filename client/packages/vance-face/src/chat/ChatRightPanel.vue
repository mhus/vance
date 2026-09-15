<script setup lang="ts">
import { ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { ProcessProgressNotification } from '@vance/generated';
import ProgressFeed from './ProgressFeed.vue';
import SkillPanel from './SkillPanel.vue';
import WizardPanel from './WizardPanel.vue';

interface Props {
  /** Live socket — forwarded to the skills tab for its process-skill LIST round-trip. */
  socket: BrainWsApi;
  events: ProcessProgressNotification[];
  projectId?: string;
  /** Active session id — only used by the panels to decide when to refresh. */
  sessionKey?: string;
}

withDefaults(defineProps<Props>(), {
  projectId: undefined,
  sessionKey: undefined,
});

const emit = defineEmits<{
  (e: 'prompt-ready', prompt: string): void;
}>();

/** Right-aside tab selector — toggles between the live progress feed,
 * the prompt-wizards panel, and the skills panel. Default 'progress'
 * preserves the pre-wizards behaviour for users that haven't engaged
 * the feature yet. */
const rightTab = ref<'progress' | 'wizards' | 'skills'>('progress');

const wizardPanelRef = ref<InstanceType<typeof WizardPanel> | null>(null);

/**
 * Deep-link entry point used by ChatView's `vance:/wizards/...` link
 * handler. Switches the tab to wizards (so the panel is mounted),
 * then calls into {@link WizardPanel.openWizard} on the next tick.
 */
function openWizard(name: string, prefill: Record<string, string> = {}): void {
  rightTab.value = 'wizards';
  void Promise.resolve().then(() => {
    wizardPanelRef.value?.openWizard(name, prefill);
  });
}

defineExpose({ openWizard });

function onPanelPromptReady(prompt: string): void {
  emit('prompt-ready', prompt);
}
</script>

<template>
  <div class="h-full flex flex-col">
    <div class="flex border-b border-base-300 text-xs uppercase tracking-wide font-semibold">
      <button
        type="button"
        :class="['flex-1 py-2 transition-colors',
                 rightTab === 'progress'
                   ? 'bg-base-100 border-b-2 border-primary'
                   : 'bg-base-200 opacity-70 hover:opacity-100']"
        @click="rightTab = 'progress'"
      >
        {{ $t('chat.wizards.progressTabLabel') }}
      </button>
      <button
        type="button"
        :class="['flex-1 py-2 transition-colors',
                 rightTab === 'wizards'
                   ? 'bg-base-100 border-b-2 border-primary'
                   : 'bg-base-200 opacity-70 hover:opacity-100']"
        @click="rightTab = 'wizards'"
      >
        {{ $t('chat.wizards.tabLabel') }}
      </button>
      <button
        type="button"
        :class="['flex-1 py-2 transition-colors',
                 rightTab === 'skills'
                   ? 'bg-base-100 border-b-2 border-primary'
                   : 'bg-base-200 opacity-70 hover:opacity-100']"
        @click="rightTab = 'skills'"
      >
        {{ $t('chat.skills.tabLabel') }}
      </button>
    </div>

    <div class="flex-1 min-h-0 overflow-y-auto">
      <ProgressFeed v-if="rightTab === 'progress'" :events="events" />
      <WizardPanel
        v-else-if="rightTab === 'wizards'"
        ref="wizardPanelRef"
        :project-id="projectId"
        :session-key="sessionKey"
        @prompt-ready="onPanelPromptReady"
      />
      <SkillPanel
        v-else
        :socket="socket"
        :session-key="sessionKey"
        @prompt-ready="onPanelPromptReady"
      />
    </div>
  </div>
</template>
