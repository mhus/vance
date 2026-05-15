<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { EventSource, EventSummary } from '@vance/generated';
import {
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VTextarea,
} from '@/components';
import { useEvents } from '@/composables/useEvents';

const props = defineProps<{ projectId: string | null }>();
const { t } = useI18n();

const state = useEvents();

// Currently selected event name. Detail panel populates from
// state.current — loadOne is called when the user clicks an item.
const selectedName = ref<string | null>(null);

/**
 * JSON payload editor content. Free text — we validate on submit so
 * users can edit live without flicker. Empty string = no payload at
 * all (the `params.payload` key is omitted server-side).
 */
const payloadText = ref('');

/** Result of the last trigger attempt — banner stays until next click. */
const triggerError = ref<string | null>(null);

watch(
  () => props.projectId,
  (next) => {
    selectedName.value = null;
    state.clearCurrent();
    state.clearLastResult();
    triggerError.value = null;
    payloadText.value = '';
    if (next) {
      void state.loadProject(next);
    } else {
      state.events.value = [];
    }
  },
  { immediate: true },
);

async function selectEvent(name: string): Promise<void> {
  if (!props.projectId) return;
  selectedName.value = name;
  state.clearLastResult();
  triggerError.value = null;
  payloadText.value = '';
  await state.loadOne(props.projectId, name);
}

// Java enums arrive over the wire as their string name (Jackson default);
// the generated TS enum types them as numeric, but at runtime they are
// strings. Compare via the stringified value to stay correct in both
// the TS type system and the actual runtime.
function sourceLabel(source: EventSource | string): string {
  return String(source) === 'PROJECT' ? 'project' : '_vance';
}

function sourceClass(source: EventSource | string): string {
  return String(source) === 'PROJECT'
    ? 'badge-source badge-source--project'
    : 'badge-source badge-source--vance';
}

function methodsLabel(methods: string[] | null | undefined): string {
  if (!methods || methods.length === 0) return 'GET, POST';
  return methods.join(', ');
}

const detail = computed(() => state.current.value);

const triggerDisabled = computed(() => {
  if (!props.projectId) return true;
  if (!detail.value) return true;
  if (!detail.value.enabled) return true;
  return state.busy.value;
});

const triggerDisabledHint = computed<string | null>(() => {
  if (!detail.value) return null;
  if (!detail.value.enabled) return t('insights.events.disabledHint');
  return null;
});

async function onTrigger(): Promise<void> {
  if (!props.projectId || !detail.value) return;
  triggerError.value = null;

  // Parse the payload text into an object/null. Empty → no payload.
  let payload: unknown = null;
  const raw = payloadText.value.trim();
  if (raw.length > 0) {
    try {
      payload = JSON.parse(raw);
    } catch (e) {
      triggerError.value = t('insights.events.payloadJsonError', {
        error: e instanceof Error ? e.message : String(e),
      });
      return;
    }
  }

  try {
    await state.trigger(props.projectId, detail.value.name, payload);
  } catch (e) {
    triggerError.value =
      e instanceof Error ? e.message : t('insights.events.triggerGenericError');
  }
}

function sortedEvents(): EventSummary[] {
  return [...state.events.value].sort((a, b) =>
    (a.name ?? '').localeCompare(b.name ?? ''),
  );
}
</script>

<template>
  <div class="flex flex-col gap-3 p-2">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.events.pickProject') }}
    </div>

    <template v-else>
      <VAlert v-if="state.error.value" variant="error">
        <span>{{ state.error.value }}</span>
      </VAlert>

      <div v-if="state.loading.value" class="text-sm opacity-60">
        {{ $t('insights.events.loading') }}
      </div>

      <VEmptyState
        v-else-if="state.events.value.length === 0"
        :headline="$t('insights.events.emptyHeadline')"
        :body="$t('insights.events.emptyBody')"
      />

      <div v-else class="grid grid-cols-12 gap-3">
        <!-- ─── Event list (left) ─── -->
        <nav class="col-span-5 flex flex-col gap-1">
          <button
            v-for="ev in sortedEvents()"
            :key="ev.name"
            type="button"
            class="event-row"
            :class="{
              'event-row--active': selectedName === ev.name,
              'event-row--disabled': !ev.enabled,
            }"
            @click="selectEvent(ev.name)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ ev.name }}</span>
              <span :class="sourceClass(ev.source)" class="text-xs">
                {{ sourceLabel(ev.source) }}
              </span>
            </div>
            <div class="text-xs opacity-60 truncate mt-0.5">
              <span class="opacity-80">→</span>
              {{ ev.workflow ?? '—' }}
              <span class="ml-2">{{ methodsLabel(ev.methods) }}</span>
              <span v-if="ev.authConfigured" class="ml-2">· 🔒 bearer</span>
              <span v-if="!ev.enabled" class="ml-2 opacity-80">· disabled</span>
            </div>
            <div
              v-if="ev.description"
              class="text-xs opacity-60 truncate mt-0.5"
              :title="ev.description"
            >
              {{ ev.description }}
            </div>
          </button>
        </nav>

        <!-- ─── Detail (right) ─── -->
        <div class="col-span-7 flex flex-col gap-3">
          <VEmptyState
            v-if="!detail"
            :headline="$t('insights.events.selectHeadline')"
            :body="$t('insights.events.selectBody')"
          />

          <template v-else>
            <VCard :title="detail.name">
              <dl class="grid grid-cols-3 gap-x-3 gap-y-1 text-sm">
                <dt class="opacity-60 col-span-1">
                  {{ $t('insights.events.detail.workflow') }}
                </dt>
                <dd class="col-span-2 font-mono">{{ detail.workflow ?? '—' }}</dd>

                <dt class="opacity-60 col-span-1">
                  {{ $t('insights.events.detail.enabled') }}
                </dt>
                <dd class="col-span-2">
                  <span
                    class="text-xs px-1.5 py-0.5 rounded"
                    :class="detail.enabled ? 'badge-open' : 'badge-closed'"
                  >{{ detail.enabled ? 'enabled' : 'disabled' }}</span>
                </dd>

                <dt class="opacity-60 col-span-1">
                  {{ $t('insights.events.detail.methods') }}
                </dt>
                <dd class="col-span-2">{{ methodsLabel(detail.methods) }}</dd>

                <dt class="opacity-60 col-span-1">
                  {{ $t('insights.events.detail.auth') }}
                </dt>
                <dd class="col-span-2">
                  <span v-if="detail.authConfigured" class="text-xs">
                    {{ $t('insights.events.detail.bearerConfigured') }}
                  </span>
                  <span v-else class="text-xs opacity-70">
                    {{ $t('insights.events.detail.bearerNone') }}
                  </span>
                </dd>

                <dt class="opacity-60 col-span-1">
                  {{ $t('insights.events.detail.runAs') }}
                </dt>
                <dd class="col-span-2">{{ detail.runAs ?? '—' }}</dd>

                <dt class="opacity-60 col-span-1">
                  {{ $t('insights.events.detail.source') }}
                </dt>
                <dd class="col-span-2">{{ sourceLabel(detail.source) }}</dd>

                <template v-if="detail.description">
                  <dt class="opacity-60 col-span-1">
                    {{ $t('insights.events.detail.description') }}
                  </dt>
                  <dd class="col-span-2">{{ detail.description }}</dd>
                </template>
              </dl>

              <details v-if="detail.params" class="mt-3">
                <summary class="text-xs opacity-70 cursor-pointer">
                  {{ $t('insights.events.detail.staticParams') }}
                </summary>
                <pre class="json-block">{{ JSON.stringify(detail.params, null, 2) }}</pre>
              </details>

              <details v-if="detail.yaml" class="mt-3">
                <summary class="text-xs opacity-70 cursor-pointer">
                  {{ $t('insights.events.detail.rawYaml') }}
                </summary>
                <pre class="json-block">{{ detail.yaml }}</pre>
              </details>
            </VCard>

            <!-- ─── Trigger panel ─── -->
            <VCard :title="$t('insights.events.trigger.title')">
              <p class="text-xs opacity-70 mb-2">
                {{ $t('insights.events.trigger.help') }}
              </p>

              <VTextarea
                v-model="payloadText"
                :label="$t('insights.events.trigger.payloadLabel')"
                :placeholder="$t('insights.events.trigger.payloadPlaceholder')"
                :rows="6"
                class="font-mono"
              />

              <VAlert v-if="triggerError" variant="error" class="mt-2">
                <span>{{ triggerError }}</span>
              </VAlert>

              <div class="flex items-center gap-2 mt-3">
                <VButton
                  :disabled="triggerDisabled"
                  :loading="state.busy.value"
                  variant="primary"
                  @click="onTrigger"
                >{{ $t('insights.events.trigger.button') }}</VButton>
                <span
                  v-if="triggerDisabledHint"
                  class="text-xs opacity-70"
                >{{ triggerDisabledHint }}</span>
              </div>

              <VAlert
                v-if="state.lastResult.value"
                variant="success"
                class="mt-3"
              >
                <div class="flex flex-col gap-1 text-sm">
                  <span>
                    {{ $t('insights.events.trigger.spawnedPrefix') }}
                    <span class="font-mono">{{ state.lastResult.value.workflowName }}</span>
                  </span>
                  <span>
                    {{ $t('insights.events.trigger.runIdLabel') }}
                    <span class="font-mono">{{ state.lastResult.value.workflowRunId }}</span>
                  </span>
                </div>
              </VAlert>
            </VCard>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.event-row {
  display: block;
  text-align: left;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  border: 1px solid transparent;
}
.event-row:hover {
  background: hsl(var(--bc) / 0.06);
}
.event-row--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}
.event-row--disabled {
  opacity: 0.55;
}

.badge-source {
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  font-family: ui-monospace, monospace;
}
.badge-source--project {
  background: hsl(var(--p) / 0.18);
  color: hsl(var(--p));
}
.badge-source--vance {
  background: hsl(var(--bc) / 0.12);
  color: hsl(var(--bc) / 0.7);
}

.badge-open {
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
}
.badge-closed {
  background: hsl(var(--bc) / 0.1);
  color: hsl(var(--bc) / 0.6);
}

.json-block {
  background: hsl(var(--bc) / 0.05);
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  word-break: break-word;
  margin-top: 0.25rem;
}
</style>
