<script setup lang="ts">
/**
 * Form view for `kind: vance-mount-source` documents — one mounted external
 * tree of the Jaglan mount system (`_vance/config/mounts/<name>.yaml`, spec
 * `specification/public/jaglan-system.md`).
 *
 * The model is the whole YAML map (see `mountSourceCodec.ts`): the form owns
 * exactly the fields it renders — protocol, endpoint, credential, enabled,
 * and the two `local` extras rootDir/writable — and every other key
 * (`$meta`, `metadataTtlSeconds`, protocol fields this build does not know)
 * passes through untouched. Mutations bubble up as a fresh map through
 * `update:doc`; the shell re-serialises and saves through the ordinary
 * document pipeline (writing `_vance/config/` requires project admin).
 *
 * The form never misrepresents a protocol: a document using an id this build
 * does not list keeps it verbatim in a raw input instead of snapping to one
 * of the known ones. Same degrade rule as the research form.
 *
 * The kind marker is guaranteed on every save (`$meta.kind`), so a mount
 * written before this kind existed routes here even though its body started
 * without the marker.
 */
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VCheckbox, VInput, VSelect } from '@/components';
import {
  MOUNT_PROTOCOLS,
  applyForm,
  formFromDoc,
  preservedKeyCount,
  showsLocalFields,
  type MountSourceDoc,
  type MountSourceForm,
} from './mountSourceCodec';

defineOptions({ name: 'MountSourceFormView' });

const props = defineProps<{
  /** Parsed model — the whole YAML map (identity: parse/serialize in the codec). */
  doc: MountSourceDoc;
  /** Supplied by the shell for location-aware views. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only binding greys its controls out. */
  readOnly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: MountSourceDoc): void;
}>();

const { t } = useI18n();

/**
 * The two secret notations, interpolated into the help text so their braces
 * stay literal — a message containing them would be read as interpolation.
 */
const notation = { ref: '{{secret:vault:…}}', literal: '{noop}…' };

// ── Form state, re-derived whenever the shell hands us a new model ──────

const form = reactive<MountSourceForm>(formFromDoc(props.doc));

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
 * A mount source outside `_vance/config/mounts/` is the same kind but
 * inert — the mount factory reads exactly that folder. The form still edits
 * it (it's a draft), the banner says what's missing to make it take part.
 */
const isDraft = computed(
  () => !!props.docPath && !props.docPath.startsWith('_vance/config/mounts/'),
);

/** Unknown keys the form keeps but does not render. */
const preserved = computed(() => preservedKeyCount(props.doc));

// ── Protocol select with raw degrade ────────────────────────────────────

const CUSTOM_PROTOCOL = '__custom__';

const protocolKnown = computed(() =>
  (MOUNT_PROTOCOLS as readonly string[]).includes(form.protocol.trim()),
);

const protocolChoice = computed<string>({
  get: () => (form.protocol.trim() === '' ? 'local' : protocolKnown.value ? form.protocol.trim() : CUSTOM_PROTOCOL),
  set: (v) => {
    if (v !== CUSTOM_PROTOCOL) form.protocol = v;
  },
});

const protocolOptions = computed(() => [
  ...MOUNT_PROTOCOLS.map((id) => ({ value: id, label: t(`mountSource.protocols.${id}`) })),
  { value: CUSTOM_PROTOCOL, label: t('mountSource.form.protocolCustom') },
]);
</script>

<template>
  <div class="space-y-6 p-4">
    <VAlert v-if="isDraft" variant="warning">
      {{ t('mountSource.form.draftWarning') }}
    </VAlert>

    <!-- ── What it is ─────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('mountSource.form.sectionMount') }}
      </h3>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <VSelect
            :model-value="protocolChoice"
            :options="protocolOptions"
            :label="t('mountSource.form.protocol')"
            :help="t('mountSource.form.protocolHelp')"
            :disabled="disabled"
            @update:model-value="(v: string | null) => { if (v) protocolChoice = v; }"
          />
        </div>
        <div>
          <VInput
            v-if="protocolChoice === CUSTOM_PROTOCOL"
            v-model="form.protocol"
            :label="t('mountSource.form.protocolCustom')"
            :help="t('mountSource.form.protocolCustomHelp')"
            required
            :disabled="disabled"
          />
        </div>
      </div>

      <VInput
        v-if="!showsLocalFields(form.protocol.trim())"
        v-model="form.baseUrl"
        :label="t('mountSource.form.baseUrl')"
        :help="t('mountSource.form.baseUrlHelp')"
        required
        :disabled="disabled"
      />

      <VInput
        v-model="form.apiKey"
        type="password"
        :label="t('mountSource.form.apiKey')"
        :help="t('mountSource.form.apiKeyHelp', notation)"
        :disabled="disabled"
      />

      <div v-if="showsLocalFields(form.protocol.trim())" class="space-y-3">
        <VInput
          v-model="form.rootDir"
          :label="t('mountSource.form.rootDir')"
          :help="t('mountSource.form.rootDirHelp')"
          required
          :disabled="disabled"
        />
        <VCheckbox
          v-model="form.writable"
          :label="t('mountSource.form.writable')"
          :help="t('mountSource.form.writableHelp')"
          :disabled="disabled"
        />
      </div>

      <VCheckbox
        v-model="form.enabled"
        :label="t('mountSource.form.enabled')"
        :help="t('mountSource.form.enabledHelp')"
        :disabled="disabled"
      />
    </section>

    <p v-if="preserved > 0" class="text-xs text-base-content/60">
      {{ t('mountSource.form.preserved', { count: preserved }) }}
    </p>
  </div>
</template>
