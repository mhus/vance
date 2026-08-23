<script setup lang="ts">
/**
 * Remote control of running CLI clients — the "Clients" tab next to the
 * session list.
 *
 * <p>Sits here rather than in its own MPA entry because a client is a *peer* to
 * a session: one switches between "my sessions" and "my running clients". A
 * dedicated {@code clients.html} would be an entry that is empty most of the
 * time.
 *
 * <p>Deliberately plain text, not a terminal emulator. Foot's line buffer is
 * line-oriented — there is no ANSI byte stream to mirror — and the only thing
 * an emulator would add is the fullscreen UI, which is exactly what remote
 * control refuses to drive. Colour comes from the line's severity level and
 * this app's own theme.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VEmptyState, VInput } from '@components/index';
import type {
  RemoteClientInfo,
  RemoteClientPrompt,
  RemoteClientState,
  RemoteOutputLine,
} from '@vance/generated';
import {
  attachClient,
  detachClient,
  noteClientSeq,
  onClientOutput,
  onClientPrompt,
  onClientRoster,
  onClientState,
  requestClientList,
  sendClientInput,
  sendClientInterrupt,
} from '@/ws/wsConnectionStore';

const { t } = useI18n();

/** Lines kept per client in the browser. Bounded for the same reason foot bounds its ring. */
const MAX_LINES = 2000;

const clients = ref<RemoteClientInfo[]>([]);
const crossPod = ref(true);
const loading = ref(false);
const error = ref<string | null>(null);

const selected = ref<string | null>(null);
const lines = ref<RemoteOutputLine[]>([]);
const gapNotice = ref(false);
const state = ref<RemoteClientState | null>(null);
const prompt = ref<RemoteClientPrompt | null>(null);
const inputLine = ref('');
const logEl = ref<HTMLElement | null>(null);

let offOutput: (() => void) | null = null;
let offState: (() => void) | null = null;
let offPrompt: (() => void) | null = null;

const offRoster = onClientRoster((roster) => {
  clients.value = roster.clients ?? [];
  crossPod.value = roster.crossPod !== false;
  loading.value = false;
});

const selectedClient = computed(
  () => clients.value.find((c) => c.clientId === selected.value) ?? null,
);

/**
 * Whether typing is possible. The client is the authority here — it knows
 * about its own fullscreen excursions and its local approval — so we show its
 * reason verbatim instead of guessing one.
 */
const inputAllowed = computed(() => state.value?.acceptingInput === true);
const inputBlockedReason = computed(
  () => state.value?.inputBlockedReason ?? selectedClient.value?.inputBlockedReason ?? null,
);

async function refresh(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    await requestClientList();
  } catch (e) {
    loading.value = false;
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function select(clientId: string): Promise<void> {
  if (selected.value === clientId) return;
  await releaseSelection();
  selected.value = clientId;
  lines.value = [];
  gapNotice.value = false;
  prompt.value = null;
  state.value = null;

  offOutput = onClientOutput(clientId, (batch) => {
    if (batch.truncated) gapNotice.value = true;
    const incoming = batch.lines ?? [];
    if (incoming.length === 0) return;
    lines.value = lines.value.concat(incoming).slice(-MAX_LINES);
    const last = incoming[incoming.length - 1];
    if (last?.seq) noteClientSeq(clientId, last.seq);
    scrollToBottom();
  });
  offState = onClientState(clientId, (next) => {
    state.value = next;
  });
  offPrompt = onClientPrompt(clientId, (next) => {
    prompt.value = next.open ? next : null;
  });

  try {
    await attachClient(clientId, 0);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function releaseSelection(): Promise<void> {
  offOutput?.();
  offState?.();
  offPrompt?.();
  offOutput = null;
  offState = null;
  offPrompt = null;
  const previous = selected.value;
  selected.value = null;
  if (previous) {
    try {
      await detachClient(previous);
    } catch {
      /* detaching a gone client is not worth reporting */
    }
  }
}

async function submit(): Promise<void> {
  const line = inputLine.value;
  if (!selected.value || !line.trim()) return;
  inputLine.value = '';
  try {
    await sendClientInput(selected.value, line);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function answerPrompt(value: string): Promise<void> {
  if (!selected.value) return;
  try {
    // An answer is just an input line — the client's own input path routes it
    // to whatever prompt is waiting, so there is no second answer protocol.
    await sendClientInput(selected.value, value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function interrupt(hard: boolean): Promise<void> {
  if (!selected.value) return;
  try {
    await sendClientInterrupt(selected.value, hard);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

function scrollToBottom(): void {
  requestAnimationFrame(() => {
    const el = logEl.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

function levelClass(level?: string): string {
  switch (level) {
    case 'ERROR':
      return 'text-error';
    case 'WARN':
      return 'text-warning';
    case 'VERBOSE':
    case 'DEBUG':
    case 'TRACE':
      return 'opacity-60';
    default:
      return '';
  }
}

function shortTime(iso?: string): string {
  if (!iso) return '';
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? '' : at.toLocaleTimeString();
}

// Roster pushes are answers to our own request; nothing pushes it
// spontaneously, so refresh when the tab opens and on demand.
void refresh();

watch(selected, (next) => {
  if (next) scrollToBottom();
});

onBeforeUnmount(() => {
  offRoster();
  void releaseSelection();
});
</script>

<template>
  <div class="h-full min-h-0 flex flex-col gap-3">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <!-- Without Redis the roster only covers clients on this very pod. Saying
         so beats implying "you have no other clients". -->
    <VAlert v-if="!crossPod" variant="info">
      {{ t('chat.clients.podLocalOnly') }}
    </VAlert>

    <div class="flex items-center gap-2">
      <h3 class="flex-1 text-sm font-semibold opacity-70">
        {{ t('chat.clients.heading') }}
      </h3>
      <VButton size="sm" variant="ghost" :loading="loading" @click="refresh">
        {{ t('chat.clients.refresh') }}
      </VButton>
    </div>

    <VEmptyState
      v-if="!loading && clients.length === 0"
      :headline="t('chat.clients.emptyTitle')"
      :body="t('chat.clients.emptyHint')"
    />

    <div v-else class="flex flex-col gap-2">
      <button
        v-for="client in clients"
        :key="client.clientId"
        type="button"
        class="text-left rounded-lg border px-3 py-2 transition-colors"
        :class="client.clientId === selected
          ? 'border-primary bg-primary/5'
          : 'border-base-300 hover:bg-base-200'"
        @click="select(client.clientId)"
      >
        <div class="flex items-baseline gap-2 min-w-0">
          <span class="font-mono text-sm truncate">{{ client.label || client.clientId }}</span>
          <span v-if="client.busy" class="text-xs text-warning">{{ t('chat.clients.busy') }}</span>
          <span v-if="client.uiMode === 'FULLSCREEN'" class="text-xs opacity-60">
            {{ t('chat.clients.fullscreen') }}
          </span>
          <span class="flex-1" />
          <span class="text-xs opacity-50">{{ shortTime(client.lastSeenAt) }}</span>
        </div>
        <div class="text-xs opacity-60 truncate">
          {{ client.sessionId ? client.sessionId : t('chat.clients.noSession') }}
          <template v-if="client.version"> · {{ client.version }}</template>
        </div>
      </button>
    </div>

    <!-- Detail: the line stream plus the state the pinned terminal UI would
         show (which never appears in the stream itself). -->
    <div v-if="selected" class="flex-1 min-h-0 flex flex-col gap-2 border-t border-base-300 pt-3">
      <div class="flex items-center gap-2 text-xs">
        <span class="opacity-70">
          {{ t('chat.clients.stateLine', {
            connection: state?.connection ?? '—',
            ui: state?.uiMode ?? '—',
          }) }}
        </span>
        <span class="flex-1" />
        <VButton size="sm" variant="ghost" @click="interrupt(false)">
          {{ t('chat.clients.pause') }}
        </VButton>
        <VButton size="sm" variant="ghost" @click="interrupt(true)">
          {{ t('chat.clients.stop') }}
        </VButton>
        <VButton size="sm" variant="ghost" @click="releaseSelection()">
          {{ t('chat.clients.detach') }}
        </VButton>
      </div>

      <VAlert v-if="gapNotice" variant="warning">
        {{ t('chat.clients.gap') }}
      </VAlert>

      <!-- An open prompt is the reason this panel exists: the client is
           blocked and only an answer moves it on. -->
      <div
        v-if="prompt"
        class="rounded-lg border border-warning bg-warning/10 px-3 py-2 flex flex-col gap-2"
      >
        <div class="text-sm font-medium">{{ prompt.question }}</div>
        <div v-if="prompt.subject" class="text-xs font-mono opacity-70 break-all">
          {{ prompt.subject }}
        </div>
        <div class="flex flex-wrap gap-2">
          <!-- An answer is input, so it is gated like input. Offering an
               enabled button that the client will refuse would put the refusal
               where nobody sees it — in the client's local terminal. -->
          <VButton
            v-for="option in prompt.options ?? []"
            :key="option.value"
            size="sm"
            variant="secondary"
            :disabled="!inputAllowed"
            @click="answerPrompt(option.value)"
          >
            {{ option.label }}
          </VButton>
        </div>
        <p v-if="!inputAllowed" class="text-xs opacity-70">
          {{ inputBlockedReason ?? t('chat.clients.inputBlocked') }}
        </p>
      </div>

      <div
        ref="logEl"
        class="flex-1 min-h-[12rem] overflow-y-auto rounded-lg bg-base-200 px-3 py-2"
      >
        <pre
          v-if="lines.length"
          class="text-xs font-mono whitespace-pre-wrap break-words"
        ><span
          v-for="line in lines"
          :key="line.seq"
          :class="levelClass(line.level)"
        >{{ line.text }}
</span></pre>
        <div v-else class="text-xs opacity-50">{{ t('chat.clients.noOutput') }}</div>
      </div>

      <div class="flex flex-col gap-1">
        <div class="flex gap-2">
          <div class="flex-1">
            <VInput
              v-model="inputLine"
              :disabled="!inputAllowed"
              :placeholder="inputAllowed
                ? t('chat.clients.inputPlaceholder')
                : t('chat.clients.inputBlocked')"
              @keydown.enter.prevent="submit"
            />
          </div>
          <VButton variant="primary" :disabled="!inputAllowed" @click="submit">
            {{ t('chat.clients.send') }}
          </VButton>
        </div>
        <p v-if="!inputAllowed && inputBlockedReason" class="text-xs opacity-70">
          {{ inputBlockedReason }}
        </p>
      </div>
    </div>
  </div>
</template>
