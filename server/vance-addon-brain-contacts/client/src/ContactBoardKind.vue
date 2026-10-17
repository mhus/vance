<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { VueDraggable } from 'vue-draggable-plus';
import { VAlert, VEmptyState, VSegment, useConfirm, useDocumentPrefixReaction } from '@vance/components';
import ContactBrowser from './ContactBrowser.vue';
import ContactPinCard from './ContactPinCard.vue';
import { loadBoard, pinContact, reorderPins, unpinContact, updatePin } from './api';
import { useT } from './i18n';
import type { ContactBoardView } from './generated/contacts/ContactBoardView';
import type { ContactPinView } from './generated/contacts/ContactPinView';
import type { ContactScope } from './generated/contacts/ContactScope';

/**
 * A `kind: contact-board` document. First tab: the pinned contacts as cards,
 * each with heading and note, reorderable by drag. Second tab: the address
 * book, where contacts are pinned. Every change is one REST call that answers
 * with the whole board; remote saves of the document reload it.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string };
}>();

const t = useT();
const confirmAction = useConfirm();

type Tab = 'pinned' | 'contacts';
const tab = ref<Tab>('pinned');
const board = ref<ContactBoardView | null>(null);
const pins = ref<ContactPinView[]>([]);
const error = ref('');
const notice = ref('');
const browser = ref<InstanceType<typeof ContactBrowser> | null>(null);

const tabs = computed(() => [
  { value: 'pinned' as Tab, label: t('contacts.board.pinned') },
  { value: 'contacts' as Tab, label: t('contacts.board.contacts') },
]);

const pinnedIds = computed(() => pins.value.map((p) => p.uuid));

function show(view: ContactBoardView): void {
  board.value = view;
  pins.value = [...view.pins];
  if (view.warning) notice.value = view.warning;
}

async function run(call: () => Promise<ContactBoardView>): Promise<void> {
  error.value = '';
  try {
    show(await call());
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    await reload();
  }
}

async function reload(): Promise<void> {
  try {
    show(await loadBoard(props.document.projectId, props.document.path));
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

function onPin(contact: { id: string; scope: ContactScope }): void {
  notice.value = '';
  void run(() => pinContact(props.document.projectId, props.document.path, contact.id, contact.scope));
}

function onUpdate(pin: ContactPinView, change: { label?: string; note?: string }): void {
  void run(() => updatePin(props.document.projectId, props.document.path, pin.uuid, change));
}

async function onUnpin(pin: ContactPinView): Promise<void> {
  const ok = await confirmAction({
    message: t('contacts.board.confirmUnpin', { name: pin.contact?.displayName ?? pin.label ?? pin.uuid }),
    confirmLabel: t('contacts.board.unpin'),
    danger: true,
  });
  if (ok) void run(() => unpinContact(props.document.projectId, props.document.path, pin.uuid));
}

function onReorder(): void {
  const order = pins.value.map((p) => p.uuid);
  void run(() => reorderPins(props.document.projectId, props.document.path, order));
}

async function onDetails(pin: ContactPinView): Promise<void> {
  if (!pin.contact) return;
  tab.value = 'contacts';
  // The browser mounts with the tab; its ref exists after the next render.
  await nextTick();
  await browser.value?.open(pin.contact.id, pin.contact.scope);
}

useDocumentPrefixReaction({
  prefix: computed(() => props.document.path),
  debounceMs: 150,
  onRemoteChange: reload,
});

onMounted(reload);
watch(() => [props.document.projectId, props.document.path], reload);
</script>

<template>
  <div class="flex flex-col gap-4 p-4">
    <div class="flex items-center gap-4">
      <h2 v-if="board?.title" class="text-lg font-semibold">{{ board.title }}</h2>
      <VSegment v-model="tab" :options="tabs" :aria-label="t('contacts.board.tab')" />
    </div>
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="warning">{{ notice }}</VAlert>

    <template v-if="tab === 'pinned'">
      <p v-if="board?.description" class="text-sm text-(--v-label-secondary)">{{ board.description }}</p>
      <VueDraggable
        v-if="pins.length"
        v-model="pins"
        handle=".pin-handle"
        :animation="150"
        class="grid grid-cols-1 lg:grid-cols-2 gap-3 max-w-5xl"
        @end="onReorder"
      >
        <ContactPinCard
          v-for="pin in pins"
          :key="pin.uuid"
          :pin="pin"
          :project-id="document.projectId"
          :editable="true"
          @update="(change) => onUpdate(pin, change)"
          @unpin="onUnpin(pin)"
          @details="onDetails(pin)"
        />
      </VueDraggable>
      <VEmptyState v-else-if="board" :headline="t('contacts.board.empty')" :body="t('contacts.board.emptyBody')" />
    </template>

    <ContactBrowser
      v-else
      ref="browser"
      :embedded-project-id="document.projectId"
      :pinned-ids="pinnedIds"
      @pin="onPin"
    />
  </div>
</template>
