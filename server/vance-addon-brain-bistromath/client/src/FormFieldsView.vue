<script setup lang="ts">
import { pickLocalized } from '@vance/shared';
import { useLocale, VEmptyState } from '@vance/components';
import { useT } from './i18n';
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

const t = useT();

/**
 * The reader's UI language — the one the rest of the interface is in, not the
 * browser's. Reached through the host's app context (see {@code useLocale}),
 * so it follows a language switch in the profile.
 *
 * <p>`pickLocalized` still falls back to English and then to any present
 * value: a form authored in one language must render for a reader in another.
 */
const locale = useLocale();

function label(field: FormFieldDto): string {
  return pickLocalized(field.label, locale.value) || field.name;
}

function help(field: FormFieldDto): string | null {
  return pickLocalized(field.help, locale.value) || null;
}

function value(field: FormFieldDto): string {
  const raw = props.record?.[field.name];
  if (raw === undefined || raw === null || raw === '') return t('bistromath.details.empty');
  if (field.type === 'boolean') {
    return raw === true || raw === 'true'
      ? t('bistromath.details.yes')
      : t('bistromath.details.no');
  }
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
    :headline="t('bistromath.details.emptyHeadline')"
    :body="t('bistromath.details.emptyBody')"
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
