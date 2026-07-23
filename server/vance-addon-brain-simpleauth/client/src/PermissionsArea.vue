<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { VAlert, VButton, VInput, VSelect } from '@vance/components';
import { listGrants, removeGrant, setGrant } from './api';
import type { GrantDto, GrantRole, GrantScopeType, GrantSubjectType } from './types';

const scopeTypeOptions: { value: GrantScopeType; label: string }[] = [
  { value: 'TENANT', label: 'Tenant' },
  { value: 'PROJECT', label: 'Project' },
];
const subjectTypeOptions: { value: GrantSubjectType; label: string }[] = [
  { value: 'USER', label: 'User' },
  { value: 'TEAM', label: 'Team' },
];
const roleOptions: { value: GrantRole; label: string }[] = [
  { value: 'READER', label: 'Reader' },
  { value: 'WRITER', label: 'Writer' },
  { value: 'ADMIN', label: 'Admin' },
];

// Scope being viewed/edited.
const scopeType = ref<GrantScopeType>('PROJECT');
const scopeId = ref<string>('');

// New-grant form.
const subjectType = ref<GrantSubjectType>('USER');
const subjectId = ref<string>('');
const role = ref<GrantRole>('WRITER');

const grants = ref<GrantDto[]>([]);
const loading = ref(false);
const error = ref<string>('');
const notice = ref<string>('');

function scopeReady(): boolean {
  return scopeType.value === 'TENANT' || scopeId.value.trim().length > 0;
}

async function load(): Promise<void> {
  error.value = '';
  notice.value = '';
  if (!scopeReady()) {
    grants.value = [];
    return;
  }
  loading.value = true;
  try {
    grants.value = await listGrants(scopeType.value, scopeId.value.trim());
  } catch (e) {
    error.value = messageOf(e);
    grants.value = [];
  } finally {
    loading.value = false;
  }
}

async function add(): Promise<void> {
  error.value = '';
  notice.value = '';
  if (!scopeReady() || subjectId.value.trim().length === 0) {
    error.value = 'Scope and subject are required.';
    return;
  }
  loading.value = true;
  try {
    await setGrant({
      scopeType: scopeType.value,
      scopeId: scopeId.value.trim(),
      subjectType: subjectType.value,
      subjectId: subjectId.value.trim(),
      role: role.value,
    });
    notice.value = `Granted ${role.value} to ${subjectType.value.toLowerCase()} '${subjectId.value.trim()}'.`;
    subjectId.value = '';
    await load();
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
}

async function revoke(g: GrantDto): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    await removeGrant(g.scopeType, g.scopeId, g.subjectType, g.subjectId);
    await load();
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
}

function messageOf(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}

onMounted(() => {
  const project = new URLSearchParams(window.location.search).get('project');
  if (project) {
    scopeType.value = 'PROJECT';
    scopeId.value = project;
  }
  void load();
});
</script>

<template>
  <div class="mx-auto max-w-3xl p-6 flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold">Permissions</h1>
      <p class="text-sm opacity-70">Grant and revoke roles for users and teams.</p>
    </div>

    <!-- Scope selector -->
    <div class="flex flex-wrap items-end gap-3">
      <div class="w-40">
        <VSelect v-model="scopeType" :options="scopeTypeOptions" label="Scope" @update:modelValue="load" />
      </div>
      <div v-if="scopeType === 'PROJECT'" class="flex-1 min-w-48">
        <VInput v-model="scopeId" label="Project" @update:modelValue="notice = ''" />
      </div>
      <VButton variant="secondary" :loading="loading" @click="load">Load</VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-else-if="notice" variant="success">{{ notice }}</VAlert>

    <!-- Existing grants -->
    <div class="flex flex-col gap-2">
      <div class="text-sm font-medium opacity-70">Current grants</div>
      <p v-if="grants.length === 0" class="text-sm opacity-60">No grants on this scope.</p>
      <ul v-else class="flex flex-col gap-2">
        <li
          v-for="g in grants"
          :key="g.subjectType + ':' + g.subjectId"
          class="flex items-center justify-between gap-3 rounded border border-base-300 px-3 py-2"
        >
          <span class="text-sm">
            <span class="font-mono">{{ g.subjectType.toLowerCase() }}:{{ g.subjectId }}</span>
            <span class="mx-2 opacity-50">→</span>
            <span class="font-semibold">{{ g.role }}</span>
          </span>
          <VButton variant="danger" size="sm" :disabled="loading" @click="revoke(g)">Revoke</VButton>
        </li>
      </ul>
    </div>

    <!-- Add grant -->
    <div class="flex flex-col gap-3 rounded border border-base-300 p-4">
      <div class="text-sm font-medium opacity-70">Grant a role</div>
      <div class="flex flex-wrap items-end gap-3">
        <div class="w-36">
          <VSelect v-model="subjectType" :options="subjectTypeOptions" label="Subject type" />
        </div>
        <div class="flex-1 min-w-48">
          <VInput v-model="subjectId" label="User / team name" />
        </div>
        <div class="w-36">
          <VSelect v-model="role" :options="roleOptions" label="Role" />
        </div>
        <VButton variant="primary" :loading="loading" @click="add">Grant</VButton>
      </div>
    </div>
  </div>
</template>
