<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import {
  EditorShell,
  MarkdownView,
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  VTextarea,
} from '@/components';
import { useAdminUsers } from '@/composables/useAdminUsers';
import { useAdminTeams } from '@/composables/useAdminTeams';
import { useHelp } from '@/composables/useHelp';
import { getUsername } from '@vance/shared';
import type { TeamDto, UserDto } from '@vance/generated';

const usersState = useAdminUsers();
const teamsState = useAdminTeams();
const help = useHelp();
const currentUsername = getUsername() ?? '';

type Selection =
  | { kind: 'user'; name: string }
  | { kind: 'team'; name: string };

const selection = ref<Selection | null>(null);
const banner = ref<string | null>(null);
const formError = ref<string | null>(null);

// ─── User form state ────────────────────────────────────────────────────
const userForm = reactive({
  title: '',
  email: '',
  status: 'ACTIVE',
});
const userStatusOptions = [
  { value: 'ACTIVE', label: 'ACTIVE' },
  { value: 'DISABLED', label: 'DISABLED' },
  { value: 'PENDING', label: 'PENDING' },
];

// ─── Team form state ────────────────────────────────────────────────────
const teamForm = reactive({
  title: '',
  enabled: true,
  membersText: '',
});

// ─── Modals ─────────────────────────────────────────────────────────────
const showCreateUser = ref(false);
const newUserName = ref('');
const newUserTitle = ref('');
const newUserEmail = ref('');
const newUserPassword = ref('');
const newUserError = ref<string | null>(null);

const showCreateTeam = ref(false);
const newTeamName = ref('');
const newTeamTitle = ref('');
const newTeamMembersText = ref('');
const newTeamError = ref<string | null>(null);

const showSetPassword = ref(false);
const passwordPlaintext = ref('');
const passwordPlaintextRepeat = ref('');
const passwordError = ref<string | null>(null);

const NAME_PATTERN_USER = /^[a-z0-9][a-z0-9_.-]*$/;
const NAME_PATTERN_TEAM = /^[a-z0-9][a-z0-9_-]*$/;

// ─── Derived ────────────────────────────────────────────────────────────

const selectedUser = computed<UserDto | null>(() => {
  const sel = selection.value;
  if (sel?.kind !== 'user') return null;
  return usersState.users.value.find(u => u.name === sel.name) ?? null;
});

const selectedTeam = computed<TeamDto | null>(() => {
  const sel = selection.value;
  if (sel?.kind !== 'team') return null;
  return teamsState.teams.value.find(t => t.name === sel.name) ?? null;
});

const isOwnAccount = computed(() =>
  selectedUser.value?.name === currentUsername);

const breadcrumbs = computed<string[]>(() => {
  const sel = selection.value;
  if (!sel) return [];
  return [sel.kind === 'user' ? `User: ${sel.name}` : `Team: ${sel.name}`];
});

const combinedError = computed<string | null>(() =>
  usersState.error.value || teamsState.error.value);

// ─── Lifecycle ──────────────────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([
    usersState.reload(),
    teamsState.reload(),
    help.load('user-team-admin.md'),
  ]);
});

watch(selection, () => {
  banner.value = null;
  formError.value = null;
  populateForm();
});

watch(() => selectedUser.value, () => {
  if (selection.value?.kind === 'user') populateForm();
});
watch(() => selectedTeam.value, () => {
  if (selection.value?.kind === 'team') populateForm();
});

function populateForm(): void {
  if (selectedUser.value) {
    userForm.title = selectedUser.value.title ?? '';
    userForm.email = selectedUser.value.email ?? '';
    userForm.status = selectedUser.value.status;
  } else if (selectedTeam.value) {
    teamForm.title = selectedTeam.value.title ?? '';
    teamForm.enabled = selectedTeam.value.enabled;
    teamForm.membersText = selectedTeam.value.members.join('\n');
  }
}

// ─── Selection ──────────────────────────────────────────────────────────

function selectUser(name: string): void {
  selection.value = { kind: 'user', name };
}
function selectTeam(name: string): void {
  selection.value = { kind: 'team', name };
}

function isSelectedUser(u: UserDto): boolean {
  return selection.value?.kind === 'user' && selection.value.name === u.name;
}
function isSelectedTeam(t: TeamDto): boolean {
  return selection.value?.kind === 'team' && selection.value.name === t.name;
}

// ─── Save / Delete ──────────────────────────────────────────────────────

async function saveUser(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  formError.value = null;
  banner.value = null;
  if (userForm.status === 'DISABLED' && selection.value.name === currentUsername) {
    formError.value = 'You cannot disable your own account.';
    return;
  }
  try {
    await usersState.update(selection.value.name, {
      title: userForm.title,
      email: userForm.email,
      status: userForm.status,
    });
    banner.value = 'User saved.';
  } catch {
    /* error in usersState.error */
  }
}

async function deleteUser(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  if (selection.value.name === currentUsername) {
    formError.value = 'You cannot delete your own account.';
    return;
  }
  if (!confirm(`Delete user "${selection.value.name}"? Memberships in teams are not auto-cleaned.`)) return;
  const name = selection.value.name;
  try {
    await usersState.remove(name);
    selection.value = null;
    banner.value = `User "${name}" deleted.`;
  } catch { /* state.error */ }
}

async function saveTeam(): Promise<void> {
  if (selection.value?.kind !== 'team') return;
  formError.value = null;
  banner.value = null;
  try {
    await teamsState.update(selection.value.name, {
      title: teamForm.title,
      enabled: teamForm.enabled,
      members: splitLines(teamForm.membersText),
    });
    banner.value = 'Team saved.';
  } catch { /* state.error */ }
}

async function deleteTeam(): Promise<void> {
  if (selection.value?.kind !== 'team') return;
  if (!confirm(`Delete team "${selection.value.name}"?`)) return;
  const name = selection.value.name;
  try {
    await teamsState.remove(name);
    selection.value = null;
    banner.value = `Team "${name}" deleted.`;
  } catch { /* state.error */ }
}

// ─── Create modals ──────────────────────────────────────────────────────

function openCreateUser(): void {
  newUserName.value = '';
  newUserTitle.value = '';
  newUserEmail.value = '';
  newUserPassword.value = '';
  newUserError.value = null;
  showCreateUser.value = true;
}

async function submitCreateUser(): Promise<void> {
  newUserError.value = null;
  const name = newUserName.value.trim();
  if (!name || !NAME_PATTERN_USER.test(name)) {
    newUserError.value = 'Name must be lower-case alphanumerics with optional ".", "-" or "_".';
    return;
  }
  if (usersState.users.value.some(u => u.name === name)) {
    newUserError.value = `A user named "${name}" already exists.`;
    return;
  }
  try {
    await usersState.create({
      name,
      title: newUserTitle.value.trim() || undefined,
      email: newUserEmail.value.trim() || undefined,
      password: newUserPassword.value || undefined,
    });
    showCreateUser.value = false;
    selectUser(name);
    banner.value = `User "${name}" created.`;
  } catch (e) {
    newUserError.value = e instanceof Error ? e.message : 'Failed to create user.';
  }
}

function openCreateTeam(): void {
  newTeamName.value = '';
  newTeamTitle.value = '';
  newTeamMembersText.value = '';
  newTeamError.value = null;
  showCreateTeam.value = true;
}

async function submitCreateTeam(): Promise<void> {
  newTeamError.value = null;
  const name = newTeamName.value.trim();
  if (!name || !NAME_PATTERN_TEAM.test(name)) {
    newTeamError.value = 'Name must be lower-case alphanumerics with optional "-" or "_".';
    return;
  }
  if (teamsState.teams.value.some(t => t.name === name)) {
    newTeamError.value = `A team named "${name}" already exists.`;
    return;
  }
  try {
    await teamsState.create({
      name,
      title: newTeamTitle.value.trim() || undefined,
      members: splitLines(newTeamMembersText.value),
    });
    showCreateTeam.value = false;
    selectTeam(name);
    banner.value = `Team "${name}" created.`;
  } catch (e) {
    newTeamError.value = e instanceof Error ? e.message : 'Failed to create team.';
  }
}

// ─── Set password ───────────────────────────────────────────────────────

function openSetPassword(): void {
  passwordPlaintext.value = '';
  passwordPlaintextRepeat.value = '';
  passwordError.value = null;
  showSetPassword.value = true;
}

async function submitSetPassword(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  passwordError.value = null;
  const pw = passwordPlaintext.value;
  if (!pw) {
    passwordError.value = 'Password is required.';
    return;
  }
  if (pw !== passwordPlaintextRepeat.value) {
    passwordError.value = 'Passwords do not match.';
    return;
  }
  try {
    await usersState.setPassword(selection.value.name, pw);
    showSetPassword.value = false;
    banner.value = `Password updated for "${selection.value.name}".`;
  } catch (e) {
    passwordError.value = e instanceof Error ? e.message : 'Failed to set password.';
  }
}

// ─── Helpers ────────────────────────────────────────────────────────────

function splitLines(s: string): string[] {
  return s
    .split(/[\n,]/)
    .map(x => x.trim())
    .filter(x => x.length > 0);
}

function fmt(value: unknown): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString();
  return String(value);
}
</script>

<template>
  <EditorShell title="Users & Teams" :breadcrumbs="breadcrumbs" wide-right-panel>
    <!-- ─── Sidebar ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-3 p-2">
        <section>
          <div class="flex items-center justify-between px-2 mb-1">
            <span class="text-xs uppercase opacity-50">Users</span>
            <VButton variant="ghost" size="sm" @click="openCreateUser">+ User</VButton>
          </div>
          <div v-if="usersState.loading.value" class="px-2 text-xs opacity-60">
            Loading…
          </div>
          <button
            v-for="u in usersState.users.value"
            :key="'u-' + u.name"
            type="button"
            class="row-item"
            :class="{ 'row-item--active': isSelectedUser(u) }"
            @click="selectUser(u.name)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ u.name }}</span>
              <span
                class="text-xs px-1.5 py-0.5 rounded"
                :class="{
                  'badge-active': u.status === 'ACTIVE',
                  'badge-disabled': u.status === 'DISABLED',
                  'badge-pending': u.status === 'PENDING',
                }"
              >{{ u.status?.toLowerCase() }}</span>
            </div>
            <div v-if="u.title || u.email" class="text-xs opacity-60 truncate">
              {{ u.title }}<span v-if="u.title && u.email"> · </span>{{ u.email }}
            </div>
          </button>
        </section>

        <section>
          <div class="flex items-center justify-between px-2 mb-1">
            <span class="text-xs uppercase opacity-50">Teams</span>
            <VButton variant="ghost" size="sm" @click="openCreateTeam">+ Team</VButton>
          </div>
          <div v-if="teamsState.loading.value" class="px-2 text-xs opacity-60">
            Loading…
          </div>
          <button
            v-for="t in teamsState.teams.value"
            :key="'t-' + t.name"
            type="button"
            class="row-item"
            :class="{ 'row-item--active': isSelectedTeam(t) }"
            @click="selectTeam(t.name)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ t.name }}</span>
              <span class="text-xs opacity-60">{{ t.members.length }} member<span v-if="t.members.length !== 1">s</span></span>
            </div>
            <div class="text-xs opacity-60 truncate">
              {{ t.title }}
              <span v-if="!t.enabled">· disabled</span>
            </div>
          </button>
        </section>
      </nav>
    </template>

    <!-- ─── Main ─── -->
    <div class="p-6 flex flex-col gap-3 max-w-3xl">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>
      <VAlert v-if="formError" variant="error">
        <span>{{ formError }}</span>
      </VAlert>

      <VEmptyState
        v-if="!selection"
        headline="Pick a user or team"
        body="Use the lists on the left, or create a new entry with + User / + Team."
      />

      <!-- ─── User detail ─── -->
      <template v-else-if="selection.kind === 'user'">
        <div v-if="!selectedUser" class="opacity-70">Loading…</div>
        <template v-else>
          <VCard :title="`User: ${selectedUser.name}`">
            <VAlert v-if="isOwnAccount" variant="info" class="mb-3">
              <span>This is your own account. Disable / delete are blocked here.</span>
            </VAlert>
            <div class="flex flex-col gap-3">
              <VInput
                :model-value="selectedUser.name"
                label="Name"
                disabled
                help="User name is immutable."
                @update:model-value="() => {}"
              />
              <VInput v-model="userForm.title" label="Title" />
              <VInput v-model="userForm.email" label="Email" type="email" />
              <VSelect
                v-model="userForm.status"
                :options="userStatusOptions"
                label="Status"
              />
              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
                <dt class="opacity-60">Created</dt><dd>{{ fmt(selectedUser.createdAt) }}</dd>
              </dl>
              <div class="flex justify-between">
                <div class="flex gap-2">
                  <VButton
                    variant="danger"
                    :disabled="isOwnAccount"
                    :loading="usersState.busy.value"
                    @click="deleteUser"
                  >Delete</VButton>
                  <VButton variant="ghost" @click="openSetPassword">Set password…</VButton>
                </div>
                <VButton variant="primary" :loading="usersState.busy.value" @click="saveUser">
                  Save
                </VButton>
              </div>
            </div>
          </VCard>
        </template>
      </template>

      <!-- ─── Team detail ─── -->
      <template v-else-if="selection.kind === 'team'">
        <div v-if="!selectedTeam" class="opacity-70">Loading…</div>
        <template v-else>
          <VCard :title="`Team: ${selectedTeam.name}`">
            <div class="flex flex-col gap-3">
              <VInput
                :model-value="selectedTeam.name"
                label="Name"
                disabled
                help="Team name is immutable."
                @update:model-value="() => {}"
              />
              <VInput v-model="teamForm.title" label="Title" />
              <VCheckbox v-model="teamForm.enabled" label="Enabled" />
              <VTextarea
                v-model="teamForm.membersText"
                label="Members"
                placeholder="One username per line. Removing a line drops the member."
                :rows="6"
                :help="`${splitLines(teamForm.membersText).length} member${
                  splitLines(teamForm.membersText).length === 1 ? '' : 's'
                }`"
              />
              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
                <dt class="opacity-60">Created</dt><dd>{{ fmt(selectedTeam.createdAt) }}</dd>
              </dl>
              <div class="flex justify-between">
                <VButton
                  variant="danger"
                  :loading="teamsState.busy.value"
                  @click="deleteTeam"
                >Delete</VButton>
                <VButton variant="primary" :loading="teamsState.busy.value" @click="saveTeam">
                  Save
                </VButton>
              </div>
            </div>
          </VCard>
        </template>
      </template>
    </div>

    <!-- ─── Right panel: help ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <h3 class="text-xs uppercase opacity-60 mb-2">User & team admin</h3>
        <div v-if="help.loading.value" class="text-xs opacity-60">Loading…</div>
        <div v-else-if="help.error.value" class="text-xs opacity-60">
          Help unavailable: {{ help.error.value }}
        </div>
        <div v-else-if="!help.content.value" class="text-xs opacity-60">
          No help content.
        </div>
        <MarkdownView v-else :source="help.content.value" />
      </div>
    </template>

    <!-- ─── Create-user modal ─── -->
    <VModal v-model="showCreateUser" title="New user">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newUserError" variant="error">
          <span>{{ newUserError }}</span>
        </VAlert>
        <VInput
          v-model="newUserName"
          label="Name"
          required
          help="Lower-case alphanumerics with optional '.', '-' or '_'."
        />
        <VInput v-model="newUserTitle" label="Title" />
        <VInput v-model="newUserEmail" label="Email" type="email" />
        <VInput
          v-model="newUserPassword"
          label="Password (optional)"
          type="password"
          help="Empty creates a passwordless account that cannot log in until you set one."
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateUser = false">Cancel</VButton>
          <VButton
            variant="primary"
            :loading="usersState.busy.value"
            @click="submitCreateUser"
          >Create</VButton>
        </div>
      </div>
    </VModal>

    <!-- ─── Create-team modal ─── -->
    <VModal v-model="showCreateTeam" title="New team">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newTeamError" variant="error">
          <span>{{ newTeamError }}</span>
        </VAlert>
        <VInput
          v-model="newTeamName"
          label="Name"
          required
          help="Lower-case alphanumerics with optional '-' or '_'."
        />
        <VInput v-model="newTeamTitle" label="Title" />
        <VTextarea
          v-model="newTeamMembersText"
          label="Initial members"
          placeholder="One username per line."
          :rows="4"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateTeam = false">Cancel</VButton>
          <VButton
            variant="primary"
            :loading="teamsState.busy.value"
            @click="submitCreateTeam"
          >Create</VButton>
        </div>
      </div>
    </VModal>

    <!-- ─── Set-password modal ─── -->
    <VModal v-model="showSetPassword" title="Set password">
      <div class="flex flex-col gap-3">
        <VAlert v-if="passwordError" variant="error">
          <span>{{ passwordError }}</span>
        </VAlert>
        <p class="text-sm opacity-80">
          Replaces the password for
          <strong>{{ selection?.kind === 'user' ? selection.name : '' }}</strong>.
          Plaintext is hashed server-side.
        </p>
        <VInput
          v-model="passwordPlaintext"
          label="New password"
          type="password"
          required
          autocomplete="new-password"
        />
        <VInput
          v-model="passwordPlaintextRepeat"
          label="Repeat password"
          type="password"
          required
          autocomplete="new-password"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showSetPassword = false">Cancel</VButton>
          <VButton
            variant="primary"
            :loading="usersState.busy.value"
            @click="submitSetPassword"
          >Set password</VButton>
        </div>
      </div>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.row-item {
  display: block;
  text-align: left;
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
  margin-bottom: 0.15rem;
}
.row-item:hover { background: hsl(var(--bc) / 0.06); }
.row-item--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}

.badge-active   { background: hsl(var(--su) / 0.18); color: hsl(var(--suc)); }
.badge-disabled { background: hsl(var(--bc) / 0.1);  color: hsl(var(--bc) / 0.6); }
.badge-pending  { background: hsl(var(--wa) / 0.18); color: hsl(var(--wac)); }
</style>
