<script setup lang="ts">
/**
 * "Translate…" — the form, the call, and (for a selection) the result.
 *
 * Two modes over one dialog, differing in what comes out rather than in what
 * goes in:
 *
 *  - `document`: the whole body. Asks for a language and a file name, and
 *    hands the translation to the parent, which writes it as a new document
 *    and opens it.
 *  - `selection`: the reader's marked passage. Asks for a language only and
 *    shows the result right here with a copy button — a fragment has no
 *    obvious place in the project, and inventing one file per marked paragraph
 *    is not a favour.
 *
 * <p>The dialog owns the call because the call is one REST round trip that
 * needs nothing but a project id. It does not own the document: writing and
 * opening belong to the Cortex, which has the store.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VInput, VModal, VSelect } from '@/components';
import {
  TRANSLATE_LANGUAGES,
  TRANSLATE_MAX_CHARS,
  languageInstruction,
  languageLabel,
  looksTruncated,
  suggestTranslatedName,
  translate,
} from '../translate';

const props = defineProps<{
  modelValue: boolean;
  mode: 'document' | 'selection';
  projectId: string;
  /** Source file name — prefills the target name and hints the format. */
  sourceName: string;
  /** The text to translate: the tab's body, or the marked passage. */
  sourceText: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  /** Document mode only: the finished translation and the name to give it. */
  (e: 'translated', payload: { text: string; name: string; truncated: boolean }): void;
}>();

const { t, locale } = useI18n();

/** Sentinel for "a language that is not in the list". */
const CUSTOM = '__custom__';

const language = ref<string>('en');
const customLanguage = ref('');
const targetName = ref('');
/** Set once the reader edits the name, so the language watcher stops helping. */
const nameTouched = ref(false);
const running = ref(false);
const error = ref<string | null>(null);
const result = ref<string | null>(null);
const truncated = ref(false);
const copied = ref(false);

const options = computed(() => {
  const langs = TRANSLATE_LANGUAGES
    .map((code) => ({ value: code, label: languageLabel(code, locale.value) }))
    .sort((a, b) => a.label.localeCompare(b.label, locale.value));
  return [...langs, { value: CUSTOM, label: t('cortex.translate.customLanguage') }];
});

/** What the recipe is told. Free text passes through as typed. */
const targetLanguage = computed<string>(() =>
  language.value === CUSTOM ? customLanguage.value.trim() : languageInstruction(language.value));

const tooLong = computed(() => props.sourceText.length > TRANSLATE_MAX_CHARS);

const canSubmit = computed<boolean>(() => {
  if (running.value || tooLong.value) return false;
  if (!props.sourceText.trim()) return false;
  if (!targetLanguage.value) return false;
  if (props.mode === 'document' && !targetName.value.trim()) return false;
  return true;
});

// Reset per opening: the dialog is kept mounted, so without this the previous
// run's result and error would greet the next reader.
watch(() => props.modelValue, (open) => {
  if (!open) return;
  error.value = null;
  result.value = null;
  truncated.value = false;
  copied.value = false;
  nameTouched.value = false;
  running.value = false;
  targetName.value = suggestTranslatedName(props.sourceName, language.value);
});

// Keep the proposed name in step with the language until the reader takes it
// over. Always derived from the source name, never from the current value, so
// switching languages twice cannot stack suffixes.
watch(language, (code) => {
  if (nameTouched.value || props.mode !== 'document') return;
  if (code === CUSTOM) return;
  targetName.value = suggestTranslatedName(props.sourceName, code);
});

function onNameInput(value: string): void {
  nameTouched.value = true;
  targetName.value = value;
}

function close(): void {
  emit('update:modelValue', false);
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  running.value = true;
  error.value = null;
  result.value = null;
  try {
    const text = await translate({
      projectId: props.projectId,
      language: targetLanguage.value,
      text: props.sourceText,
      sourceName: props.sourceName,
    });
    if (!text.trim()) {
      error.value = t('cortex.translate.emptyResult');
      return;
    }
    truncated.value = looksTruncated(props.sourceText, text);
    if (props.mode === 'document') {
      emit('translated', { text, name: targetName.value.trim(), truncated: truncated.value });
      close();
      return;
    }
    result.value = text;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('cortex.translate.failed');
  } finally {
    running.value = false;
  }
}

async function copyResult(): Promise<void> {
  if (!result.value) return;
  if (typeof navigator === 'undefined' || !navigator.clipboard) return;
  try {
    await navigator.clipboard.writeText(result.value);
    copied.value = true;
    window.setTimeout(() => { copied.value = false; }, 2000);
  } catch {
    // Clipboard access can be refused (permissions, insecure context). The
    // text is on screen and selectable, so this is a missing convenience
    // rather than a failure worth an error banner.
    copied.value = false;
  }
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :close-on-backdrop="false"
    :title="mode === 'selection'
      ? t('cortex.translate.titleSelection')
      : t('cortex.translate.title', { name: sourceName })"
    :size="result ? 'lg' : 'md'"
    @update:model-value="close"
  >
    <VAlert v-if="error" variant="error" class="mb-3">
      <span>{{ error }}</span>
    </VAlert>

    <VAlert v-if="tooLong" variant="warning" class="mb-3">
      <span>{{ t('cortex.translate.tooLong', {
        length: sourceText.length, max: TRANSLATE_MAX_CHARS,
      }) }}</span>
    </VAlert>

    <template v-if="result">
      <VAlert v-if="truncated" variant="warning" class="mb-3">
        <span>{{ t('cortex.translate.maybeTruncated') }}</span>
      </VAlert>
      <!-- Read-only but selectable: a disabled <textarea> is dimmed and, in
           some browsers, cannot be marked with the mouse — which is the one
           thing a reader wants to do here when the clipboard is unavailable. -->
      <span class="text-sm">{{ t('cortex.translate.resultLabel') }}</span>
      <pre
        class="mt-1 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded
               border border-base-300 p-2 text-sm"
      >{{ result }}</pre>
    </template>

    <div v-else class="flex flex-col gap-3">
      <VSelect
        v-model="language"
        :options="options"
        :label="t('cortex.translate.languageLabel')"
        :disabled="running"
      />
      <div v-if="language === CUSTOM">
        <VInput
          v-model="customLanguage"
          :label="t('cortex.translate.customLanguageLabel')"
          :placeholder="t('cortex.translate.customLanguagePlaceholder')"
          :disabled="running"
        />
      </div>
      <div v-if="mode === 'document'">
        <VInput
          :model-value="targetName"
          :label="t('cortex.translate.targetNameLabel')"
          :help="t('cortex.translate.targetNameHelp')"
          :disabled="running"
          @update:model-value="onNameInput"
        />
      </div>
      <p class="text-xs opacity-60">
        {{ t('cortex.translate.sourceInfo', { chars: sourceText.length }) }}
      </p>
    </div>

    <template #actions>
      <template v-if="result">
        <VButton variant="ghost" @click="copyResult">
          {{ copied ? t('cortex.translate.copied') : t('cortex.translate.copy') }}
        </VButton>
        <VButton variant="primary" @click="close">
          {{ t('cortex.translate.close') }}
        </VButton>
      </template>
      <template v-else>
        <VButton variant="ghost" :disabled="running" @click="close">
          {{ t('cortex.translate.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="running" :disabled="!canSubmit" @click="submit">
          {{ t('cortex.translate.submit') }}
        </VButton>
      </template>
    </template>
  </VModal>
</template>
