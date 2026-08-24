<script setup lang="ts">
import { FormFields as SharedFormFields, type FormValue } from '@vance/components';
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormFieldDto } from '@vance/generated';

/**
 * The host's i18n binding for the shared form renderer.
 *
 * <p>The renderer itself lives in {@code @vance/components} — it has to, because
 * Brain addons bundle separately and cannot import from {@code vance-face}. That
 * package deliberately has no {@code vue-i18n} dependency, so its strings are
 * props with English defaults.
 *
 * <p>This file is where the German comes back. It is a binding, not a second
 * renderer: no markup, no field logic, no branch on {@code field.type}. If
 * anything about *how a field looks* ever appears below this comment, it is in
 * the wrong file.
 */
export type { FormValue, FormValueObject } from '@vance/components';

const props = withDefaults(
  defineProps<{
    fields: FormFieldDto[];
    modelValue: Record<string, FormValue>;
    errors?: Record<string, string>;
    /** Override the active locale for label resolution. */
    preferredLang?: string;
    pathPrefix?: string;
    disabled?: boolean;
  }>(),
  {
    errors: () => ({}),
    preferredLang: undefined,
    pathPrefix: '',
    disabled: false,
  },
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, FormValue>): void;
}>();

const { t, locale } = useI18n();

const lang = computed(() => props.preferredLang ?? locale.value);
</script>

<template>
  <SharedFormFields
    :fields="fields"
    :model-value="modelValue"
    :errors="errors"
    :preferred-lang="lang"
    :path-prefix="pathPrefix"
    :disabled="disabled"
    :filter-label="t('form.filterPlaceholder')"
    :no-matches-label="t('form.noMatches')"
    :selected-count-label="(n: number) => t('form.selectedCount', { count: n })"
    :clear-selection-label="t('form.clearSelection')"
    @update:model-value="(v: Record<string, FormValue>) => emit('update:modelValue', v)"
  />
</template>
