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
} from './api';
import type { KanbanBoardView } from './generated/kanban/KanbanBoardView';
import type { KanbanCardCreateRequest } from './generated/kanban/KanbanCardCreateRequest';
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
const detailRef = ref<{ flushNow: () => void; openContent: () => void } | null>(null);

// Bumped only when a remote edit to the OPEN card lands (outside our
// self-write window) — signals the detail panel to re-seed from the fresh
// card. Our own saves never bump it, so the cursor stays put while typing.
const remoteRevision = ref(0);

// Self-write quiet window per card path. A save's WS echo arrives after
// the PATCH response; suppressing the detail-refresh for a few seconds
// keeps our own write from resetting the editor.
const SELF_WRITE_QUIET_MS = 3000;
const selfWriteAt = new Map<string, number>();
function markSelfWrite(path: string): void {
  selfWriteAt.set(path, Date.now());
}
function withinSelfWrite(path: string): boolean {
  const t = selfWriteAt.get(path);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}
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
  // Only show the full-pane spinner on the FIRST load. A refresh (WS echo,
  // manual reload, rebuild) swaps board.value in place — flipping `loading`
  // would tear the `v-else` subtree (detail panel + open content modal) out
  // of the DOM and unmount it mid-edit.
  const first = board.value == null;
  if (first) loading.value = true;
  error.value = null;
  try {
    board.value = await getKanbanBoard(props.projectId, props.folder);
  } catch (e) {
    error.value = `Could not load board: ${(e as Error).message}`;
  } finally {
    if (first) loading.value = false;
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

// The detail panel owns the debounced PATCH; it emits `dirty` when a save
// cycle begins and `saved` with the authoritative card once the PATCH
// returns. The board only tracks the self-write window + mirrors the
// updated card into its array.
function onCardDirty(): void {
  if (selectedCardPath.value) markSelfWrite(selectedCardPath.value);
}

function onCardSaved(updated: KanbanCardView): void {
  markSelfWrite(updated.path);
  if (!board.value) return;
  // Path is stable across updates (the controller never renames on patch),
  // so just mirror the fresh card in — never touch the selection, which may
  // already point elsewhere if the user switched cards mid-save.
  const idx = board.value.cards.findIndex((c) => c.path === updated.path);
  if (idx >= 0) board.value.cards[idx] = updated;
}

function selectCard(path: string): void {
  // Progressive disclosure: first click opens the attribute panel; a
  // repeat click on the already-open card opens its content dialog.
  if (path === selectedCardPath.value) {
    detailRef.value?.openContent();
    return;
  }
  detailRef.value?.flushNow();
  selectedCardPath.value = path;
}

function closeDetail(): void {
  detailRef.value?.flushNow();
  selectedCardPath.value = null;
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

// Subtle full-tile tint from the card's document accent color. Literal
// class strings so the host Tailwind (which scans the addon source) emits
// them; a dynamic `bg-${c}-500/10` would be purged. Falls back to the
// neutral card surface when no color is set.
const CARD_TINT: Record<string, string> = {
  SLATE: 'bg-slate-500/10',
  RED: 'bg-red-500/10',
  ORANGE: 'bg-orange-500/10',
  AMBER: 'bg-amber-500/10',
  GREEN: 'bg-green-500/10',
  TEAL: 'bg-teal-500/10',
  CYAN: 'bg-cyan-500/10',
  BLUE: 'bg-blue-500/10',
  INDIGO: 'bg-indigo-500/10',
  PURPLE: 'bg-purple-500/10',
  PINK: 'bg-pink-500/10',
  ROSE: 'bg-rose-500/10',
};

function cardTintClass(color?: string | null): string {
  return (color && CARD_TINT[color]) || 'bg-base-100';
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

// Reload driven by the KanbanAppKind wrapper on documents.changed pushes
// (WS subscription lives there; the Board stays WS-free). `changedPaths`
// is the set of card paths that changed — omitted for a manual reload,
// which force-refreshes the open card. The open card is only re-seeded
// from the server when it actually changed remotely AND we're outside its
// self-write window, so our own write-echoes never reset the editor.
async function reload(changedPaths?: string[]): Promise<void> {
  const openPath = selectedCardPath.value;
  await load();
  if (!openPath || !board.value) return;
  const stillOpen = board.value.cards.some((c) => c.path === openPath);
  if (!stillOpen) {
    // Card was deleted / moved away remotely — drop the stale selection.
    selectedCardPath.value = null;
    return;
  }
  const changed = changedPaths == null || changedPaths.includes(openPath);
  if (changed && !withinSelfWrite(openPath)) {
    remoteRevision.value++;
  }
}

defineExpose({ reload });
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
        <VButton size="sm" variant="ghost" @click="reload()">Reload</VButton>
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
              class="rounded p-2 cursor-grab active:cursor-grabbing shadow-sm hover:shadow-md transition-shadow"
              :class="[priorityClass(card.priority), cardTintClass(card.color)]"
              @click="selectCard(card.path)"
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
        ref="detailRef"
        :card="selectedCard"
        :project-id="projectId"
        :folder="folder"
        :remote-revision="remoteRevision"
        class="w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto"
        @close="closeDetail"
        @dirty="onCardDirty"
        @saved="onCardSaved"
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
