<script setup lang="ts">
import { computed } from 'vue';
import type {
  ActiveAppContext,
  BoundDocSelection,
  DocumentDto,
} from '@vance/generated';
import ChatSidePanel from '@/chat/ChatSidePanel.vue';
import type { ComposerCurrentFileSource } from '@/chat/ChatComposer.vue';
import { useCortexStore } from '../stores/cortexStore';
import { useViewEditMode } from '../useViewEditMode';
import type { CortexClientToolService } from '../clientToolService';
import { navigateTo } from '@/platform/navigate';

/**
 * Cortex's half of the chat side panel: everything the conversation should
 * know about the document the user is looking at.
 *
 * <p>The session bind, the takeover states and the ChatView/ChatComposer pair
 * live in {@code ChatSidePanel} — shared with the inbox, which has no
 * documents and therefore passes none of this. What is left here is exactly
 * the Cortex-store reads.
 */

interface Props {
  sessionId: string;
  projectId: string;
  /**
   * Owned by the parent app — single instance for the lifetime of the
   * Cortex view.
   */
  toolService?: CortexClientToolService | null;
  /**
   * Document currently bound to the chat (the `bind file` affordance,
   * owned by EditorApp).
   */
  boundDocumentId?: string | null;
  /**
   * An app-owned structured selection ({@code appDocId} = the app tab's doc
   * id, {@code selection} = a freeform hint like a canvas board's selected
   * node ids). Folded into the `activeApp` context when it belongs to the
   * active app tab — the char-range `boundDocSelection` can't express it.
   */
  appSelection?: { appDocId: string; selection: string } | null;
}

const props = defineProps<Props>();

const cortexStore = useCortexStore();

/**
 * Surfaces the Cortex active tab as a one-click chat attachment. Reactive,
 * so the composer's dropdown label always reflects what the user is
 * currently looking at in the main editor. {@code null} when no tab is
 * open (Cortex starts blank for fresh sessions) — the composer then
 * falls back to the plain native file-picker UX.
 */
const currentFileSource = computed<ComposerCurrentFileSource | null>(() => {
  const tab = cortexStore.activeTab;
  if (!tab) return null;
  return { documentId: tab.id, label: tab.path };
});

/**
 * Per-turn text-selection hint forwarded to the brain via
 * {@code ProcessSteerRequest.boundDocSelection}. Only surfaced when the
 * user's current selection lives inside the document that is actually
 * bound to the chat this turn — otherwise the range would point into a
 * file the agent isn't being shown. Only the {@code from}/{@code to}
 * range travels; the model reads the selected text on demand via
 * {@code doc_get_selection}.
 */
const boundDocSelection = computed<BoundDocSelection | null>(() => {
  const boundId = props.boundDocumentId ?? null;
  if (!boundId) return null;
  const sel = cortexStore.currentSelection;
  if (!sel || sel.docId !== boundId) return null;
  if (sel.from === sel.to) return null;
  return { from: sel.from, to: sel.to };
});

const viewEditMode = useViewEditMode();

/**
 * Per-turn active-app hint forwarded to the brain via
 * {@code ProcessSteerRequest.activeApp}. Derived from the visible
 * Cortex tab — when its kind is {@code application} and the manifest
 * carries an {@code app:} discriminator, the brain renders an
 * app-context block in the engine prompt and asks the app's
 * {@code VanceApplication.promptInject(...)} for dynamic content.
 */
const activeApp = computed<ActiveAppContext | null>(() => {
  // Only forward the hint when the tab is actually running the app
  // ("App" mode). In "Edit" mode the user is editing the raw _app.yaml
  // manifest, not operating the app, so the app-context block would be
  // misleading. (App mode only exists for application docs, so the
  // kind check below is the precondition; the mode is the deciding one.)
  if (viewEditMode.value !== 'view') return null;
  const tab = cortexStore.activeTab;
  if (!tab) return null;
  if ((tab.kind ?? '').toLowerCase() !== 'application') return null;
  const app = tab.headers?.app;
  if (!app || typeof app !== 'string' || app.trim() === '') return null;
  const folder = tab.path.replace(/\/_app\.yaml$/, '');
  if (!folder) return null;
  // An app-owned selection (e.g. canvas node ids) rides along when it belongs
  // to this app tab — carried on activeApp.selection (not boundDocSelection).
  const selection = props.appSelection && props.appSelection.appDocId === tab.id
    ? props.appSelection.selection
    : undefined;
  return { folder, app, selection };
});

function onLeave(): void {
  // ChatView emits 'leave' when the user archives/deletes the session
  // via SessionHeader. Bounce back to /chat so they can pick a
  // different session — Cortex without a session has nothing to do.
  navigateTo('/chat');
}

/**
 * Open the freshly-saved conversation-export document as a Cortex tab so
 * the user can rename/move it without leaving the editor. The chat-side
 * banner (rendered inside ChatView) still shows the success path; this
 * handler just adds the "open it" affordance that's unique to Cortex.
 */
async function onConversationExported(
  payload: { documentId: string; document: DocumentDto },
): Promise<void> {
  try {
    await cortexStore.openFile(payload.documentId);
  } catch (e) {
    console.warn('Failed to open exported conversation in Cortex', e);
  }
}
</script>

<template>
  <ChatSidePanel
    :session-id="sessionId"
    :project-id="projectId"
    :tool-service="toolService ?? null"
    :bound-document-id="boundDocumentId ?? null"
    :bound-doc-selection="boundDocSelection"
    :active-app="activeApp"
    :current-file-source="currentFileSource"
    :draft-key="`cortex:${sessionId}`"
    @leave="onLeave"
    @conversation-exported="onConversationExported"
  />
</template>
