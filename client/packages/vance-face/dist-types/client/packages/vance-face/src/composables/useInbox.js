import { ref } from 'vue';
import { AnswerOutcome, InboxItemStatus, } from '@vance/generated';
import { brainFetch } from '@vance/shared';
/** Map an {@link AssignedToFilter} to the wire {@code ?assignedTo=} param. */
function encodeAssignedTo(a) {
    switch (a.kind) {
        case 'self': return null;
        case 'team': return `team:${a.teamName}`;
        case 'user': return a.userId;
    }
}
/**
 * Reactive wrapper around the inbox REST endpoints. One instance
 * per editor instance — exposes the active list, the selected
 * item, and mutation helpers.
 */
export function useInbox() {
    const items = ref([]);
    const selected = ref(null);
    const tags = ref([]);
    const loading = ref(false);
    const error = ref(null);
    const filter = ref({
        assignedTo: { kind: 'self' },
        status: InboxItemStatus.PENDING,
        tag: null,
    });
    async function loadList(next) {
        loading.value = true;
        error.value = null;
        filter.value = next;
        try {
            const params = new URLSearchParams();
            const a = encodeAssignedTo(next.assignedTo);
            if (a)
                params.set('assignedTo', a);
            if (next.status !== null && next.status !== undefined) {
                // Numerical TS-enum → backend's string name via reverse lookup.
                params.set('status', InboxItemStatus[next.status]);
            }
            if (next.tag)
                params.set('tag', next.tag);
            const qs = params.toString();
            const data = await brainFetch('GET', qs ? `inbox?${qs}` : 'inbox');
            items.value = data.items ?? [];
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load inbox.';
        }
        finally {
            loading.value = false;
        }
    }
    async function loadOne(id) {
        loading.value = true;
        error.value = null;
        try {
            selected.value = await brainFetch('GET', `inbox/${encodeURIComponent(id)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load item.';
        }
        finally {
            loading.value = false;
        }
    }
    async function loadTags() {
        try {
            const data = await brainFetch('GET', 'inbox/tags');
            tags.value = data.tags ?? [];
        }
        catch (e) {
            // Tags are a UX nicety — non-fatal. Log and clear.
            tags.value = [];
            console.warn('Failed to load inbox tags', e);
        }
    }
    function clearSelection() {
        selected.value = null;
    }
    /**
     * Apply a mutation result to local state — replace `selected`
     * if it was the touched item, and refresh the row in `items`.
     * Returns `true` so the caller can chain.
     */
    function applyMutation(updated) {
        if (selected.value?.id === updated.id) {
            selected.value = updated;
        }
        const idx = items.value.findIndex((i) => i.id === updated.id);
        if (idx >= 0) {
            // After a mutation the item often falls out of the active
            // filter (e.g. answered → no longer PENDING). The strict
            // approach is to drop it; we go with replace-in-place so
            // the user keeps context, and the next loadList reconciles.
            items.value[idx] = updated;
        }
        return true;
    }
    async function answer(id, outcome, value, reason) {
        error.value = null;
        try {
            // The wire-form uses the enum's STRING name (Spring/Jackson
            // deserialises @JsonValue-style by default). The generated
            // TS-enum is numeric, so reverse-lookup gives us the name.
            const body = {
                itemId: id,
                outcome: AnswerOutcome[outcome],
                value: value ?? undefined,
                reason: reason ?? undefined,
            };
            const updated = await brainFetch('POST', `inbox/${encodeURIComponent(id)}/answer`, { body });
            return applyMutation(updated);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to answer.';
            return false;
        }
    }
    async function archive(id) {
        error.value = null;
        try {
            const updated = await brainFetch('POST', `inbox/${encodeURIComponent(id)}/archive`);
            return applyMutation(updated);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to archive.';
            return false;
        }
    }
    async function unarchive(id) {
        error.value = null;
        try {
            const updated = await brainFetch('POST', `inbox/${encodeURIComponent(id)}/unarchive`);
            return applyMutation(updated);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to unarchive.';
            return false;
        }
    }
    async function dismiss(id) {
        error.value = null;
        try {
            const updated = await brainFetch('POST', `inbox/${encodeURIComponent(id)}/dismiss`);
            return applyMutation(updated);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to dismiss.';
            return false;
        }
    }
    async function delegate(id, toUserId, note) {
        error.value = null;
        try {
            const body = {
                itemId: id,
                toUserId,
                note: note ?? undefined,
            };
            const updated = await brainFetch('POST', `inbox/${encodeURIComponent(id)}/delegate`, { body });
            return applyMutation(updated);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delegate.';
            return false;
        }
    }
    return {
        items,
        selected,
        tags,
        loading,
        error,
        filter,
        loadList,
        loadOne,
        loadTags,
        clearSelection,
        answer,
        archive,
        unarchive,
        dismiss,
        delegate,
    };
}
//# sourceMappingURL=useInbox.js.map