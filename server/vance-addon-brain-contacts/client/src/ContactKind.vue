<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { VAlert, VButton } from '@vance/components';
import ContactCardView from './ContactCardView.vue';
import { loadDocument } from './api';
import { useT } from './i18n';
import type { ContactDocumentView } from './generated/contacts/ContactDocumentView';

/**
 * Renders a `kind: contact` document: its own card, or the address-book card
 * a reference points to (with picture). The server resolves both — the
 * client parses no YAML.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string };
}>();

const t = useT();
const view = ref<ContactDocumentView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
/** Only the newest load may write — a slower answer for a previous document is dropped. */
let generation = 0;

async function load(): Promise<void> {
  const mine = ++generation;
  loading.value = true;
  error.value = null;
  try {
    const answer = await loadDocument(props.document.projectId, props.document.path);
    if (mine === generation) view.value = answer;
  } catch (e) {
    if (mine === generation) error.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (mine === generation) loading.value = false;
  }
}

function openInContacts(id: string, scope: string): void {
  const u = new URL('addon.html', window.location.href);
  u.searchParams.set('addon', 'contacts');
  u.searchParams.set('project', props.document.projectId);
  u.searchParams.set('contact', id);
  u.searchParams.set('scope', scope.toLowerCase());
  window.open(u.toString(), '_blank', 'noopener');
}

onMounted(load);
watch(() => [props.document.projectId, props.document.path], load);
</script>

<template>
  <div class="p-4 max-w-3xl">
    <VAlert v-if="error" variant="error">{{ t('contacts.doc.noAccess') }} {{ error }}</VAlert>
    <p v-else-if="loading" class="text-sm opacity-60">{{ t('contacts.common.loading') }}</p>
    <VAlert v-else-if="view && view.dangling" variant="warning">{{ t('contacts.doc.dangling') }} ({{ view.refId }})</VAlert>
    <template v-else-if="view && view.card">
      <ContactCardView
        :card="view.card"
        :project-id="document.projectId"
        :id="view.entry?.id ?? null"
        :scope="view.entry?.scope ?? null"
        :has-photo="view.entry?.hasPhoto ?? false"
        :photo-version="view.entry?.photoVersion ?? null"
      />
      <div v-if="view.entry" class="mt-4">
        <VButton variant="secondary" icon="external" @click="openInContacts(view.entry.id, view.entry.scope)">
          {{ t('contacts.detail.openInContacts') }}
        </VButton>
      </div>
    </template>
  </div>
</template>
