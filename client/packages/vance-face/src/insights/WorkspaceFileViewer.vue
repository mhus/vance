<script setup lang="ts">
import { computed } from 'vue';
import { CodeEditor, MarkdownView, VAlert, VButton, VEmptyState } from '@/components';
import type { FileLoadResult } from '@/composables/useWorkspaceFile';

interface Props {
  /** Selected file's display name + path — for the header. */
  name: string | null;
  path: string | null;
  loading: boolean;
  error: string | null;
  result: FileLoadResult | null;
}

const props = defineProps<Props>();

const showHeader = computed(() => props.name !== null);
</script>

<template>
  <div class="flex flex-col h-full min-h-0">
    <div v-if="showHeader" class="px-4 py-2 border-b border-base-300 bg-base-200/50 flex items-center gap-2">
      <span class="text-sm font-medium truncate">{{ name }}</span>
      <span class="text-xs opacity-60 truncate">{{ path }}</span>
    </div>

    <div class="flex-1 min-h-0 overflow-auto">
      <div v-if="loading" class="h-full flex items-center justify-center gap-2 text-sm opacity-70">
        <span class="loading loading-spinner loading-sm" />
        <span>{{ $t('workspace.loadingFile') }}</span>
      </div>

      <div v-else-if="error" class="p-4">
        <VAlert variant="error">{{ error }}</VAlert>
      </div>

      <VEmptyState
        v-else-if="!result"
        :headline="$t('workspace.preview.pickFileHeadline')"
        :body="$t('workspace.preview.pickFileBody')"
      />

      <template v-else>
        <MarkdownView
          v-if="result.mode === 'markdown' && result.text !== null"
          :source="result.text"
          class="p-4"
        />

        <CodeEditor
          v-else-if="result.mode === 'text' && result.text !== null"
          :model-value="result.text"
          :mime-type="result.mimeType"
          :rows="40"
          disabled
        />

        <div v-else-if="result.mode === 'image'" class="p-4 flex items-center justify-center">
          <img
            :src="result.url"
            :alt="name ?? ''"
            class="max-w-full max-h-full object-contain bg-base-200 rounded"
          />
        </div>

        <div v-else class="p-6 flex flex-col items-center gap-3">
          <VEmptyState
            :headline="$t('workspace.preview.binaryHeadline')"
            :body="$t('workspace.preview.binaryBody')"
          />
          <VButton variant="primary" :href="result.url">
            {{ $t('workspace.preview.download') }}
          </VButton>
        </div>
      </template>
    </div>
  </div>
</template>
