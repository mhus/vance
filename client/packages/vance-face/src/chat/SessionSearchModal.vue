<script setup lang="ts">
import {
  SessionSearchScope,
  SessionStatus,
  type ChatMessageDto,
  type SessionSearchHitDto,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { getSessionMessages, searchSessions } from '@vance/shared';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  MarkdownView,
  VAlert,
  VButton,
  VCheckbox,
  VEmptyState,
  VModal,
  VSelect,
} from '../components/index';

const { t } = useI18n();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'pick', sessionId: string): void;
}>();

const open = ref(true);
const query = ref('');
const scope = ref<SessionSearchScope>(SessionSearchScope.BOTH);
const includeArchived = ref(true);
const hits = ref<SessionSearchHitDto[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const searched = ref(false);

// Preview-modal state.
const previewSessionId = ref<string | null>(null);
const previewSession = ref<SessionSummaryRichDto | null>(null);
const previewMessages = ref<ChatMessageDto[]>([]);
const previewLoading = ref(false);
const previewError = ref<string | null>(null);

const metadataHits = computed(() =>
  hits.value.filter((h) => h.matchedIn === SessionSearchScope.METADATA),
);
const contentHits = computed(() =>
  hits.value.filter((h) => h.matchedIn === SessionSearchScope.CONTENT),
);

let debounceTimer: ReturnType<typeof setTimeout> | null = null;

watch([query, scope, includeArchived], () => {
  if (debounceTimer) clearTimeout(debounceTimer);
  const q = query.value.trim();
  if (q.length === 0) {
    hits.value = [];
    searched.value = false;
    return;
  }
  debounceTimer = setTimeout(() => void runSearch(), 220);
});

async function runSearch(): Promise<void> {
  const q = query.value.trim();
  if (q.length === 0) return;
  loading.value = true;
  error.value = null;
  try {
    hits.value = await searchSessions({
      q,
      scope: scope.value,
      includeArchived: includeArchived.value,
      limit: 50,
    });
    searched.value = true;
  } catch (e) {
    error.value = t('chat.search.failed') + ' ' + (e as Error).message;
    hits.value = [];
  } finally {
    loading.value = false;
  }
}

function close(): void {
  open.value = false;
  emit('close');
}

function onModalChange(value: boolean): void {
  open.value = value;
  if (!value) emit('close');
}

function pick(hit: SessionSearchHitDto): void {
  emit('pick', hit.session.sessionId);
}

async function showPreview(hit: SessionSearchHitDto): Promise<void> {
  previewSession.value = hit.session;
  previewSessionId.value = hit.session.sessionId;
  previewLoading.value = true;
  previewError.value = null;
  previewMessages.value = [];
  try {
    previewMessages.value = await getSessionMessages(hit.session.sessionId, 200);
  } catch (e) {
    previewError.value =
      t('chat.search.previewError') + ' ' + (e as Error).message;
  } finally {
    previewLoading.value = false;
  }
}

function closePreview(): void {
  previewSessionId.value = null;
  previewSession.value = null;
  previewMessages.value = [];
  previewError.value = null;
}

function scopeLabel(value: SessionSearchScope): string {
  switch (value) {
    case SessionSearchScope.METADATA: return t('chat.search.scopeMetadata');
    case SessionSearchScope.CONTENT: return t('chat.search.scopeContent');
    case SessionSearchScope.BOTH:
    default: return t('chat.search.scopeBoth');
  }
}

const SCOPE_OPTIONS = [
  { label: scopeLabel(SessionSearchScope.BOTH), value: SessionSearchScope.BOTH },
  { label: scopeLabel(SessionSearchScope.METADATA), value: SessionSearchScope.METADATA },
  { label: scopeLabel(SessionSearchScope.CONTENT), value: SessionSearchScope.CONTENT },
];

function sessionTitle(session: SessionSummaryRichDto): string {
  if (session.title && session.title.trim().length > 0) return session.title;
  if (session.firstUserMessage && session.firstUserMessage.trim().length > 0) {
    return session.firstUserMessage;
  }
  return t('chat.sessionHeader.untitled');
}

function isArchived(session: SessionSummaryRichDto): boolean {
  return session.status === SessionStatus.ARCHIVED;
}

function previewTimestamp(value: Date | string | number | undefined): string {
  if (value === undefined || value === null) return '';
  const d = value instanceof Date ? value : new Date(value as string | number);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString();
}

// Focus the input on first render — keyboard-driven dialog UX.
const inputRef = ref<HTMLInputElement | null>(null);
onMounted(() => {
  setTimeout(() => inputRef.value?.focus(), 0);
});
onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer);
});
</script>

<template>
  <VModal
    :model-value="open"
    :title="t('chat.search.title')"
    @update:model-value="onModalChange"
  >
    <div class="flex flex-col gap-3">
      <div class="flex items-center gap-2">
        <input
          ref="inputRef"
          v-model="query"
          type="search"
          class="input input-bordered flex-1"
          :placeholder="t('chat.search.placeholder')"
          @keyup.enter="runSearch"
        />
        <VSelect
          v-model="scope"
          :options="SCOPE_OPTIONS"
        />
      </div>
      <VCheckbox
        v-model="includeArchived"
        :label="t('chat.search.includeArchived')"
      />

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="loading" class="text-sm opacity-60">
        {{ t('chat.picker.loading') }}
      </div>

      <VEmptyState
        v-else-if="!searched && !loading"
        :headline="t('chat.search.empty')"
        :body="t('chat.search.emptyBody')"
      />

      <VEmptyState
        v-else-if="searched && hits.length === 0"
        :headline="t('chat.search.noResults')"
        :body="t('chat.search.noResultsBody')"
      />

      <div v-else class="flex flex-col gap-4 max-h-96 overflow-y-auto pr-1">
        <section v-if="metadataHits.length > 0" class="flex flex-col gap-2">
          <h4 class="text-xs uppercase tracking-wide opacity-60 font-semibold">
            {{ t('chat.search.headlineMetadata') }}
          </h4>
          <button
            v-for="hit in metadataHits"
            :key="`m-${hit.session.sessionId}`"
            type="button"
            class="text-left rounded border border-base-300 hover:border-primary p-3 flex items-start gap-3 bg-base-100"
            @click="pick(hit)"
          >
            <div class="text-xl shrink-0">
              <span v-if="hit.session.icon">{{ hit.session.icon }}</span>
              <span v-else class="opacity-30">💬</span>
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 min-w-0">
                <span class="font-medium truncate">{{ sessionTitle(hit.session) }}</span>
                <span
                  v-if="isArchived(hit.session)"
                  class="text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 shrink-0"
                >
                  {{ t('chat.sessionHeader.archived') }}
                </span>
              </div>
              <div
                v-if="hit.session.tags && hit.session.tags.length > 0"
                class="flex flex-wrap gap-1 mt-1"
              >
                <span
                  v-for="tag in hit.session.tags"
                  :key="tag"
                  class="text-[10px] px-1.5 py-0.5 rounded bg-base-200"
                >
                  {{ tag }}
                </span>
              </div>
              <div class="text-xs opacity-60 truncate mt-1">
                {{ hit.session.projectId }}
              </div>
            </div>
          </button>
        </section>

        <section v-if="contentHits.length > 0" class="flex flex-col gap-2">
          <h4 class="text-xs uppercase tracking-wide opacity-60 font-semibold">
            {{ t('chat.search.headlineContent') }}
          </h4>
          <div
            v-for="hit in contentHits"
            :key="`c-${hit.session.sessionId}-${hit.matchedMessageId ?? ''}`"
            class="rounded border border-base-300 p-3 bg-base-100"
          >
            <div class="flex items-start gap-3">
              <div class="text-xl shrink-0">
                <span v-if="hit.session.icon">{{ hit.session.icon }}</span>
                <span v-else class="opacity-30">💬</span>
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 min-w-0">
                  <span class="font-medium truncate">{{ sessionTitle(hit.session) }}</span>
                  <span
                    v-if="isArchived(hit.session)"
                    class="text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 shrink-0"
                  >
                    {{ t('chat.sessionHeader.archived') }}
                  </span>
                  <span
                    v-if="hit.matchedRole"
                    class="text-[10px] uppercase tracking-wide px-1 py-0.5 rounded bg-base-200 shrink-0"
                  >
                    {{ hit.matchedRole }}
                  </span>
                </div>
                <p
                  v-if="hit.snippet"
                  class="text-sm opacity-80 mt-1 line-clamp-3"
                >
                  {{ hit.snippet }}
                </p>
                <div class="text-xs opacity-60 mt-1">
                  {{ hit.session.projectId }}
                  <span v-if="hit.matchedAt"> · {{ previewTimestamp(hit.matchedAt) }}</span>
                </div>
              </div>
              <div class="shrink-0 flex flex-col gap-1">
                <VButton size="sm" variant="ghost" @click="showPreview(hit)">
                  {{ t('chat.search.preview') }}
                </VButton>
                <VButton size="sm" variant="primary" @click="pick(hit)">
                  {{ t('chat.search.open') }}
                </VButton>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>

    <template #actions>
      <VButton variant="ghost" @click="close">
        {{ t('chat.search.previewClose') }}
      </VButton>
    </template>
  </VModal>

  <!-- Read-only chat preview, layered on top -->
  <VModal
    :model-value="previewSessionId !== null"
    :title="previewSession ? sessionTitle(previewSession) : t('chat.search.preview')"
    @update:model-value="(v) => { if (!v) closePreview(); }"
  >
    <div v-if="previewLoading" class="text-sm opacity-60">
      {{ t('chat.search.previewLoading') }}
    </div>
    <VAlert v-else-if="previewError" variant="error">{{ previewError }}</VAlert>
    <div v-else class="flex flex-col gap-3 max-h-96 overflow-y-auto pr-1">
      <div
        v-for="msg in previewMessages"
        :key="msg.messageId ?? `${msg.createdAt}`"
        class="rounded border border-base-200 p-2"
      >
        <div class="text-[10px] uppercase tracking-wide opacity-60 mb-1">
          {{ msg.role }}
          <span v-if="msg.createdAt"> · {{ previewTimestamp(msg.createdAt) }}</span>
        </div>
        <MarkdownView :source="msg.content ?? ''" />
      </div>
      <div v-if="previewMessages.length === 0" class="text-sm opacity-60">
        {{ t('chat.picker.noSessionsBody') }}
      </div>
    </div>
    <template #actions>
      <VButton
        v-if="previewSession"
        variant="primary"
        @click="(previewSession && emit('pick', previewSession.sessionId)); closePreview();"
      >
        {{ t('chat.search.open') }}
      </VButton>
      <VButton variant="ghost" @click="closePreview">
        {{ t('chat.search.previewClose') }}
      </VButton>
    </template>
  </VModal>
</template>
