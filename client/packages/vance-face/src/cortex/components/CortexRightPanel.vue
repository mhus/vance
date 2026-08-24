<script setup lang="ts">
/**
 * Cortex right-panel container. Switches between Chat, Discussion and Help.
 *
 * <p>Chat is the agent conversation (CortexChatPanel — owns WS session,
 * tool service attachment, message stream). Help shows per-document
 * markdown loaded from the brain's bundled help files; the path is
 * derived from the active document's binding (see {@link resolveHelpPath}).
 *
 * <p>The chat panel stays mounted across tab switches (keep-alive) so
 * its WebSocket and message buffer survive when the user peeks at Help.
 * The help panel can re-mount cheaply — it's just a markdown render.
 *
 * <p>Discussion (threads whose object is the open document) is kept mounted for
 * a different reason than Chat: it holds a two-level position (list vs. one
 * thread open), and remounting would throw the reader back to the list every
 * time they glanced at another tab.
 */
import { computed, ref } from 'vue';
import type { CortexDocument } from '../types';
import { resolveHelpPath } from '../help';
import CortexChatPanel from './CortexChatPanel.vue';
import SessionPickerPanel from '@/components/SessionPickerPanel.vue';
import CortexThreadsPanel from './CortexThreadsPanel.vue';
import CortexHelpPanel from './CortexHelpPanel.vue';
import type { CortexClientToolService } from '../clientToolService';

interface Props {
  /**
   * The bound session, or {@code null} when none is. The tabs exist either way:
   * the panel's identity is the tab strip, and Discussion is about the open
   * document, not about a conversation. Before this the whole strip only
   * appeared once a session was bound, which made the document's own
   * discussions unreachable until you happened to start a chat.
   */
  sessionId: string | null;
  projectId: string;
  toolService?: CortexClientToolService | null;
  activeDocument: CortexDocument | null;
  boundDocumentId?: string | null;
  /**
   * An app-owned structured selection, on its way from {@code EditorApp} to
   * {@code CortexChatPanel} — see there for what it becomes.
   *
   * <p>Declared and forwarded explicitly. An undeclared prop does not reach a
   * grandchild: Vue turns it into a fallthrough attribute on this component's
   * root element, where it lands on a `div` and is never seen again. That is
   * how the app selection went missing between a canvas board (and later a
   * feed) and the chat, with every end of the chain working on its own.
   */
  appSelection?: { appDocId: string; selection: string } | null;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  /** A session was picked in the Chat tab's picker. */
  (e: 'open-session', sessionId: string): void;
}>();

type RightTab = 'chat' | 'threads' | 'help';
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
        :aria-selected="activeTab === 'threads'"
        class="px-4 py-1.5 border-r border-base-300"
        :class="activeTab === 'threads' ? 'bg-base-100 font-semibold' : 'opacity-70 hover:bg-base-100/40'"
        @click="activeTab = 'threads'"
      >{{ $t('cortexThreads.tab') }}</button>
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
          v-if="sessionId"
          :session-id="sessionId"
          :project-id="projectId"
          :tool-service="toolService ?? null"
          :bound-document-id="boundDocumentId ?? null"
          :app-selection="appSelection ?? null"
        />
        <SessionPickerPanel
          v-else
          :project-id="projectId"
          @open-session="(id: string) => emit('open-session', id)"
        />
      </div>
      <div v-show="activeTab === 'threads'" class="h-full">
        <CortexThreadsPanel :active-document="activeDocument" />
      </div>
      <div v-show="activeTab === 'help'" class="h-full">
        <CortexHelpPanel :help-path="helpPath" />
      </div>
    </div>
  </div>
</template>
