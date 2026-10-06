<script setup lang="ts">
import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  VAlert,
  VButton,
  VEmptyState,
  VInput,
  VSelect,
  VTextarea,
} from '@/components';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { isEncryptedSettingType } from '@vance/shared';
import { SettingType } from '@vance/generated';
import type { SettingDto } from '@vance/generated';

/**
 * The advanced key/value editor for one scope — the Settings page's
 * counterpart of the Scopes page's raw-settings tab. Same backend
 * (`useScopeSettings` → `AdminSettingsController`), same wire scopes;
 * this is a fresh, self-contained component rather than an extraction,
 * because the Strangler rule is to leave `ScopesApp.vue` untouched
 * until the cutover.
 *
 * <p>The listing endpoint is tenant-ADMIN enforced; for users without
 * that role the load fails with 403 and the error surface explains
 * that this editor is operator tooling. Guided Setting Forms remain
 * available either way — they authorize per scope at apply time.
 */

const props = defineProps<{
  /** Wire referenceType: {@code tenant}, {@code user} or {@code project}. */
  referenceType: 'tenant' | 'user' | 'project';
  /** Wire referenceId: tenant name, user login or project name. */
  referenceId: string;
}>();

const { t } = useI18n();
const settingsState = useScopeSettings();


const newSettingKey = ref('');
const newSettingType = ref<SettingType>(SettingType.STRING);
const newSettingValue = ref('');
const newSettingDescription = ref('');
const editingKey = ref<string | null>(null);
const editValue = ref('');
const editDescription = ref('');

// Load the scope's settings whenever the panel mounts or the scope
// changes — the listing is the panel's whole purpose; upsert/remove
// only reload as a side-effect of a write. Without this the tab
// renders the empty state until the first mutation. Watch AFTER the
// refs above: immediate:true runs the callback synchronously during
// setup, where an earlier-positioned watch would hit the refs' TDZ.
watch(
  () => [props.referenceType, props.referenceId] as const,
  ([type, id]) => {
    editingKey.value = null;
    void settingsState.load(type, id);
  },
  { immediate: true },
);

const settingTypeOptions = [
  { value: SettingType.STRING, label: t('settings.raw.types.string') },
  { value: SettingType.INT, label: t('settings.raw.types.int') },
  { value: SettingType.LONG, label: t('settings.raw.types.long') },
  { value: SettingType.DOUBLE, label: t('settings.raw.types.double') },
  { value: SettingType.BOOLEAN, label: t('settings.raw.types.boolean') },
  { value: SettingType.PASSWORD, label: t('settings.raw.types.password') },
  { value: SettingType.HIDDEN, label: t('settings.raw.types.hidden') },
];

function settingValueLabel(s: SettingDto): string {
  // A null value and an empty string mean different things in the cascade —
  // null keeps falling through to the outer scope, "" stops the cascade
  // here — so they must not render identically.
  if (isEncryptedSettingType(s.type)) {
    return s.value ? t('settings.raw.maskedSet') : t('settings.raw.empty');
  }
  if (s.value === null || s.value === undefined) return t('settings.raw.empty');
  if (s.value === '') return t('settings.raw.explicitEmpty');
  return s.value;
}

function startEditSetting(s: SettingDto): void {
  editingKey.value = s.key;
  // Password values come back masked — clear the edit field so the
  // operator types a fresh password instead of editing the mask.
  editValue.value = isEncryptedSettingType(s.type) ? '' : (s.value ?? '');
  editDescription.value = s.description ?? '';
}

function cancelEditSetting(): void {
  editingKey.value = null;
  editValue.value = '';
  editDescription.value = '';
}

async function saveEditSetting(s: SettingDto): Promise<void> {
  try {
    await settingsState.upsert(
      props.referenceType,
      props.referenceId,
      s.key,
      // Empty input on an encrypted type means "unchanged" — the server
      // keeps the stored ciphertext; for plain types it writes "" (the
      // explicit-empty cascade breaker).
      editValue.value === '' && isEncryptedSettingType(s.type)
        ? null
        : editValue.value,
      s.type,
      editDescription.value,
    );
    cancelEditSetting();
  } catch {
    // useScopeSettings surfaced the message — keep the editor open so
    // the input is not lost.
  }
}

async function deleteSetting(s: SettingDto): Promise<void> {
  if (!window.confirm(t('settings.raw.confirmDelete', { key: s.key }))) return;
  try {
    await settingsState.remove(props.referenceType, props.referenceId, s.key);
  } catch {
    // surfaced via settingsState.error
  }
}

async function addSetting(): Promise<void> {
  const key = newSettingKey.value.trim();
  if (!key) return;
  try {
    await settingsState.upsert(
      props.referenceType,
      props.referenceId,
      key,
      newSettingValue.value === '' && isEncryptedSettingType(newSettingType.value)
        ? null
        : newSettingValue.value,
      newSettingType.value,
      newSettingDescription.value || null,
    );
    newSettingKey.value = '';
    newSettingValue.value = '';
    newSettingDescription.value = '';
  } catch {
    // surfaced via settingsState.error
  }
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="settingsState.error.value" variant="error">
      {{ settingsState.error.value }}
    </VAlert>

    <VEmptyState
      v-if="!settingsState.loading.value && settingsState.settings.value.length === 0"
      :headline="t('settings.raw.emptyHeadline')"
      :body="t('settings.raw.emptyBody')"
    />

    <ul class="flex flex-col divide-y divide-base-300">
      <li
        v-for="s in settingsState.settings.value"
        :key="s.key"
        class="py-2 flex flex-col gap-1"
      >
        <div class="flex items-center justify-between gap-2">
          <span class="font-mono text-sm truncate">{{ s.key }}</span>
          <span class="opacity-60 text-xs">{{ s.type }}</span>
        </div>
        <template v-if="editingKey === s.key">
          <VInput
            v-if="!isEncryptedSettingType(s.type)"
            v-model="editValue"
            :label="t('settings.raw.valueLabel')"
          />
          <VInput
            v-else
            v-model="editValue"
            type="password"
            :label="t('settings.raw.newPasswordLabel')"
            :placeholder="t('settings.raw.passwordEmptyToKeep')"
          />
          <VTextarea
            v-model="editDescription"
            :label="t('settings.raw.descriptionLabel')"
            :rows="2"
          />
          <div class="flex justify-end gap-2">
            <VButton variant="ghost" size="sm" @click="cancelEditSetting">
              {{ t('settings.raw.cancel') }}
            </VButton>
            <VButton
              variant="primary"
              size="sm"
              :loading="settingsState.busy.value"
              @click="saveEditSetting(s)"
            >{{ t('settings.raw.save') }}</VButton>
          </div>
        </template>
        <template v-else>
          <div class="text-sm break-words opacity-80">{{ settingValueLabel(s) }}</div>
          <div v-if="s.description" class="text-xs opacity-60">{{ s.description }}</div>
          <div class="flex justify-end gap-2">
            <VButton variant="ghost" size="sm" @click="startEditSetting(s)">
              {{ t('settings.raw.edit') }}
            </VButton>
            <VButton variant="ghost" size="sm" @click="deleteSetting(s)">
              {{ t('settings.raw.deleteLabel') }}
            </VButton>
          </div>
        </template>
      </li>
    </ul>

    <div class="border-t border-base-300 pt-3 flex flex-col gap-2">
      <h4 class="text-xs uppercase opacity-60">{{ t('settings.raw.addTitle') }}</h4>
      <VInput
        v-model="newSettingKey"
        :label="t('settings.raw.keyLabel')"
        :placeholder="t('settings.raw.keyPlaceholder')"
      />
      <VSelect
        v-model="newSettingType"
        :label="t('settings.raw.typeLabel')"
        :options="settingTypeOptions"
      />
      <VInput
        v-if="!isEncryptedSettingType(newSettingType)"
        v-model="newSettingValue"
        :label="t('settings.raw.valueLabel')"
      />
      <VInput
        v-else
        v-model="newSettingValue"
        type="password"
        :label="t('settings.raw.passwordLabel')"
      />
      <VTextarea
        v-model="newSettingDescription"
        :label="t('settings.raw.descriptionOptional')"
        :rows="2"
      />
      <VButton
        variant="primary"
        size="sm"
        :disabled="!newSettingKey.trim()"
        :loading="settingsState.busy.value"
        @click="addSetting"
      >{{ t('settings.raw.add') }}</VButton>
    </div>
  </div>
</template>
