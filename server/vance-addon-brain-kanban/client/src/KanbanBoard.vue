<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VueDraggable } from 'vue-draggable-plus';
import {
  VAlert,
  VButton,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
} from '@vance/components';
import KanbanCardDetail from './KanbanCardDetail.vue';
import {
  createKanbanCard,
  deleteKanbanCard,
  getKanbanBoard,
  moveKanbanCard,
  rebuildKanbanBoard,
  updateKanbanCard,
} from './api';
import type { KanbanBoardView } from './generated/kanban/KanbanBoardView';
import type { KanbanCardCreateRequest } from './generated/kanban/KanbanCardCreateRequest';
import type { KanbanCardUpdateRequest } from './generated/kanban/KanbanCardUpdateRequest';
import type { KanbanCardView } from './generated/kanban/KanbanCardView';

const props = defineProps<{
  projectId: string;
  folder: string;
  title?: string;
}>();

const board = ref<KanbanBoardView | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);
const warnings = ref<string[]>([]);
const selectedCardPath = ref<string | null>(null);
const showCreateModal = ref(false);
const newCardForm = ref<KanbanCardCreateRequest>({
  title: '',
  column: '',
  labels: [],
  blocked: false,
});

const selectedCard = computed<KanbanCardView | null>(() =>
  board.value?.cards.find((c) => c.path === selectedCardPath.value) ?? null,
);

const cardsByColumn = computed<Record<string, KanbanCardView[]>>(() => {
  const out: Record<string, KanbanCardView[]> = {};
  if (!board.value) return out;
  for (const col of board.value.columns) out[col.name] = [];
  for (const card of board.value.cards) {
    if (!out[card.column]) out[card.column] = [];
    out[card.column].push(card);
  }
  // Sort each column: priority desc → dueDate asc → title asc
  for (const col of Object.keys(out)) {
    out[col].sort(compareCards);
  }
  return out;
});

function compareCards(a: KanbanCardView, b: KanbanCardView): number {
  const pa = priorityWeight(a.priority);
  const pb = priorityWeight(b.priority);
  if (pa !== pb) return pb - pa;
  const da = a.dueDate ?? '9999-99-99';
  const db = b.dueDate ?? '9999-99-99';
  if (da !== db) return da.localeCompare(db);
  return (a.title ?? '').localeCompare(b.title ?? '');
}

function priorityWeight(priority?: string | null): number {
  switch ((priority ?? '').toLowerCase()) {
    case 'critical': return 4;
    case 'high': return 3;
    case 'med':
    case 'medium':
    case 'normal': return 2;
    case 'low': return 0;
    default: return 1;
  }
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    board.value = await getKanbanBoard(props.projectId, props.folder);
  } catch (e) {
    error.value = `Could not load board: ${(e as Error).message}`;
  } finally {
    loading.value = false;
  }
}

async function refresh(): Promise<void> {
  warnings.value = [];
  try {
    await rebuildKanbanBoard(props.projectId, props.folder);
    await load();
  } catch (e) {
    error.value = `Rebuild failed: ${(e as Error).message}`;
  }
}

// Drag callback: vue-draggable-plus mutates the array model directly, so by
// the time `onEnd` fires the card is already in the new column slot. We just
// need to persist the move on the server.
async function onCardDropped(toColumn: string, card: KanbanCardView): Promise<void> {
  if (card.column === toColumn) return;
  const previousColumn = card.column;
  // Optimistic UI: update the card's column locally.
  card.column = toColumn;
  try {
    const response = await moveKanbanCard(props.projectId, props.folder, {
      card: card.path,
      toColumn,
    });
    card.path = response.card;
    warnings.value = response.warnings ?? [];
  } catch (e) {
    // Rollback on error.
    card.column = previousColumn;
    error.value = `Move failed: ${(e as Error).message}`;
  }
}

async function onCardUpdate(path: string, patch: KanbanCardUpdateRequest): Promise<void> {
  try {
    const updated = await updateKanbanCard(props.projectId, props.folder, path, patch);
    if (!board.value) return;
    const idx = board.value.cards.findIndex((c) => c.path === path);
    if (idx >= 0) board.value.cards[idx] = updated;
    selectedCardPath.value = updated.path;
  } catch (e) {
    error.value = `Update failed: ${(e as Error).message}`;
  }
}

async function onCardDelete(path: string): Promise<void> {
  try {
    await deleteKanbanCard(props.projectId, props.folder, path);
    if (board.value) {
      board.value.cards = board.value.cards.filter((c) => c.path !== path);
    }
    selectedCardPath.value = null;
  } catch (e) {
    error.value = `Delete failed: ${(e as Error).message}`;
  }
}

function openCreateModal(column: string): void {
  newCardForm.value = {
    title: '',
    column,
    labels: [],
    blocked: false,
  };
  showCreateModal.value = true;
}

async function submitCreate(): Promise<void> {
  if (!newCardForm.value.title.trim()) return;
  try {
    const created = await createKanbanCard(
      props.projectId,
      props.folder,
      newCardForm.value,
    );
    if (board.value) board.value.cards.push(created);
    showCreateModal.value = false;
    selectedCardPath.value = created.path;
  } catch (e) {
    error.value = `Create failed: ${(e as Error).message}`;
  }
}

function priorityClass(priority?: string | null): string {
  switch ((priority ?? '').toLowerCase()) {
    case 'critical': return 'border-l-4 border-error';
    case 'high': return 'border-l-4 border-warning';
    case 'med':
    case 'medium': return 'border-l-2 border-info';
    case 'low': return 'border-l-2 border-base-300';
    default: return 'border-l-2 border-base-300';
  }
}

onMounted(load);
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <div>
        <h1 class="text-xl font-semibold">{{ title ?? folder }}</h1>
        <div class="text-sm text-base-content/60 mt-0.5">{{ folder }}</div>
      </div>
      <div class="flex gap-2 items-center">
        <span v-if="board" class="text-sm text-base-content/60">
          {{ board.cards.length }} cards · {{ board.columns.length }} columns
        </span>
        <VButton size="sm" variant="ghost" @click="load">Reload</VButton>
        <VButton size="sm" variant="ghost" @click="refresh">Rebuild artefacts</VButton>
      </div>
    </div>

    <VAlert v-if="error" variant="error" class="m-4">{{ error }}</VAlert>
    <VAlert v-if="warnings.length > 0" variant="warning" class="m-4">
      <ul class="list-disc pl-4">
        <li v-for="w in warnings" :key="w">{{ w }}</li>
      </ul>
    </VAlert>

    <div v-if="loading" class="p-8 text-base-content/70">Loading board…</div>

    <VEmptyState
      v-else-if="board && board.columns.length === 0"
      class="m-4"
      headline="No columns yet"
      body="Add columns to _app.yaml to start using this board."
    />

    <div v-else class="flex-1 flex overflow-hidden">
      <!-- Board: horizontal scroll, columns side-by-side -->
      <div class="flex-1 flex overflow-x-auto p-4 gap-3">
        <div
          v-for="col in board?.columns ?? []"
          :key="col.name"
          class="flex flex-col w-72 flex-shrink-0 bg-base-200/40 rounded-lg"
        >
          <div class="flex items-center justify-between px-3 py-2 border-b border-base-300">
            <div class="flex items-center gap-2">
              <span class="font-medium">{{ col.title ?? col.name }}</span>
              <span
                class="text-xs text-base-content/60"
                :class="{ 'text-error font-semibold': col.wipExceeded }"
              >
                {{ col.cardCount }}<template v-if="col.wipLimit">/{{ col.wipLimit }}</template>
              </span>
              <span v-if="!col.declared" class="text-xs text-warning">⚠ undeclared</span>
            </div>
            <button
              class="text-base-content/60 hover:text-base-content text-lg leading-none"
              title="Add card"
              @click="openCreateModal(col.name)"
            >+</button>
          </div>

          <VueDraggable
            v-model="cardsByColumn[col.name]"
            group="kanban-cards"
            :animation="150"
            item-key="path"
            class="flex-1 flex flex-col gap-2 p-2 min-h-[80px] overflow-y-auto"
            @add="(e: any) => {
              const card = cardsByColumn[col.name][e.newIndex];
              if (card) onCardDropped(col.name, card);
            }"
          >
            <div
              v-for="card in cardsByColumn[col.name]"
              :key="card.path"
              class="bg-base-100 rounded p-2 cursor-grab active:cursor-grabbing shadow-sm hover:shadow-md transition-shadow"
              :class="priorityClass(card.priority)"
              @click="selectedCardPath = card.path"
            >
              <div class="font-medium text-sm">{{ card.title }}</div>
              <div class="flex flex-wrap items-center gap-1 mt-1 text-xs text-base-content/70">
                <span v-if="card.assignee" class="bg-base-200 rounded px-1.5 py-0.5">
                  @{{ card.assignee }}
                </span>
                <span v-if="card.priority" class="bg-base-200 rounded px-1.5 py-0.5">
                  {{ card.priority }}
                </span>
                <span v-if="card.dueDate" class="bg-base-200 rounded px-1.5 py-0.5">
                  📅 {{ card.dueDate }}
                </span>
                <span v-if="card.estimate" class="bg-base-200 rounded px-1.5 py-0.5">
                  {{ card.estimate }}p
                </span>
                <span v-if="card.blocked" class="bg-error/20 text-error rounded px-1.5 py-0.5">
                  blocked
                </span>
                <span
                  v-if="card.subtaskTotal > 0"
                  class="bg-base-200 rounded px-1.5 py-0.5"
                >
                  {{ card.subtaskDone }}/{{ card.subtaskTotal }}
                </span>
              </div>
              <div v-if="card.labels.length > 0" class="flex flex-wrap gap-1 mt-1">
                <span
                  v-for="label in card.labels"
                  :key="label"
                  class="text-xs bg-info/15 text-info rounded px-1.5 py-0.5"
                >
                  {{ label }}
                </span>
              </div>
            </div>
          </VueDraggable>
        </div>
      </div>

      <!-- Right panel: card detail -->
      <KanbanCardDetail
        v-if="selectedCard"
        :card="selectedCard"
        class="w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto"
        @close="selectedCardPath = null"
        @update="(patch) => onCardUpdate(selectedCard!.path, patch)"
        @delete="onCardDelete(selectedCard!.path)"
      />
    </div>

    <!-- New-card modal -->
    <VModal v-model="showCreateModal" title="New card">
      <div class="flex flex-col gap-3">
        <VInput v-model="newCardForm.title" placeholder="Card title" />
        <div class="flex gap-2">
          <VSelect
            :model-value="newCardForm.column ?? ''"
            :options="board?.columns.map((c) => ({ value: c.name, label: c.title ?? c.name })) ?? []"
            class="flex-1"
            @update:model-value="(v) => newCardForm.column = (v as string | null) ?? ''"
          />
          <VSelect
            :model-value="newCardForm.priority ?? ''"
            :options="[
              { value: '', label: 'No priority' },
              { value: 'low', label: 'Low' },
              { value: 'med', label: 'Medium' },
              { value: 'high', label: 'High' },
              { value: 'critical', label: 'Critical' },
            ]"
            class="flex-1"
            @update:model-value="(v) => newCardForm.priority = (v as string | null) ?? ''"
          />
        </div>
        <VInput
          :model-value="newCardForm.assignee ?? ''"
          placeholder="Assignee (optional)"
          @update:model-value="(v) => newCardForm.assignee = v"
        />
        <VInput
          :model-value="newCardForm.dueDate ?? ''"
          placeholder="Due date YYYY-MM-DD (optional)"
          @update:model-value="(v) => newCardForm.dueDate = v"
        />
        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="showCreateModal = false">Cancel</VButton>
          <VButton variant="primary" :disabled="!newCardForm.title.trim()" @click="submitCreate">
            Create
          </VButton>
        </div>
      </div>
    </VModal>
  </div>
</template>
