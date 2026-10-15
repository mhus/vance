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
  VGroup,
  VGroupRow,
  VIconButton,
  VInput,
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
const inputRef = ref<InstanceType<typeof VInput> | null>(null);
onMounted(() => {
  setTimeout(() => inputRef.value?.input?.focus(), 0);
});

/** Second line of a metadata hit: project, then the session's tags. */
function metadataLine(session: SessionSummaryRichDto): string {
  const tags = (session.tags ?? []).map((tag) => `#${tag}`);
  return [session.projectId, ...tags].filter((part) => !!part).join(' · ');
}

/** Right-hand context of a content hit: project and when the match was said. */
function contentHitContext(hit: SessionSearchHitDto): string {
  const when = hit.matchedAt ? previewTimestamp(hit.matchedAt) : '';
  return [hit.session.projectId, when].filter((part) => !!part).join(' · ');
}

function openPreviewed(): void {
  const session = previewSession.value;
  if (session) emit('pick', session.sessionId);
  closePreview();
}
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
    <div class="flex flex-col gap-4">
      <div class="flex items-center gap-2">
        <div class="flex-1 min-w-0">
          <VInput
            ref="inputRef"
            v-model="query"
            :placeholder="t('chat.search.placeholder')"
            @keyup.enter="runSearch"
          />
        </div>
        <div class="w-40 shrink-0">
          <VSelect v-model="scope" :options="SCOPE_OPTIONS" />
        </div>
      </div>
      <VCheckbox
        v-model="includeArchived"
        :label="t('chat.search.includeArchived')"
      />

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="loading" class="text-sm text-(--v-label-secondary)">
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

      <template v-else>
        <VGroup v-if="metadataHits.length > 0" :label="t('chat.search.headlineMetadata')">
          <VGroupRow
            v-for="hit in metadataHits"
            :key="`m-${hit.session.sessionId}`"
            navigates
            :description="metadataLine(hit.session)"
            @click="pick(hit)"
          >
            <template #label>
              <span class="flex items-center gap-2 min-w-0">
                <span v-if="hit.session.icon" class="shrink-0">{{ hit.session.icon }}</span>
                <span class="truncate">{{ sessionTitle(hit.session) }}</span>
                <span
                  v-if="isArchived(hit.session)"
                  class="text-xs shrink-0 text-(--v-warning)"
                >{{ t('chat.sessionHeader.archived') }}</span>
              </span>
            </template>
          </VGroupRow>
        </VGroup>

        <VGroup v-if="contentHits.length > 0" :label="t('chat.search.headlineContent')">
          <VGroupRow
            v-for="hit in contentHits"
            :key="`c-${hit.session.sessionId}-${hit.matchedMessageId ?? ''}`"
            navigates
            :meta="hit.matchedRole ?? undefined"
            :description="hit.snippet ?? undefined"
            :value="contentHitContext(hit)"
            @click="pick(hit)"
          >
            <template #label>
              <span class="flex items-center gap-2 min-w-0">
                <span v-if="hit.session.icon" class="shrink-0">{{ hit.session.icon }}</span>
                <span class="truncate">{{ sessionTitle(hit.session) }}</span>
                <span
                  v-if="isArchived(hit.session)"
                  class="text-xs shrink-0 text-(--v-warning)"
                >{{ t('chat.sessionHeader.archived') }}</span>
              </span>
            </template>
            <VIconButton
              icon="show"
              size="sm"
              :label="t('chat.search.preview')"
              @click.stop="showPreview(hit)"
            />
          </VGroupRow>
        </VGroup>
      </template>
    </div>

    <template #actions>
      <VButton variant="secondary" @click="close">
        {{ t('chat.search.previewClose') }}
      </VButton>
    </template>
  </VModal>

  <!-- Read-only chat preview, layered on top -->
  <VModal
    :model-value="previewSessionId !== null"
    :title="previewSession ? sessionTitle(previewSession) : t('chat.search.preview')"
    size="lg"
    @update:model-value="(v) => { if (!v) closePreview(); }"
  >
    <div v-if="previewLoading" class="text-sm text-(--v-label-secondary)">
      {{ t('chat.search.previewLoading') }}
    </div>
    <VAlert v-else-if="previewError" variant="error">{{ previewError }}</VAlert>
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="msg in previewMessages"
        :key="msg.messageId ?? `${msg.createdAt}`"
        class="rounded-md border border-(--v-separator) p-2"
      >
        <div class="text-[10px] uppercase tracking-wide text-(--v-label-secondary) mb-1">
          {{ msg.role }}
          <span v-if="msg.createdAt"> · {{ previewTimestamp(msg.createdAt) }}</span>
        </div>
        <MarkdownView :source="msg.content ?? ''" />
      </div>
      <div v-if="previewMessages.length === 0" class="text-sm text-(--v-label-secondary)">
        {{ t('chat.picker.noSessionsBody') }}
      </div>
    </div>
    <template #actions>
      <VButton variant="secondary" @click="closePreview">
        {{ t('chat.search.previewClose') }}
      </VButton>
      <VButton v-if="previewSession" variant="primary" @click="openPreviewed">
        {{ t('chat.search.open') }}
      </VButton>
    </template>
  </VModal>
</template>
