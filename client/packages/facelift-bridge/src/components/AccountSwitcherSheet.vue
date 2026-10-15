<script setup lang="ts">
import { VButton, VGroup, VGroupRow, VIcon } from '@vance/components';
import type { Account } from '@/accounts/accountStore';

defineProps<{
  accounts: Account[];
  activeId: string | null;
}>();

const emit = defineEmits<{
  (e: 'select', id: string): void;
  (e: 'manage'): void;
  (e: 'add'): void;
  (e: 'close'): void;
}>();
</script>

<template>
  <!-- Scrim: no token exists for it, so a neutral black at 40 % — the same
       in light and dark, like the native sheets it imitates. -->
  <div
    class="fixed inset-0 z-50 flex flex-col justify-end bg-(--v-scrim)"
    @click.self="emit('close')"
  >
    <!-- A floating layer (corporate-design §7.1): raised surface, the only
         place with a shadow. -->
    <div
      class="switcher-sheet rounded-t-(--v-radius-modal) bg-(--v-surface-raised) text-(--v-label) shadow-(--v-shadow-float)"
      style="padding-bottom: max(1rem, env(safe-area-inset-bottom))"
    >
      <div class="flex justify-center pt-2">
        <span class="h-1 w-10 rounded-full bg-(--v-fill)"></span>
      </div>
      <h2 class="px-4 pt-3 pb-3 text-[17px] leading-[22px] font-semibold">
        Switch account
      </h2>
      <div class="max-h-[60vh] overflow-y-auto px-4">
        <VGroup>
          <VGroupRow
            v-for="a in accounts"
            :key="a.id"
            :label="a.displayName"
            :description="a.faceUrl"
            navigates
            @click="emit('select', a.id)"
          >
            <VIcon v-if="a.id === activeId" name="check" class="text-(--v-accent-text)" />
          </VGroupRow>
        </VGroup>
      </div>
      <div class="flex gap-2 px-4 pt-4">
        <VButton variant="secondary" icon="add" class="flex-1" @click="emit('add')">
          Add account
        </VButton>
        <VButton variant="secondary" class="flex-1" @click="emit('manage')">
          Manage
        </VButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* The sheet is already a white (raised) ground — like inside VModal, the
   group needs its hairline to stay a group. */
.switcher-sheet :deep(.v-group) { box-shadow: inset 0 0 0 1px var(--v-separator); }
</style>
