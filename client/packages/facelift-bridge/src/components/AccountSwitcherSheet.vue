<script setup lang="ts">
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
  <div
    class="fixed inset-0 z-50 flex flex-col justify-end bg-black/60"
    @click.self="emit('close')"
  >
    <div
      class="rounded-t-2xl bg-gray-900 pb-4 shadow-2xl"
      style="padding-bottom: max(1rem, env(safe-area-inset-bottom))"
    >
      <div class="flex justify-center pt-2">
        <span class="h-1 w-10 rounded-full bg-gray-700"></span>
      </div>
      <h2 class="px-4 py-3 text-sm font-semibold uppercase tracking-wide text-gray-400">
        Switch account
      </h2>
      <ul class="max-h-[60vh] overflow-y-auto">
        <li
          v-for="a in accounts"
          :key="a.id"
          class="border-t border-gray-800"
        >
          <button
            type="button"
            class="flex w-full items-center justify-between px-4 py-3 text-left"
            :class="a.id === activeId ? 'bg-gray-800' : ''"
            @click="emit('select', a.id)"
          >
            <div class="min-w-0 flex-1">
              <p class="truncate font-medium">{{ a.displayName }}</p>
              <p class="mt-0.5 truncate text-xs text-gray-500">{{ a.faceUrl }}</p>
            </div>
            <span v-if="a.id === activeId" class="ml-3 text-blue-400">●</span>
          </button>
        </li>
      </ul>
      <div class="flex gap-2 border-t border-gray-800 px-4 pt-3">
        <button
          type="button"
          class="flex-1 rounded border border-gray-700 px-3 py-2 text-sm"
          @click="emit('add')"
        >
          Add account
        </button>
        <button
          type="button"
          class="flex-1 rounded border border-gray-700 px-3 py-2 text-sm"
          @click="emit('manage')"
        >
          Manage
        </button>
      </div>
    </div>
  </div>
</template>
