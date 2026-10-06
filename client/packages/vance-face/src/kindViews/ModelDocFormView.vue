<script setup lang="ts">
/**
 * Form view for `kind: vance-model` documents — one operator-managed model
 * document of the ModelCatalog (`_vance/model/<provider>/<slug>.yaml`, spec
 * `specification/public/llm-resource-management.md` §3a).
 *
 * The model is the whole YAML map (see `modelDocCodec.ts`): the form owns
 * exactly the fields it renders — display name, wire name, context/output
 * window, size, capabilities, timeout, the three known pricing keys — and
 * every other key (`$meta`, `messageParser`, `unsupportedParams`, …) passes
 * through untouched. A cleared field is a dropped key, and a dropped key
 * inherits from the next outer cascade layer: partial overrides are the
 * point of this document kind.
 *
 * The capabilities the form offers are the ones the server enum knows; a
 * document carrying an unknown capability keeps it (visible in the
 * preserved-fields count) instead of being silently snapped or dropped.
 */
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VCheckbox, VInput, VSelect } from '@/components';
import {
  MODEL_CAPABILITIES,
  MODEL_SIZES,
  applyForm,
  formFromDoc,
  preservedKeyCount,
  type ModelDoc,
  type ModelDocForm,
} from './modelDocCodec';

defineOptions({ name: 'ModelDocFormView' });

const props = defineProps<{
  /** Parsed model — the whole YAML map (identity: parse/serialize in the codec). */
  doc: ModelDoc;
  /** Supplied by the shell for location-aware views. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only binding greys its controls out. */
  readOnly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: ModelDoc): void;
}>();

const { t } = useI18n();

// ── Form state, re-derived whenever the shell hands us a new model ──────

const form = reactive<ModelDocForm>(formFromDoc(props.doc));

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
 * A model document outside `_vance/model/` is the same kind but inert — the
 * catalog reads exactly that tree. The form still edits it (it's a draft),
 * the banner says what's missing to make it take part.
 */
const isDraft = computed(
  () => !!props.docPath && !props.docPath.startsWith('_vance/model/'),
);

/** Unknown keys the form keeps but does not render (incl. pricing extras). */
const preserved = computed(() => preservedKeyCount(props.doc));

// ── Size select with raw degrade ────────────────────────────────────────

const CUSTOM_SIZE = '__custom__';

const sizeKnown = computed(() => (MODEL_SIZES as readonly string[]).includes(form.size.trim()));

const sizeChoice = computed<string>({
  get: () => (form.size.trim() === '' ? 'SMALL' : sizeKnown.value ? form.size.trim() : CUSTOM_SIZE),
  set: (v) => {
    if (v !== CUSTOM_SIZE) form.size = v;
  },
});

const sizeOptions = computed(() => [
  ...MODEL_SIZES.map((id) => ({ value: id, label: t(`modelDoc.sizes.${id}`) })),
  { value: CUSTOM_SIZE, label: t('modelDoc.form.sizeCustom') },
]);

// ── Capabilities: known ones as checkboxes ──────────────────────────────

function toggleCapability(id: string, on: boolean): void {
  const rest = form.capabilities.filter((c) => c !== id);
  form.capabilities = on ? [...rest, id] : rest;
}
</script>

<template>
  <div class="space-y-6 p-4">
    <VAlert v-if="isDraft" variant="warning">
      {{ t('modelDoc.form.draftWarning') }}
    </VAlert>

    <!-- ── Identity ─────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('modelDoc.form.sectionIdentity') }}
      </h3>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <VInput
          v-model="form.displayName"
          :label="t('modelDoc.form.displayName')"
          :help="t('modelDoc.form.displayNameHelp')"
          :disabled="disabled"
        />
        <VInput
          v-model="form.wireName"
          :label="t('modelDoc.form.wireName')"
          :help="t('modelDoc.form.wireNameHelp')"
          :disabled="disabled"
        />
      </div>
    </section>

    <!-- ── Limits ───────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('modelDoc.form.sectionLimits') }}
      </h3>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <VInput
          v-model="form.contextWindowTokens"
          type="number"
          :label="t('modelDoc.form.contextWindowTokens')"
          :help="t('modelDoc.form.contextWindowTokensHelp')"
          :disabled="disabled"
        />
        <VInput
          v-model="form.defaultMaxOutputTokens"
          type="number"
          :label="t('modelDoc.form.defaultMaxOutputTokens')"
          :help="t('modelDoc.form.defaultMaxOutputTokensHelp')"
          :disabled="disabled"
        />
        <div>
          <VSelect
            :model-value="sizeChoice"
            :options="sizeOptions"
            :label="t('modelDoc.form.size')"
            :help="t('modelDoc.form.sizeHelp')"
            :disabled="disabled"
            @update:model-value="(v: string | null) => { if (v) sizeChoice = v; }"
          />
        </div>
        <div v-if="sizeChoice === CUSTOM_SIZE">
          <VInput
            v-model="form.size"
            :label="t('modelDoc.form.sizeCustom')"
            required
            :disabled="disabled"
          />
        </div>
      </div>

      <VInput
        v-model="form.timeoutSeconds"
        type="number"
        :label="t('modelDoc.form.timeoutSeconds')"
        :help="t('modelDoc.form.timeoutSecondsHelp')"
        :disabled="disabled"
      />

      <div class="flex flex-wrap gap-x-6 gap-y-2">
        <VCheckbox
          v-for="cap in MODEL_CAPABILITIES"
          :key="cap"
          :model-value="form.capabilities.includes(cap)"
          :label="t(`modelDoc.capabilities.${cap}`)"
          :disabled="disabled"
          @update:model-value="(on: boolean) => toggleCapability(cap, on)"
        />
      </div>
    </section>

    <!-- ── Pricing ───────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('modelDoc.form.sectionPricing') }}
      </h3>
      <p class="text-xs text-base-content/60">{{ t('modelDoc.form.pricingHelp') }}</p>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <VInput
          v-model="form.pricingCurrency"
          :label="t('modelDoc.form.pricingCurrency')"
          :disabled="disabled"
        />
        <VInput
          v-model="form.pricingInputPerMTok"
          type="number"
          :label="t('modelDoc.form.pricingInputPerMTok')"
          :disabled="disabled"
        />
        <VInput
          v-model="form.pricingOutputPerMTok"
          type="number"
          :label="t('modelDoc.form.pricingOutputPerMTok')"
          :disabled="disabled"
        />
      </div>
    </section>

    <p v-if="preserved > 0" class="text-xs text-base-content/60">
      {{ t('modelDoc.form.preserved', { count: preserved }) }}
    </p>
  </div>
</template>
