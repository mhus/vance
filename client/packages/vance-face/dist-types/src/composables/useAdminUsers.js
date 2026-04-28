import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/** Admin CRUD on users — passwords go through a dedicated endpoint. */
export function useAdminUsers() {
    const users = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            users.value = await brainFetch('GET', 'admin/users');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load users.';
            users.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function create(req) {
        busy.value = true;
        error.value = null;
        try {
            const created = await brainFetch('POST', 'admin/users', { body: req });
            await reload();
            return created;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to create user.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function update(name, req) {
        busy.value = true;
        error.value = null;
        try {
            const saved = await brainFetch('PUT', `admin/users/${encodeURIComponent(name)}`, { body: req });
            await reload();
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to update user.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function setPassword(name, password) {
        busy.value = true;
        error.value = null;
        try {
            const body = { password };
            await brainFetch('PUT', `admin/users/${encodeURIComponent(name)}/password`, { body });
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to set password.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function remove(name) {
        busy.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `admin/users/${encodeURIComponent(name)}`);
            await reload();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete user.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { users, loading, busy, error, reload, create, update, setPassword, remove };
}
//# sourceMappingURL=useAdminUsers.js.map