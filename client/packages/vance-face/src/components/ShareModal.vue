<script setup lang="ts">
/**
 * Milliways — "show this to someone", with a reason.
 *
 * Two zones. The **subject** at the top says what is being shared — it comes
 * from the caller (Cortex a document, the search app a title, link and
 * snippet), and only its title is editable. Below it the **handler's form**:
 * this component knows nothing about recipients, mail addresses or subjects,
 * it renders whatever `FormFields` is handed. A new handler (server-side or
 * from an addon) shows up here without a change to this file.
 *
 * The subject deliberately does not live in the handler's form: it is not the
 * handler's business, and asking for it per handler would ask twice, possibly
 * differently.
 *
 * Unavailable handlers are listed greyed out with the reason they cannot be
 * used. A missing entry would read as "not possible"; a greyed one reads as
 * "here is the lever".
 *
 * See specification/public/milliways-system.md.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VInput, VModal } from '@vance/components';
import {
  RestError,
  fetchShareForm,
  listShareHandlers,
  pickLocalized,
  safeUrl,
  submitShare,
} from '@vance/shared';
import type { ShareHandlerDto, ShareSubjectDto } from '@vance/generated';
import FormFields, { type FormValue } from './FormFields.vue';

const props = defineProps<{
  modelValue: boolean;
  projectId: string;
  /**
   * What to share. At least one of `link` / `snippet` / `documentPath` must be
   * set — the server refuses a subject that names nothing to show.
   */
  subject: ShareSubjectDto;
}>();

const emit = defineEmits<{ (e: 'update:modelValue', open: boolean): void }>();

const { t, locale } = useI18n();

const handlers = ref<ShareHandlerDto[]>([]);
const picked = ref<ShareHandlerDto | null>(null);
const fields = ref<Awaited<ReturnType<typeof fetchShareForm>>['fields']>([]);
const values = ref<Record<string, FormValue>>({});
/** The title the sharer may adjust; starts from what the caller supplied. */
const title = ref('');
const loading = ref(false);
const submitting = ref(false);
const error = ref<string | null>(null);
const done = ref<string | null>(null);

const fileName = computed(() => {
  const path = props.subject.documentPath;
  return path ? path.split('/').pop() || path : null;
});

/** Mirrors the server's `displayTitle()` cascade for the header. */
const displayTitle = computed(() =>
  title.value.trim()
  || fileName.value
  || hostOf(props.subject.link)
  || t('share.untitledSubject'),
);

/** Only a link we would also render as an href gets shown as one. */
const linkHref = computed(() => safeUrl(props.subject.link));

function hostOf(raw: string | undefined): string | null {
  const safe = safeUrl(raw);
  if (!safe) return null;
  try {
    return new URL(safe).host || null;
  } catch {
    return null;
  }
}

/** The subject as it goes on the wire: the caller's, with the edited title. */
function outgoingSubject(): ShareSubjectDto {
  const trimmed = title.value.trim();
  return { ...props.subject, title: trimmed || undefined };
}

function labelOf(handler: ShareHandlerDto): string {
  return pickLocalized(handler.label, locale.value) || handler.id;
}

// `immediate` matters: the host sets the subject and opens the modal in the
// same tick, so this component mounts with `modelValue` already true and a
// plain watch would never fire — the dialog would show an empty handler list
// and never ask the server. Same trap VModal documents for showModal().
watch(() => props.modelValue, (open) => {
  if (open) void openFresh();
}, { immediate: true });

/** Every open starts from scratch — availability may have changed since. */
async function openFresh(): Promise<void> {
  picked.value = null;
  fields.value = [];
  values.value = {};
  title.value = props.subject.title ?? '';
  error.value = null;
  done.value = null;
  loading.value = true;
  try {
    handlers.value = await listShareHandlers(props.projectId, outgoingSubject());
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
}

async function pick(handler: ShareHandlerDto): Promise<void> {
  if (!handler.available) return;
  error.value = null;
  loading.value = true;
  try {
    const form = await fetchShareForm(handler.id, props.projectId, outgoingSubject());
    fields.value = form.fields ?? [];
    values.value = seed(form.fields ?? []);
    picked.value = handler;
  } catch (e) {
    // A 409 means the list we showed was stale — go back and re-read it
    // rather than leaving the user on a form that cannot be submitted.
    //
    // The message goes up after the reload, not before: openFresh() clears
    // `error` on its way in, so setting it first wiped the one sentence that
    // explains why the dialog just jumped back to the handler list.
    if (e instanceof RestError && e.status === 409) {
      const stale = e.message;
      await openFresh();
      error.value = stale;
      return;
    }
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
}

async function submit(): Promise<void> {
  const handler = picked.value;
  if (!handler) return;
  error.value = null;
  submitting.value = true;
  try {
    const result = await submitShare(
      handler.id, props.projectId, outgoingSubject(), values.value,
    );
    done.value = result.message;
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    submitting.value = false;
  }
}

/**
 * Initial form state in the encoding `FormFields` uses: multi-selects and
 * repeats are arrays, everything else a string.
 */
function seed(list: { name: string; type: string; defaultValue?: string }[]): Record<string, FormValue> {
  const out: Record<string, FormValue> = {};
  for (const field of list) {
    if (field.type === 'multi_select') out[field.name] = [];
    else if (field.type === 'repeat') out[field.name] = [];
    else out[field.name] = field.defaultValue ?? '';
  }
  return out;
}

function messageOf(e: unknown): string {
  if (e instanceof RestError && e.message) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return t('share.errorGeneric');
}

function back(): void {
  picked.value = null;
  error.value = null;
}

function close(): void {
  emit('update:modelValue', false);
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :close-on-backdrop="false"
    :title="t('share.title', { name: displayTitle })"
    @update:model-value="close"
  >
    <VAlert v-if="error" variant="error" class="mb-3">
      <span>{{ error }}</span>
    </VAlert>

    <VAlert v-if="done" variant="success">
      <span>{{ done }}</span>
    </VAlert>

    <template v-else>
      <!-- Subject zone: what is being shared. Supplied by the caller, not
           asked for per handler — only the title is the sharer's to adjust,
           because a search engine's headline is rarely what they want to say.
           Link and snippet are quotes and stay read-only. -->
      <section class="mb-4 flex flex-col gap-2">
        <VInput
          v-model="title"
          :label="t('share.subject.titleLabel')"
          :placeholder="displayTitle"
          :disabled="submitting"
        />
        <a
          v-if="linkHref"
          :href="linkHref"
          target="_blank"
          rel="noopener noreferrer nofollow"
          class="text-xs underline opacity-70 hover:opacity-100 break-all"
        >🔗 {{ props.subject.link }}</a>
        <!-- Foreign text: a quote, never Markdown. Whatever a search hit
             contains stays inert here. -->
        <blockquote
          v-if="props.subject.snippet"
          class="border-l-2 border-base-300 pl-2 text-xs opacity-70 whitespace-pre-wrap
                 max-h-24 overflow-y-auto"
        >{{ props.subject.snippet }}</blockquote>
        <span v-if="fileName" class="text-xs opacity-60 font-mono">
          📄 {{ props.subject.documentPath }}
        </span>
      </section>

      <template v-if="picked">
        <p class="text-sm opacity-70 mb-3">
          {{ t('share.viaLabel', { handler: labelOf(picked) }) }}
        </p>
        <FormFields v-model="values" :fields="fields" :disabled="submitting" />
      </template>

      <template v-else>
        <p v-if="loading" class="text-sm opacity-70">{{ t('share.loading') }}</p>
        <p v-else-if="handlers.length === 0" class="text-sm opacity-70">
          {{ t('share.noHandlers') }}
        </p>
        <ul v-else class="flex flex-col gap-2">
          <li v-for="handler in handlers" :key="handler.id">
            <button
              type="button"
              class="w-full text-left p-3 rounded border border-base-300
                     enabled:hover:bg-base-200 disabled:opacity-50
                     disabled:cursor-not-allowed"
              :disabled="!handler.available"
              :title="handler.statusText ?? undefined"
              @click="pick(handler)"
            >
              <span class="font-medium">{{ labelOf(handler) }}</span>
              <span v-if="handler.statusText" class="block text-xs opacity-70 mt-0.5">
                {{ handler.statusText }}
              </span>
            </button>
          </li>
        </ul>
      </template>
    </template>

    <template #actions>
      <VButton v-if="done" variant="primary" @click="close">
        {{ t('share.close') }}
      </VButton>
      <template v-else-if="picked">
        <VButton variant="ghost" :disabled="submitting" @click="back">
          {{ t('share.back') }}
        </VButton>
        <VButton variant="primary" :loading="submitting" @click="submit">
          {{ t('share.submit') }}
        </VButton>
      </template>
      <VButton v-else variant="ghost" @click="close">
        {{ t('share.cancel') }}
      </VButton>
    </template>
  </VModal>
</template>
