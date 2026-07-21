<script setup lang="ts">
/**
 * Renders an `agent`-task compose output: a reference to a session process's
 * answer. The URI is either `vance-process:<id>` (latest answer) or
 * `vance-process:<id>/<messageId>` (a pinned answer message — the normal case,
 * since the agent task blocks until the turn finishes and records the exact
 * reply). The answer is already present when this renders (the compose run only
 * completes once the turn has), so a single fetch suffices; no polling.
 */
import { computed, onMounted, ref } from 'vue';
import { brainFetch } from '@vance/shared';
import { MarkdownView, VAlert, VCard } from '@/components';

interface OutputArtifact {
  path: string;
  uri: string;
  kind?: string;
  mime?: string;
  title?: string;
}
const props = defineProps<{ projectId: string; output: OutputArtifact }>();

const PROCESS_PREFIX = 'vance-process:';

/** `vance-process:<pid>[/<msgId>]` → { processId, messageId? }. */
const ref_ = computed<{ processId: string; messageId: string | null }>(() => {
  const rest = props.output.uri.startsWith(PROCESS_PREFIX)
    ? props.output.uri.slice(PROCESS_PREFIX.length)
    : props.output.uri;
  const slash = rest.indexOf('/');
  return slash === -1
    ? { processId: rest, messageId: null }
    : { processId: rest.slice(0, slash), messageId: rest.slice(slash + 1) };
});

interface Msg { messageId: string; role: string; content: string }
interface Resp { processId: string; status: string; messages: Msg[] }

const answer = ref<string | null>(null);
const messageId = ref<string | null>(null);
const error = ref<string | null>(null);
const loading = ref<boolean>(true);

const title = computed<string>(() => props.output.title ?? 'Agent');

function pick(msgs: Msg[]): Msg | null {
  const wanted = ref_.value.messageId;
  if (wanted) {
    const hit = msgs.find((m) => m.messageId === wanted);
    if (hit) return hit;
  }
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    if ((m.role ?? '').toUpperCase() === 'ASSISTANT' && m.content?.trim()) return m;
  }
  return null;
}

async function load() {
  try {
    const r = await brainFetch<Resp>('GET', `process/${encodeURIComponent(ref_.value.processId)}/messages`);
    const m = pick(r.messages ?? []);
    answer.value = m?.content ?? null;
    messageId.value = m?.messageId ?? null;
    error.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

onMounted(() => { void load(); });
</script>

<template>
  <VCard :title="title">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <p v-else-if="loading" class="text-sm opacity-60">Lade Antwort…</p>
    <template v-else>
      <MarkdownView v-if="answer" :source="answer" />
      <p v-else class="text-sm opacity-60">Der Agent hat keine Antwort erzeugt.</p>
      <p v-if="messageId" class="text-xs opacity-40 mt-1 font-mono">{{ messageId }}</p>
    </template>
  </VCard>
</template>
