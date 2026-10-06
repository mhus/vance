<script setup lang="ts">
import { onMounted, reactive } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton, VCard, VCheckbox, VInput } from '@/components';
import { useAdminTenant } from '@/composables/useAdminTenant';

/**
 * Tenant properties card — the port of the Scopes page's tenant card:
 * immutable {@code name}, editable {@code title} / {@code enabled}, save
 * through the {@code admin/tenant} endpoints via {@link useAdminTenant}.
 * Same i18n keys as Scopes ({@code scopes.tenant.*}, {@code scopes.common.*}).
 */
const emit = defineEmits<{ (e: 'banner', message: string | null): void }>();

const { t } = useI18n();
const tenantState = useAdminTenant();

const form = reactive({ title: '', enabled: true });

function applyTenantToForm(): void {
  const tenant = tenantState.tenant.value;
  form.title = tenant?.title ?? '';
  form.enabled = tenant?.enabled ?? true;
}

onMounted(async () => {
  await tenantState.reload();
  applyTenantToForm();
});

async function saveTenant(): Promise<void> {
  try {
    await tenantState.save({
      title: form.title,
      enabled: form.enabled,
    });
    applyTenantToForm();
    emit('banner', t('scopes.tenant.saved'));
  } catch {
    /* error already in tenantState.error */
  }
}
</script>

<template>
  <VCard :title="$t('scopes.tenant.cardTitle')">
    <div v-if="!tenantState.tenant.value" class="opacity-70">{{ $t('scopes.loading') }}</div>
    <div v-else class="flex flex-col gap-3">
      <VInput
        :model-value="tenantState.tenant.value.name"
        :label="$t('scopes.common.name')"
        disabled
        :help="$t('scopes.tenant.nameImmutable')"
        @update:model-value="() => {}"
      />
      <VInput v-model="form.title" :label="$t('scopes.common.title')" />
      <VCheckbox v-model="form.enabled" :label="$t('scopes.common.enabled')" />
      <div class="flex justify-end">
        <VButton variant="primary" :loading="tenantState.saving.value" @click="saveTenant">
          {{ $t('scopes.common.save') }}
        </VButton>
      </div>
    </div>
  </VCard>
</template>
