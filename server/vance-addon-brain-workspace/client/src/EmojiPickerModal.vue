<script setup lang="ts">
import { onMounted, ref } from 'vue';
// Side-effect import: registers the <emoji-picker> custom element.
// The element lazy-loads its emoji database on first render.
import 'emoji-picker-element';

const emit = defineEmits<{
  (e: 'pick', unicode: string): void;
  (e: 'remove'): void;
  (e: 'close'): void;
}>();

defineProps<{ hasCurrent?: boolean }>();

const pickerRef = ref<HTMLElement | null>(null);

interface EmojiClickDetail {
  unicode: string;
}

onMounted(() => {
  const el = pickerRef.value;
  if (!el) return;
  el.addEventListener('emoji-click', (event) => {
    const detail = (event as CustomEvent<EmojiClickDetail>).detail;
    if (detail?.unicode) emit('pick', detail.unicode);
  });
});

function onBackdropClick(e: MouseEvent) {
  if (e.target === e.currentTarget) emit('close');
}
</script>

<template>
  <div class="emoji-modal-backdrop" @click="onBackdropClick">
    <div class="emoji-modal" @click.stop>
      <div class="emoji-modal__header">
        <span class="emoji-modal__title">Pick an icon</span>
        <button class="emoji-modal__close" @click="emit('close')">×</button>
      </div>
      <emoji-picker ref="pickerRef" class="emoji-modal__picker" />
      <div v-if="hasCurrent" class="emoji-modal__footer">
        <button class="emoji-modal__remove" @click="emit('remove')">Remove icon</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.emoji-modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.emoji-modal {
  background: var(--color-bg, #fff);
  border-radius: 8px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
  display: flex;
  flex-direction: column;
  max-width: 360px;
  width: 100%;
}
.emoji-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.emoji-modal__title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--color-text, #111827);
}
.emoji-modal__close {
  background: transparent;
  border: 0;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: var(--color-text-muted, #6b7280);
}
.emoji-modal__close:hover { color: var(--color-text, #111827); }
.emoji-modal__picker {
  /* The element exposes its width/height via host CSS. */
  width: 100%;
  height: 26rem;
}
.emoji-modal__footer {
  padding: 0.5rem 0.75rem;
  border-top: 1px solid var(--color-border, #e5e7eb);
  display: flex;
  justify-content: flex-end;
}
.emoji-modal__remove {
  background: transparent;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 4px;
  padding: 0.25rem 0.75rem;
  font-size: 0.85rem;
  cursor: pointer;
  color: var(--color-text, #111827);
}
.emoji-modal__remove:hover { background: var(--color-button-bg, #f3f4f6); }
</style>
