<script setup lang="ts">
import { pickLocalized } from '@vance/shared';
import { VEmptyState } from '@vance/components';
import type { FormFieldDto } from '@vance/generated';

/**
 * Read-only display of a form-engine field list against one table row.
 *
 * <p>This is a <b>viewer</b>, not a disabled editor. `FormFields` — the shared
 * editable renderer — now lives in `@vance/components` and the `form` widget
 * uses it; this one is what a `details` widget renders. The difference is not
 * a flag: an editor with its inputs greyed out still reads as a form somebody
 * broke, while a label-over-value readout reads as information. Same fields,
 * different job.
 *
 * <p>So this component renders labels and values and nothing else. The field
 * `type` is honoured only as far as display goes — a boolean reads as yes/no,
 * a long text keeps its line breaks. The values come from the program's state,
 * so they are whatever JavaScript put there; formatting to a string happens
 * here rather than being assumed.
 */
const props = defineProps<{
  fields: FormFieldDto[];
  /** The bound record, or `null` when nothing is selected. */
  record: Record<string, unknown> | null;
}>();

/**
 * The reader's language, read off the browser rather than from `vue-i18n`.
 *
 * <p>`useI18n()` needs the host's i18n instance, and an addon bundle does not
 * share it. `pickLocalized` already falls back to English and then to any
 * present value, so the worst case of guessing wrong is a label in the one
 * language the document does have.
 */
const lang = (navigator.language || 'en').split('-')[0];

function label(field: FormFieldDto): string {
  return pickLocalized(field.label, lang) || field.name;
}

function help(field: FormFieldDto): string | null {
  return pickLocalized(field.help, lang) || null;
}

function value(field: FormFieldDto): string {
  const raw = props.record?.[field.name];
  if (raw === undefined || raw === null || raw === '') return '—';
  if (field.type === 'boolean') return raw === true || raw === 'true' ? 'yes' : 'no';
  if (typeof raw === 'object') return JSON.stringify(raw);
  return String(raw);
}

function multiline(field: FormFieldDto): boolean {
  return field.type === 'textarea';
}
</script>

<template>
  <VEmptyState
    v-if="!record"
    headline="Nothing selected"
    body="Click a row in the table to see it here."
  />
  <dl v-else class="flex flex-col gap-2">
    <div v-for="field in fields" :key="field.name" class="flex flex-col gap-0.5">
      <dt class="text-xs font-semibold uppercase tracking-wide opacity-50">
        {{ label(field) }}
      </dt>
      <dd :class="['text-sm', multiline(field) ? 'whitespace-pre-line' : '']">
        {{ value(field) }}
      </dd>
      <p v-if="help(field)" class="text-xs opacity-60">{{ help(field) }}</p>
    </div>
  </dl>
</template>
