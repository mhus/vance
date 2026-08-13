<script setup lang="ts">
/**
 * One run in full: where it got to, what it produced, what it started,
 * and how to get out of here to the thing it runs.
 *
 * <p>The blocks below are the ones every source can fill. What only one
 * source has goes through {@link RunExtraBlock}.
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VCard } from '@/components';
import type { RunDetailDto } from '@vance/generated';
import RunStatusBadge from './RunStatusBadge.vue';
import RunExtraBlock from './RunExtraBlock.vue';

const props = defineProps<{ detail: RunDetailDto; projectId: string }>();
const emit = defineEmits<{ (e: 'open-run', runId: string): void }>();
const { t } = useI18n();

const variables = computed(() => Object.entries(props.detail.variables ?? {}));

/** Where a link points, per rel. The run view never renders those itself. */
function href(rel: string, target: string): string {
  const project = encodeURIComponent(props.projectId);
  if (rel === 'session') return `/chat.html?project=${project}&session=${encodeURIComponent(target)}`;
  // definition / document both open in Cortex by path.
  return `/cortex.html?project=${project}&path=${encodeURIComponent(target)}`;
}

function stepTone(outcome?: string | null): string {
  if (!outcome) return 'current';
  return outcome === 'success' || outcome === 'done' ? 'ok' : 'other';
}

function render(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}
</script>

<template>
  <div class="detail">
    <header>
      <div class="title-row">
        <h2>{{ detail.summary.name }}</h2>
        <RunStatusBadge :status="detail.summary.status" />
      </div>
      <p class="meta">
        <span class="mono">{{ detail.summary.runId }}</span>
        <span v-if="detail.summary.startedBy"> · {{ detail.summary.startedBy }}</span>
        <span v-if="detail.summary.startedAt"> · {{ detail.summary.startedAt }}</span>
      </p>
      <p v-if="detail.links?.length" class="links">
        <a v-for="link in detail.links" :key="link.rel + link.target"
           :href="href(link.rel, link.target)" class="link">
          {{ t(`runs.link.${link.rel}`) }} ↗
        </a>
      </p>
    </header>

    <VAlert v-if="detail.errorMessage" variant="error">{{ detail.errorMessage }}</VAlert>

    <!-- Waiting on a person: the useful action is answering the item, not
         doing anything to the run. -->
    <VAlert v-if="detail.waitingOnInboxItemId" variant="warning" class="waiting">
      {{ t('runs.detail.waitingOnInbox') }}
      <a :href="`/inbox.html?item=${encodeURIComponent(detail.waitingOnInboxItemId)}`">
        {{ t('runs.detail.openInbox') }} ↗
      </a>
    </VAlert>

    <VCard v-if="detail.steps?.length" :title="t('runs.detail.steps')">
      <ol class="steps">
        <li v-for="(step, i) in detail.steps" :key="i"
            :class="['step', `step--${stepTone(step.outcome)}`]">
          <span class="step-name">{{ step.name }}</span>
          <span v-if="step.kind" class="step-kind">{{ step.kind }}</span>
          <span v-if="step.outcome" class="step-outcome">{{ step.outcome }}</span>
          <span v-if="step.detail" class="step-detail">{{ step.detail }}</span>
        </li>
      </ol>
    </VCard>

    <VCard v-if="variables.length" :title="t('runs.detail.variables')">
      <dl class="vars">
        <template v-for="[key, value] in variables" :key="key">
          <dt>{{ key }}</dt>
          <dd><pre>{{ render(value) }}</pre></dd>
        </template>
      </dl>
    </VCard>

    <VCard v-if="detail.children?.length" :title="t('runs.detail.children')">
      <ul class="children">
        <li v-for="child in detail.children" :key="child.runId">
          <VButton size="sm" variant="ghost" @click="emit('open-run', child.runId)">
            {{ child.name || child.runId }}
          </VButton>
          <span v-if="child.fromStep" class="from-step">{{ child.fromStep }}</span>
        </li>
      </ul>
    </VCard>

    <VCard v-if="detail.result" :title="t('runs.detail.result')">
      <pre class="result">{{ render(detail.result) }}</pre>
    </VCard>

    <VCard :title="t('runs.detail.source', { source: detail.summary.source })">
      <RunExtraBlock :source="detail.summary.source" :extra="detail.extra" />
    </VCard>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; gap: 0.75rem; }
.title-row { display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap; }
h2 { margin: 0; font-size: 1.05rem; }
.meta { margin: 0.15rem 0 0; font-size: 0.75rem; opacity: 0.65; }
.mono { font-family: ui-monospace, monospace; }
.links { margin: 0.35rem 0 0; display: flex; gap: 0.75rem; }
.link { font-size: 0.8rem; text-decoration: underline; }
.waiting { font-size: 0.85rem; }
.waiting a { text-decoration: underline; margin-left: 0.4rem; }
.steps { margin: 0; padding-left: 1.1rem; display: flex; flex-direction: column; gap: 0.25rem; }
.step { font-size: 0.85rem; display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: baseline; }
.step-name { font-weight: 600; }
.step-kind { font-size: 0.68rem; text-transform: uppercase; opacity: 0.5; }
.step-outcome { font-size: 0.72rem; opacity: 0.8; }
.step--ok .step-outcome { color: var(--color-success); }
.step--other .step-outcome { color: var(--color-warning); }
.step--current .step-name::after { content: ' …'; opacity: 0.6; }
.step-detail { font-size: 0.75rem; opacity: 0.7; flex-basis: 100%; }
.vars { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: 0.2rem 0.75rem; }
.vars dt { font-size: 0.72rem; font-family: ui-monospace, monospace; opacity: 0.7; }
.vars dd { margin: 0; min-width: 0; }
.vars pre, .result {
  margin: 0; font-size: 0.75rem; white-space: pre-wrap; word-break: break-word;
  font-family: ui-monospace, monospace;
}
.children { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.2rem; }
.children li { display: flex; align-items: center; gap: 0.5rem; }
.from-step { font-size: 0.72rem; opacity: 0.55; font-family: ui-monospace, monospace; }
</style>
