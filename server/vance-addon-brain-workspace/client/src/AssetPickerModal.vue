<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { brainFetch, documentContentUrl } from '@vance/shared';

/**
 * Modal that lists existing image assets under a folder and lets the
 * user pick one for insertion. The "Upload new" button delegates to
 * the same uploadImage callback that drag-drop uses so the two paths
 * stay consistent.
 */
const props = defineProps<{
  projectId: string;
  /** Workspace folder root — assets live under `<folder>/assets/`. */
  workspaceFolder: string;
  uploadImage: (file: File) => Promise<string | null>;
}>();

const emit = defineEmits<{
  (e: 'pick', src: string, alt: string): void;
  (e: 'close'): void;
}>();

interface AssetItem {
  id: string;
  path: string;
  name: string;
  mimeType: string | null;
  size: number | { low?: number };
}

const assets = ref<AssetItem[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const uploading = ref(false);
const fileInputRef = ref<HTMLInputElement | null>(null);

const assetsFolder = computed(() => `${props.workspaceFolder}/assets/`);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const resp = await brainFetch<{ items: AssetItem[] }>(
      'GET',
      `documents?projectId=${encodeURIComponent(props.projectId)}` +
        `&pathPrefix=${encodeURIComponent(assetsFolder.value)}` +
        `&size=200`,
    );
    assets.value = (resp.items ?? []).filter((i) =>
      (i.mimeType ?? '').startsWith('image/'),
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load assets.';
    assets.value = [];
  } finally {
    loading.value = false;
  }
}

function pick(asset: AssetItem) {
  emit('pick', documentContentUrl(asset.id), asset.name);
}

function close() {
  emit('close');
}

function openFileBrowser() {
  fileInputRef.value?.click();
}

async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  uploading.value = true;
  try {
    const url = await props.uploadImage(file);
    if (url) emit('pick', url, file.name);
  } finally {
    uploading.value = false;
  }
}

function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

onMounted(() => load());
</script>

<template>
  <div class="asset-picker" @click="onBackdrop">
    <div class="asset-picker__panel">
      <header class="asset-picker__header">
        <span>Insert image</span>
        <button class="asset-picker__close" type="button" @click="close">×</button>
      </header>

      <div class="asset-picker__actions">
        <button
          type="button"
          class="asset-picker__upload-btn"
          :disabled="uploading"
          @click="openFileBrowser"
        >
          {{ uploading ? 'Uploading…' : '⤴ Upload new' }}
        </button>
        <input
          ref="fileInputRef"
          type="file"
          accept="image/*"
          class="asset-picker__file-input"
          @change="onFileSelected"
        />
      </div>

      <div v-if="error" class="asset-picker__error">{{ error }}</div>

      <div v-if="loading" class="asset-picker__loading">Lade Assets…</div>

      <div
        v-else-if="assets.length === 0"
        class="asset-picker__empty"
      >
        Noch keine Bilder unter <code>{{ assetsFolder }}</code>.
      </div>

      <div v-else class="asset-picker__grid">
        <button
          v-for="a in assets"
          :key="a.id"
          type="button"
          class="asset-picker__item"
          :title="a.path"
          @click="pick(a)"
        >
          <img
            :src="documentContentUrl(a.id)"
            :alt="a.name"
            class="asset-picker__thumb"
          />
          <div class="asset-picker__item-name">{{ a.name }}</div>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.asset-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.asset-picker__panel {
  background: var(--color-bg, #fff);
  border-radius: 0.5rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  width: 100%;
  max-width: 56rem;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.asset-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.asset-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: var(--color-text-muted, #6b7280);
  padding: 0 0.25rem;
}
.asset-picker__actions {
  display: flex;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.asset-picker__upload-btn {
  padding: 0.3rem 0.8rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-button-bg, #fff);
  cursor: pointer;
}
.asset-picker__upload-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.asset-picker__file-input { display: none; }
.asset-picker__error {
  background: #fef2f2;
  color: #991b1b;
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.asset-picker__loading,
.asset-picker__empty {
  padding: 2rem;
  color: var(--color-text-muted, #6b7280);
  text-align: center;
  font-size: 0.9rem;
}
.asset-picker__empty code {
  font-family: monospace;
  font-size: 0.85em;
}
.asset-picker__grid {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(8rem, 1fr));
  gap: 0.75rem;
}
.asset-picker__item {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.375rem;
  background: var(--color-button-bg, #fafafa);
  cursor: pointer;
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  text-align: left;
  transition: box-shadow 0.15s ease;
}
.asset-picker__item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}
.asset-picker__thumb {
  width: 100%;
  height: 7rem;
  object-fit: cover;
  background: #f3f4f6;
}
.asset-picker__item-name {
  padding: 0.4rem 0.5rem;
  font-size: 0.75rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--color-text-muted, #6b7280);
  font-family: monospace;
}
</style>
