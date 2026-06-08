<script setup lang="ts">
defineProps<{
  /** Suggestion text to render, or {@code null} to hide the bubble. */
  suggestion: string | null;
}>();

const emit = defineEmits<{
  /** User clicked the ghost bubble — same effect as Space in the composer. */
  (e: 'accept'): void;
}>();

function onClick(): void {
  emit('accept');
}
</script>

<template>
  <div v-if="suggestion" class="flex justify-start">
    <button
      type="button"
      class="max-w-[85%] rounded-2xl px-4 py-2 text-sm italic
             border border-dashed border-base-300 bg-base-200/40
             text-base-content/60 hover:bg-base-200/70 hover:text-base-content/80
             transition cursor-pointer text-left"
      :title="$t('chat.followUp.acceptTooltip')"
      @click="onClick"
    >
      <span class="opacity-70 mr-2 select-none" aria-hidden="true">↹</span>
      <span>{{ suggestion }}</span>
      <span class="ml-2 opacity-50 text-xs select-none" aria-hidden="true">
        {{ $t('chat.followUp.acceptHint') }}
      </span>
    </button>
  </div>
</template>
