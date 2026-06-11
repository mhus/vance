<script setup lang="ts">
/**
 * Cortex right-panel container. Switches between Chat and Help tabs.
 *
 * <p>Chat is the agent conversation (CortexChatPanel — owns WS session,
 * tool service attachment, message stream). Help shows per-document
 * markdown loaded from the brain's bundled help files; the path is
 * derived from the active document's binding (see {@link resolveHelpPath}).
 *
 * <p>The chat panel stays mounted across tab switches (keep-alive) so
 * its WebSocket and message buffer survive when the user peeks at Help.
 * The help panel can re-mount cheaply — it's just a markdown render.
 */
import { computed, ref } from 'vue';
import type { CortexDocument } from '../types';
import { resolveHelpPath } from '../help';
import CortexChatPanel from './CortexChatPanel.vue';
import CortexHelpPanel from './CortexHelpPanel.vue';
import type { CortexClientToolService } from '../clientToolService';

interface Props {
  sessionId: string;
  projectId: string;
  toolService?: CortexClientToolService | null;
  activeDocument: CortexDocument | null;
}

const props = defineProps<Props>();

type RightTab = 'chat' | 'help';
const activeTab = ref<RightTab>('chat');

const helpPath = computed<string | null>(() => resolveHelpPath(props.activeDocument));
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <div
      class="flex items-stretch border-b border-base-300 bg-base-200 text-sm shrink-0"
      role="tablist"
      aria-label="Right panel"
    >
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'chat'"
        class="px-4 py-1.5 border-r border-base-300"
        :class="activeTab === 'chat' ? 'bg-base-100 font-semibold' : 'opacity-70 hover:bg-base-100/40'"
        @click="activeTab = 'chat'"
      >Chat</button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'help'"
        class="px-4 py-1.5"
        :class="activeTab === 'help' ? 'bg-base-100 font-semibold' : 'opacity-70 hover:bg-base-100/40'"
        @click="activeTab = 'help'"
      >Help</button>
    </div>
    <div class="flex-1 min-h-0">
      <!-- v-show keeps the chat panel mounted while the user is on the
           Help tab, preserving its WS state + message buffer. -->
      <div v-show="activeTab === 'chat'" class="h-full">
        <CortexChatPanel
          :session-id="sessionId"
          :project-id="projectId"
          :tool-service="toolService ?? null"
        />
      </div>
      <div v-show="activeTab === 'help'" class="h-full">
        <CortexHelpPanel :help-path="helpPath" />
      </div>
    </div>
  </div>
</template>
