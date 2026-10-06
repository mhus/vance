<script setup lang="ts">
/**
 * Form view for `kind: vance-model-provider` documents — the
 * `_provider.yaml` sidecar of one provider instance in the ModelCatalog
 * (`_vance/model/<provider>/_provider.yaml`, spec
 * `specification/public/llm-resource-management.md` §3a).
 *
 * The model is the whole YAML map (see `providerDocCodec.ts`): the form owns
 * exactly the fields it renders — display name, wire protocol, credential
 * shape, base URL — and every other key (`$meta`, provider-specific fields
 * this build does not know) passes through untouched. A cleared field is a
 * dropped key, and a dropped key inherits: partial sidecars are legal.
 *
 * The form never misrepresents a wire or auth type: a document using an id
 * this build does not list keeps it verbatim in a raw input instead of
 * snapping to one of the known ones. Same degrade rule as the other forms.
 */
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VInput, VSelect } from '@/components';
import {
  AUTH_TYPES,
  WIRE_TYPES,
  applyForm,
  formFromDoc,
  preservedKeyCount,
  type ProviderDoc,
  type ProviderDocForm,
} from './providerDocCodec';

defineOptions({ name: 'ProviderDocFormView' });

const props = defineProps<{
  /** Parsed sidecar — the whole YAML map (identity: parse/serialize in the codec). */
  doc: ProviderDoc;
  /** Supplied by the shell for location-aware views. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only binding greys its controls out. */
  readOnly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: ProviderDoc): void;
}>();

const { t } = useI18n();

// ── Form state, re-derived whenever the shell hands us a new model ──────

const form = reactive<ProviderDocForm>(formFromDoc(props.doc));

/** Guards the re-derivation against echoing the doc back as an edit. */
let suspend = false;

watch(
  () => props.doc,
  (doc) => {
    suspend = true;
    Object.assign(form, formFromDoc(doc));
    // One tick is enough: the reactive writes above are batched.
    setTimeout(() => {
      suspend = false;
    }, 0);
  },
);

// Any form change rebuilds the model: clone the incoming map, apply the
// owned keys, leave everything else as it is.
watch(
  form,
  () => {
    if (suspend) return;
    emit('update:doc', applyForm({ ...props.doc }, form));
  },
  { deep: true },
);

const disabled = computed(() => props.readOnly === true);

/**
 * A sidecar outside `_vance/model/` is the same kind but inert — the catalog
 * reads exactly that tree. The form still edits it (it's a draft), the
 * banner says what's missing to make it take part.
 */
const isDraft = computed(
  () => !!props.docPath && !props.docPath.startsWith('_vance/model/'),
);

/** Unknown keys the form keeps but does not render. */
const preserved = computed(() => preservedKeyCount(props.doc));

// ── Wire/auth selects with raw degrade ──────────────────────────────────

const CUSTOM_WIRE = '__custom__';
const CUSTOM_AUTH = '__custom__';

const wireKnown = computed(() => (WIRE_TYPES as readonly string[]).includes(form.wireType.trim()));
const authKnown = computed(() => (AUTH_TYPES as readonly string[]).includes(form.authType.trim()));

const wireChoice = computed<string>({
  get: () => (form.wireType.trim() === '' ? 'openai' : wireKnown.value ? form.wireType.trim() : CUSTOM_WIRE),
  set: (v) => {
    if (v !== CUSTOM_WIRE) form.wireType = v;
  },
});

const authChoice = computed<string>({
  get: () => (form.authType.trim() === '' ? 'api-key' : authKnown.value ? form.authType.trim() : CUSTOM_AUTH),
  set: (v) => {
    if (v !== CUSTOM_AUTH) form.authType = v;
  },
});

const wireOptions = computed(() => [
  ...WIRE_TYPES.map((id) => ({ value: id, label: id })),
  { value: CUSTOM_WIRE, label: t('providerDoc.form.wireTypeCustom') },
]);

const authOptions = computed(() => [
  ...AUTH_TYPES.map((id) => ({ value: id, label: id })),
  { value: CUSTOM_AUTH, label: t('providerDoc.form.authTypeCustom') },
]);
</script>

<template>
  <div class="space-y-6 p-4">
    <VAlert v-if="isDraft" variant="warning">
      {{ t('providerDoc.form.draftWarning') }}
    </VAlert>

    <!-- ── Provider instance ─────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('providerDoc.form.sectionProvider') }}
      </h3>
      <p class="text-xs text-base-content/60">{{ t('providerDoc.form.intro') }}</p>

      <VInput
        v-model="form.displayName"
        :label="t('providerDoc.form.displayName')"
        :help="t('providerDoc.form.displayNameHelp')"
        :disabled="disabled"
      />

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <VSelect
            :model-value="wireChoice"
            :options="wireOptions"
            :label="t('providerDoc.form.wireType')"
            :help="t('providerDoc.form.wireTypeHelp')"
            :disabled="disabled"
            @update:model-value="(v: string | null) => { if (v) wireChoice = v; }"
          />
        </div>
        <div v-if="wireChoice === CUSTOM_WIRE">
          <VInput
            v-model="form.wireType"
            :label="t('providerDoc.form.wireTypeCustom')"
            required
            :disabled="disabled"
          />
        </div>
        <div>
          <VSelect
            :model-value="authChoice"
            :options="authOptions"
            :label="t('providerDoc.form.authType')"
            :help="t('providerDoc.form.authTypeHelp')"
            :disabled="disabled"
            @update:model-value="(v: string | null) => { if (v) authChoice = v; }"
          />
        </div>
        <div v-if="authChoice === CUSTOM_AUTH">
          <VInput
            v-model="form.authType"
            :label="t('providerDoc.form.authTypeCustom')"
            required
            :disabled="disabled"
          />
        </div>
      </div>

      <VInput
        v-model="form.baseUrl"
        :label="t('providerDoc.form.baseUrl')"
        :help="t('providerDoc.form.baseUrlHelp')"
        :disabled="disabled"
      />
    </section>

    <p v-if="preserved > 0" class="text-xs text-base-content/60">
      {{ t('providerDoc.form.preserved', { count: preserved }) }}
    </p>
  </div>
</template>
