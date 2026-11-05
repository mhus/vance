<script setup lang="ts">
/**
 * "Simulate": give a halted run a result instead of executing the state it stands in
 * front of. Nothing is run — not the tool, the script, the agent, no inbox item is
 * created, no timer waits. The reader says how the state ends (one of the outcomes the
 * plan knows a way on for) and, if a later state reads it, what it produced.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { RunSimulateRequest, RunSimulationDto } from '@vance/generated';
import { VButton, VInput, VModal, VSelect, VTextarea } from '@/components';
import { isFailureOutcome } from './stepOverlay';
import { defaultOutcome, parseSimulatedOutput } from './simulate';

const props = defineProps<{
  modelValue: boolean;
  simulation: RunSimulationDto;
  busy?: boolean;
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'submit', body: RunSimulateRequest): void;
}>();
const { t } = useI18n();

const outcome = ref<string>(defaultOutcome(props.simulation.outcomes));
const output = ref('');
const errorMessage = ref('');

// A new halt is a new question: start from its defaults, not from the last answer.
watch(
  () => [props.simulation.state, props.modelValue] as const,
  () => {
    outcome.value = defaultOutcome(props.simulation.outcomes);
    output.value = '';
    errorMessage.value = '';
  },
);

const options = computed(() => props.simulation.outcomes.map((o) => ({ value: o, label: o })));
const isFailure = computed(() => isFailureOutcome(outcome.value));

function submit(): void {
  const parsed = parseSimulatedOutput(output.value);
  emit('submit', {
    outcome: outcome.value,
    ...(parsed === undefined ? {} : { output: parsed }),
    ...(isFailure.value && errorMessage.value.trim() !== '' ? { errorMessage: errorMessage.value.trim() } : {}),
  });
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    size="sm"
    :title="t('runs.simulate.title', { state: simulation.state })"
    :description="t('runs.simulate.description', { kind: simulation.kind })"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <form id="simulate-form" class="flex flex-col gap-3" @submit.prevent="submit">
      <VSelect v-model="outcome" :label="t('runs.simulate.outcome')" :options="options" />
      <VInput
        v-if="isFailure"
        v-model="errorMessage"
        :label="t('runs.simulate.errorMessage')"
      />
      <VTextarea
        v-model="output"
        :label="t('runs.simulate.output')"
        :rows="6"
        class="font-mono"
      />
      <p class="hint">{{ t('runs.simulate.outputHint') }}</p>
    </form>
    <template #actions>
      <VButton variant="secondary" @click="emit('update:modelValue', false)">
        {{ t('runs.simulate.cancel') }}
      </VButton>
      <VButton type="submit" form="simulate-form" variant="primary" :loading="busy" :disabled="outcome === ''">
        {{ t('runs.simulate.submit') }}
      </VButton>
    </template>
  </VModal>
</template>

<style scoped>
.hint { margin: 0; font-size: 0.78rem; opacity: 0.65; }
</style>
