import { VAlert, VButton, VEmptyState, VInput, VModal, VSelect } from '@vance/components';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { brainFetch } from '@vance/shared';
const props = withDefaults(defineProps(), {
    loading: false,
    error: null,
    searchEnabled: true,
    editEnabled: false,
    showGroupRows: false,
    kitOptions: () => [],
    heading: '',
    filterPlaceholder: '',
    ungroupedLabel: '',
    emptyHeadline: '',
    emptyBody: '',
});
const { t } = useI18n();
const selectedProject = defineModel('selectedProject', { default: null });
const selectedNode = defineModel('selectedNode', { default: null });
const emit = defineEmits();
const projectFilter = ref('');
const projectsByGroup = computed(() => {
    const byKey = new Map();
    // In edit mode keep an empty {@code null} bucket so the "ungrouped"
    // drop zone is always visible — without it, dragging the last
    // project out of "no group" would remove its target entirely on
    // the next reload and the inverse (dropping back into "no group")
    // would have nowhere to land.
    if (props.editEnabled) {
        byKey.set(null, []);
    }
    for (const p of props.projects) {
        const key = p.projectGroupId ?? null;
        const list = byKey.get(key) ?? [];
        list.push(p);
        byKey.set(key, list);
    }
    // In edit mode also surface every named group, even when empty,
    // so users can drag a project into a freshly created group that
    // has no projects yet.
    if (props.editEnabled) {
        for (const g of props.groups) {
            if (!byKey.has(g.name))
                byKey.set(g.name, []);
        }
    }
    const groupByName = new Map(props.groups.map((g) => [g.name, g]));
    const result = [];
    for (const [groupName, list] of byKey.entries()) {
        const group = groupName ? groupByName.get(groupName) ?? null : null;
        const groupLabel = group ? group.title || group.name : props.ungroupedLabel;
        result.push({ group, groupLabel, projects: list });
    }
    // Stable order: ungrouped first, then groups by name.
    result.sort((a, b) => {
        if (a.group === null && b.group !== null)
            return -1;
        if (a.group !== null && b.group === null)
            return 1;
        if (!a.group || !b.group)
            return 0;
        return a.group.name.localeCompare(b.group.name);
    });
    return result;
});
const filteredProjectsByGroup = computed(() => {
    const needle = projectFilter.value.trim().toLowerCase();
    if (!needle)
        return projectsByGroup.value;
    const result = [];
    for (const block of projectsByGroup.value) {
        const matching = block.projects.filter((p) => {
            const title = (p.title ?? '').toLowerCase();
            const name = p.name.toLowerCase();
            return title.includes(needle) || name.includes(needle);
        });
        if (matching.length > 0) {
            result.push({ ...block, projects: matching });
        }
    }
    return result;
});
const filteredProjectsCount = computed(() => filteredProjectsByGroup.value.reduce((n, b) => n + b.projects.length, 0));
function isProjectSelected(p) {
    if (selectedNode.value) {
        return selectedNode.value.kind === 'project' && selectedNode.value.name === p.name;
    }
    return selectedProject.value === p.name;
}
function isGroupSelected(g) {
    return selectedNode.value?.kind === 'group' && selectedNode.value.name === g.name;
}
function selectProject(p) {
    selectedProject.value = p.name;
    selectedNode.value = { kind: 'project', name: p.name };
    emit('project-pick', { name: p.name, title: p.title || p.name });
}
function selectGroup(g) {
    selectedNode.value = { kind: 'group', name: g.name };
    emit('group-pick', { name: g.name, title: g.title || g.name });
}
// ────────────────── Collapse state (per-user, persisted) ──────────────────
//
// Collapse state lives on the per-user {@code _user_<login>} project
// behind {@code /brain/{tenant}/me/ui-state/sidebar}. The Set holds
// group names; missing = expanded (the default for new groups). When
// a filter is active we override and always show matches — otherwise
// the user types a query and gets nothing back because the parent is
// collapsed.
const collapsedGroups = ref(new Set());
let collapsedLoaded = false;
let saveTimer = null;
const SAVE_DEBOUNCE_MS = 300;
function isGroupCollapsed(g) {
    if (!g)
        return false;
    if (projectFilter.value.trim())
        return false;
    return collapsedGroups.value.has(g.name);
}
function toggleGroupCollapsed(g) {
    if (!g)
        return;
    const next = new Set(collapsedGroups.value);
    if (next.has(g.name))
        next.delete(g.name);
    else
        next.add(g.name);
    collapsedGroups.value = next;
    scheduleSaveCollapsed();
}
function scheduleSaveCollapsed() {
    if (!collapsedLoaded)
        return;
    if (saveTimer !== null)
        window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => {
        saveTimer = null;
        void saveCollapsedNow();
    }, SAVE_DEBOUNCE_MS);
}
async function saveCollapsedNow() {
    try {
        await brainFetch('PUT', 'me/ui-state/sidebar', {
            body: {
                collapsedProjectGroups: Array.from(collapsedGroups.value),
            },
        });
    }
    catch (e) {
        // UI-state persistence is non-critical — swallow the error so a
        // transient failure doesn't surface as an alert. Worst case: the
        // next toggle retries the PUT.
        console.warn('Failed to save sidebar UI state', e);
    }
}
onMounted(async () => {
    try {
        const state = await brainFetch('GET', 'me/ui-state/sidebar');
        collapsedGroups.value = new Set(state.collapsedProjectGroups ?? []);
    }
    catch (e) {
        // Same rationale as saveCollapsedNow — UI state is best-effort.
        console.warn('Failed to load sidebar UI state', e);
    }
    finally {
        collapsedLoaded = true;
    }
});
onBeforeUnmount(() => {
    if (saveTimer !== null) {
        // Flush any pending debounced write so the user's last toggle
        // doesn't get lost when they navigate away immediately after.
        window.clearTimeout(saveTimer);
        saveTimer = null;
        void saveCollapsedNow();
    }
});
// ────────────────── Edit mode: create group / project ──────────────────
const showCreateGroup = ref(false);
const newGroupName = ref('');
const newGroupTitle = ref('');
const showCreateProject = ref(false);
const newProjectName = ref('');
const newProjectTitle = ref('');
const newProjectGroupId = ref(null);
const newProjectKitName = ref('');
const creating = ref(false);
const creationError = ref(null);
const showKitField = computed(() => props.kitOptions.length > 0);
function openCreateGroup() {
    newGroupName.value = '';
    newGroupTitle.value = '';
    creationError.value = null;
    showCreateGroup.value = true;
}
/** Group dropdown for the create-project modal — same options
 *  as the rendered project list. {@code null} value maps to
 *  "Ohne Gruppe" / "No group". */
const groupSelectOptions = computed(() => [
    { value: '', label: t('common.projectPicker.createProject.groupNone') },
    ...props.groups.map((g) => ({
        value: g.name,
        label: g.title || g.name,
    })),
]);
function openCreateProject(groupId = null) {
    newProjectName.value = '';
    newProjectTitle.value = '';
    newProjectGroupId.value = groupId;
    newProjectKitName.value = '';
    creationError.value = null;
    showCreateProject.value = true;
}
async function submitCreateGroup() {
    const name = newGroupName.value.trim();
    if (!name)
        return;
    creating.value = true;
    creationError.value = null;
    try {
        await brainFetch('POST', 'admin/project-groups', {
            body: {
                name,
                title: newGroupTitle.value.trim() || undefined,
            },
        });
        showCreateGroup.value = false;
        emit('data-changed', { kind: 'group', name });
    }
    catch (e) {
        creationError.value = describeError(e);
    }
    finally {
        creating.value = false;
    }
}
async function submitCreateProject() {
    const name = newProjectName.value.trim();
    if (!name)
        return;
    creating.value = true;
    creationError.value = null;
    try {
        await brainFetch('POST', 'admin/projects', {
            body: {
                name,
                title: newProjectTitle.value.trim() || undefined,
                projectGroupId: newProjectGroupId.value || undefined,
                teamIds: [],
                // Only included when the host opted in via {@link Props.kitOptions}.
                // Blank string maps to "no kit" — server treats null/blank the same.
                kitName: showKitField.value
                    ? (newProjectKitName.value.trim() || undefined)
                    : undefined,
            },
        });
        showCreateProject.value = false;
        emit('data-changed', { kind: 'project', name });
    }
    catch (e) {
        creationError.value = describeError(e);
    }
    finally {
        creating.value = false;
    }
}
function describeError(e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.toLowerCase().includes('forbidden') || msg.includes('403')) {
        return t('common.projectPicker.error.forbidden');
    }
    return t('common.projectPicker.error.generic', { message: msg });
}
// ────────────────── Edit mode: drag-and-drop to move ──────────────────
//
// Project buttons are {@code draggable="true"} when {@link editEnabled}
// is set; group blocks (and the synthetic "ungrouped" block) accept
// drops. On a successful drop we PUT
// {@code admin/projects/{name}} with the target group — empty payload
// means "leave as is", so we send either {@code projectGroupId:<name>}
// or {@code clearProjectGroup: true} for the "no group" target.
const draggingProject = ref(null);
/** Identifier of the group block the dragged project is hovering over —
 *  {@code 'g:<name>'} for a named group, {@code 'ungrouped'} for the
 *  null-group bucket, {@code null} when not over any drop zone.
 *  Drives the highlight class on the drop-target. */
const dragHoverKey = ref(null);
const moving = ref(false);
const moveError = ref(null);
// Auto-scroll during drag — when the pointer hovers within
// AUTOSCROLL_EDGE pixels of the scrollable ancestor's top/bottom
// edge, we kick a rAF loop that scrolls the container so the user
// can drop on rows currently below/above the fold without having
// to abort the drag.
const AUTOSCROLL_EDGE = 48; // px from the edge where scroll kicks in
const AUTOSCROLL_MAX = 16; // px per frame at the very edge
let scrollContainer = null;
let scrollRaf = null;
let lastPointerY = 0;
function findScrollableAncestor(el) {
    let cur = el;
    while (cur && cur !== document.body && cur !== document.documentElement) {
        const overflowY = window.getComputedStyle(cur).overflowY;
        if (overflowY === 'auto' || overflowY === 'scroll') {
            // Only useful if the container actually overflows; otherwise
            // climb past it (the dialog body sometimes has overflow-auto
            // but no overflowing content).
            if (cur.scrollHeight > cur.clientHeight)
                return cur;
        }
        cur = cur.parentElement;
    }
    return null;
}
function onDocumentDragOver(ev) {
    lastPointerY = ev.clientY;
}
function scrollLoop() {
    if (!scrollContainer || !draggingProject.value) {
        scrollRaf = null;
        return;
    }
    const rect = scrollContainer.getBoundingClientRect();
    const fromTop = lastPointerY - rect.top;
    const fromBottom = rect.bottom - lastPointerY;
    let delta = 0;
    if (fromTop >= 0 && fromTop < AUTOSCROLL_EDGE) {
        const ratio = 1 - fromTop / AUTOSCROLL_EDGE; // 1 at edge, 0 at threshold
        delta = -Math.max(2, Math.round(ratio * AUTOSCROLL_MAX));
    }
    else if (fromBottom >= 0 && fromBottom < AUTOSCROLL_EDGE) {
        const ratio = 1 - fromBottom / AUTOSCROLL_EDGE;
        delta = Math.max(2, Math.round(ratio * AUTOSCROLL_MAX));
    }
    if (delta !== 0) {
        scrollContainer.scrollTop += delta;
    }
    scrollRaf = requestAnimationFrame(scrollLoop);
}
function startAutoScroll(originEl) {
    scrollContainer = findScrollableAncestor(originEl);
    if (!scrollContainer)
        return;
    document.addEventListener('dragover', onDocumentDragOver);
    if (scrollRaf === null) {
        scrollRaf = requestAnimationFrame(scrollLoop);
    }
}
function stopAutoScroll() {
    document.removeEventListener('dragover', onDocumentDragOver);
    if (scrollRaf !== null) {
        cancelAnimationFrame(scrollRaf);
        scrollRaf = null;
    }
    scrollContainer = null;
}
// Component teardown mid-drag (rare — route change, dialog close)
// has to release the global listener + rAF so the browser doesn't
// keep them alive past the picker's lifetime.
onBeforeUnmount(stopAutoScroll);
function blockKey(block) {
    return block.group ? `g:${block.group.name}` : 'ungrouped';
}
function onProjectDragStart(p, ev) {
    if (!props.editEnabled)
        return;
    draggingProject.value = p.name;
    if (ev.dataTransfer) {
        ev.dataTransfer.effectAllowed = 'move';
        // Use a vendored MIME so other draggables on the page can't
        // accidentally trigger our drop handler.
        ev.dataTransfer.setData('application/x-vance-project', p.name);
    }
    // Track pointer Y at the document level + tick an animation loop
    // so the user can drag over rows that are currently outside the
    // scroll viewport.
    lastPointerY = ev.clientY;
    startAutoScroll(ev.target);
}
function onProjectDragEnd() {
    draggingProject.value = null;
    dragHoverKey.value = null;
    stopAutoScroll();
}
function onBlockDragOver(block, ev) {
    if (!props.editEnabled || !draggingProject.value)
        return;
    ev.preventDefault();
    if (ev.dataTransfer)
        ev.dataTransfer.dropEffect = 'move';
    dragHoverKey.value = blockKey(block);
}
function onBlockDragLeave(block) {
    if (dragHoverKey.value === blockKey(block)) {
        dragHoverKey.value = null;
    }
}
async function onBlockDrop(block, ev) {
    if (!props.editEnabled)
        return;
    ev.preventDefault();
    const projectName = draggingProject.value
        ?? ev.dataTransfer?.getData('application/x-vance-project')
        ?? '';
    draggingProject.value = null;
    dragHoverKey.value = null;
    stopAutoScroll();
    if (!projectName)
        return;
    const project = props.projects.find((p) => p.name === projectName);
    if (!project)
        return;
    const targetGroup = block.group ? block.group.name : null;
    const currentGroup = project.projectGroupId ?? null;
    if (currentGroup === targetGroup)
        return;
    moving.value = true;
    moveError.value = null;
    try {
        await brainFetch('PUT', `admin/projects/${encodeURIComponent(projectName)}`, {
            body: targetGroup
                ? { projectGroupId: targetGroup }
                : { clearProjectGroup: true },
        });
        emit('data-changed', { kind: 'project', name: projectName });
    }
    catch (e) {
        moveError.value = describeError(e);
        // Auto-dismiss after 5s — leaves the sidebar clean once the
        // user has seen the message (or hits the dismiss button below).
        window.setTimeout(() => {
            moveError.value = null;
        }, 5000);
    }
    finally {
        moving.value = false;
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    loading: false,
    error: null,
    searchEnabled: true,
    editEnabled: false,
    showGroupRows: false,
    kitOptions: () => [],
    heading: '',
    filterPlaceholder: '',
    ungroupedLabel: '',
    emptyHeadline: '',
    emptyBody: '',
});
const __VLS_defaults = {
    'selectedProject': null,
    'selectedNode': null,
};
const __VLS_modelEmit = defineEmits();
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-4 flex flex-col gap-4" },
});
if (__VLS_ctx.heading || __VLS_ctx.$slots['header-extra'] || __VLS_ctx.editEnabled) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between gap-2" },
    });
    if (__VLS_ctx.heading) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold px-2" },
        });
        (__VLS_ctx.heading);
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({});
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-1" },
    });
    var __VLS_0 = {};
    if (__VLS_ctx.editEnabled) {
        const __VLS_2 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_3 = __VLS_asFunctionalComponent(__VLS_2, new __VLS_2({
            ...{ 'onPointerdown': {} },
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            title: (__VLS_ctx.t('common.projectPicker.addGroup')),
        }));
        const __VLS_4 = __VLS_3({
            ...{ 'onPointerdown': {} },
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            title: (__VLS_ctx.t('common.projectPicker.addGroup')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_3));
        let __VLS_6;
        let __VLS_7;
        let __VLS_8;
        const __VLS_9 = {
            onPointerdown: () => { }
        };
        const __VLS_10 = {
            onClick: (__VLS_ctx.openCreateGroup)
        };
        __VLS_5.slots.default;
        var __VLS_5;
    }
}
if (__VLS_ctx.searchEnabled && !__VLS_ctx.loading && !__VLS_ctx.error && __VLS_ctx.projects.length > 0) {
    const __VLS_11 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_12 = __VLS_asFunctionalComponent(__VLS_11, new __VLS_11({
        modelValue: (__VLS_ctx.projectFilter),
        placeholder: (__VLS_ctx.filterPlaceholder),
    }));
    const __VLS_13 = __VLS_12({
        modelValue: (__VLS_ctx.projectFilter),
        placeholder: (__VLS_ctx.filterPlaceholder),
    }, ...__VLS_functionalComponentArgsRest(__VLS_12));
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60 px-2" },
    });
    var __VLS_15 = {};
}
else if (__VLS_ctx.error) {
    const __VLS_17 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        variant: "error",
    }));
    const __VLS_19 = __VLS_18({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_20.slots.default;
    (__VLS_ctx.error);
    var __VLS_20;
}
else {
    if (__VLS_ctx.moveError) {
        const __VLS_21 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            ...{ 'onClick': {} },
            variant: "error",
            ...{ class: "cursor-pointer" },
        }));
        const __VLS_23 = __VLS_22({
            ...{ 'onClick': {} },
            variant: "error",
            ...{ class: "cursor-pointer" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
        let __VLS_25;
        let __VLS_26;
        let __VLS_27;
        const __VLS_28 = {
            onClick: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!!(__VLS_ctx.error))
                    return;
                if (!(__VLS_ctx.moveError))
                    return;
                __VLS_ctx.moveError = null;
            }
        };
        __VLS_24.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.moveError);
        var __VLS_24;
    }
    for (const [block] of __VLS_getVForSourceType((__VLS_ctx.filteredProjectsByGroup))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ onDragover: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    if (!!(__VLS_ctx.error))
                        return;
                    __VLS_ctx.onBlockDragOver(block, $event);
                } },
            ...{ onDragleave: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    if (!!(__VLS_ctx.error))
                        return;
                    __VLS_ctx.onBlockDragLeave(block);
                } },
            ...{ onDrop: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    if (!!(__VLS_ctx.error))
                        return;
                    __VLS_ctx.onBlockDrop(block, $event);
                } },
            key: (block.group?.name ?? '_ungrouped'),
            ...{ class: "flex flex-col gap-1 rounded transition-colors" },
            ...{ class: (__VLS_ctx.dragHoverKey === __VLS_ctx.blockKey(block)
                    ? 'bg-primary/5 outline outline-2 outline-primary/40 outline-offset-2'
                    : '') },
        });
        if (block.group && __VLS_ctx.showGroupRows) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-1" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onPointerdown: () => { } },
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        if (!(block.group && __VLS_ctx.showGroupRows))
                            return;
                        __VLS_ctx.toggleGroupCollapsed(block.group);
                    } },
                type: "button",
                ...{ class: "px-1 py-1.5 text-xs opacity-60 hover:opacity-100" },
                title: (__VLS_ctx.isGroupCollapsed(block.group)
                    ? __VLS_ctx.t('common.projectPicker.expandGroup')
                    : __VLS_ctx.t('common.projectPicker.collapseGroup')),
            });
            (__VLS_ctx.isGroupCollapsed(block.group) ? '▸' : '▾');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onPointerdown: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        if (!(block.group && __VLS_ctx.showGroupRows))
                            return;
                        __VLS_ctx.emit('focus-main');
                    } },
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        if (!(block.group && __VLS_ctx.showGroupRows))
                            return;
                        __VLS_ctx.selectGroup(block.group);
                    } },
                type: "button",
                ...{ class: "flex-1 text-left px-2 py-1.5 rounded text-sm transition-colors flex items-center gap-2" },
                ...{ class: (__VLS_ctx.isGroupSelected(block.group)
                        ? 'bg-primary/10 text-primary font-medium'
                        : 'hover:bg-base-200') },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "flex-1 truncate" },
            });
            (block.group.title || block.group.name);
            var __VLS_29 = {
                kind: ('group'),
                item: (block.group),
            };
            if (__VLS_ctx.editEnabled) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onPointerdown: () => { } },
                    ...{ onClick: (...[$event]) => {
                            if (!!(__VLS_ctx.loading))
                                return;
                            if (!!(__VLS_ctx.error))
                                return;
                            if (!(block.group && __VLS_ctx.showGroupRows))
                                return;
                            if (!(__VLS_ctx.editEnabled))
                                return;
                            __VLS_ctx.openCreateProject(block.group.name);
                        } },
                    type: "button",
                    ...{ class: "text-xs opacity-50 hover:opacity-100 px-1" },
                    title: (__VLS_ctx.t('common.projectPicker.addProjectToGroup')),
                });
            }
        }
        else if (block.group && block.groupLabel) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onPointerdown: () => { } },
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        if (!!(block.group && __VLS_ctx.showGroupRows))
                            return;
                        if (!(block.group && block.groupLabel))
                            return;
                        __VLS_ctx.toggleGroupCollapsed(block.group);
                    } },
                type: "button",
                ...{ class: "flex items-center justify-between px-2 py-1 rounded text-left hover:bg-base-200 transition-colors w-full" },
                title: (__VLS_ctx.isGroupCollapsed(block.group)
                    ? __VLS_ctx.t('common.projectPicker.expandGroup')
                    : __VLS_ctx.t('common.projectPicker.collapseGroup')),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "flex items-center gap-1.5 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-50 w-3 inline-block text-center" },
            });
            (__VLS_ctx.isGroupCollapsed(block.group) ? '▸' : '▾');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-50 truncate" },
            });
            (block.groupLabel);
            if (__VLS_ctx.editEnabled) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ onPointerdown: () => { } },
                    ...{ onClick: (...[$event]) => {
                            if (!!(__VLS_ctx.loading))
                                return;
                            if (!!(__VLS_ctx.error))
                                return;
                            if (!!(block.group && __VLS_ctx.showGroupRows))
                                return;
                            if (!(block.group && block.groupLabel))
                                return;
                            if (!(__VLS_ctx.editEnabled))
                                return;
                            __VLS_ctx.openCreateProject(block.group.name);
                        } },
                    ...{ class: "text-xs opacity-50 hover:opacity-100 px-1" },
                    title: (__VLS_ctx.t('common.projectPicker.addProjectToGroup')),
                    role: "button",
                });
            }
        }
        else if (block.groupLabel) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between px-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-50" },
            });
            (block.groupLabel);
            if (__VLS_ctx.editEnabled) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onPointerdown: () => { } },
                    ...{ onClick: (...[$event]) => {
                            if (!!(__VLS_ctx.loading))
                                return;
                            if (!!(__VLS_ctx.error))
                                return;
                            if (!!(block.group && __VLS_ctx.showGroupRows))
                                return;
                            if (!!(block.group && block.groupLabel))
                                return;
                            if (!(block.groupLabel))
                                return;
                            if (!(__VLS_ctx.editEnabled))
                                return;
                            __VLS_ctx.openCreateProject(null);
                        } },
                    type: "button",
                    ...{ class: "text-xs opacity-50 hover:opacity-100" },
                    title: (__VLS_ctx.t('common.projectPicker.addProjectToGroup')),
                });
            }
        }
        for (const [p] of __VLS_getVForSourceType((block.projects))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onPointerdown: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        __VLS_ctx.emit('focus-main');
                    } },
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        __VLS_ctx.selectProject(p);
                    } },
                ...{ onDragstart: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.error))
                            return;
                        __VLS_ctx.onProjectDragStart(p, $event);
                    } },
                ...{ onDragend: (__VLS_ctx.onProjectDragEnd) },
                key: (p.name),
                type: "button",
                draggable: (__VLS_ctx.editEnabled),
                ...{ class: "text-left px-2 py-1.5 rounded text-sm transition-colors flex items-center gap-2" },
                ...{ class: ([
                        __VLS_ctx.isProjectSelected(p)
                            ? 'bg-primary/10 text-primary font-medium'
                            : 'hover:bg-base-200',
                        __VLS_ctx.showGroupRows ? 'pl-6' : '',
                        __VLS_ctx.editEnabled ? 'cursor-grab active:cursor-grabbing' : '',
                        __VLS_ctx.draggingProject === p.name ? 'opacity-50' : '',
                    ]) },
            });
            __VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (!__VLS_ctx.isGroupCollapsed(block.group)) }, null, null);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "flex-1 truncate" },
            });
            (p.title || p.name);
            var __VLS_31 = {
                kind: ('project'),
                item: (p),
            };
        }
    }
    if (__VLS_ctx.projects.length === 0 && __VLS_ctx.emptyHeadline) {
        const __VLS_33 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
            headline: (__VLS_ctx.emptyHeadline),
            body: (__VLS_ctx.emptyBody),
        }));
        const __VLS_35 = __VLS_34({
            headline: (__VLS_ctx.emptyHeadline),
            body: (__VLS_ctx.emptyBody),
        }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    }
    else if (__VLS_ctx.projectFilter && __VLS_ctx.filteredProjectsCount === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 px-2" },
        });
        var __VLS_37 = {
            filter: (__VLS_ctx.projectFilter),
        };
    }
    if (__VLS_ctx.editEnabled && !__VLS_ctx.loading && !__VLS_ctx.error) {
        const __VLS_39 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_40 = __VLS_asFunctionalComponent(__VLS_39, new __VLS_39({
            ...{ 'onPointerdown': {} },
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            block: true,
        }));
        const __VLS_41 = __VLS_40({
            ...{ 'onPointerdown': {} },
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            block: true,
        }, ...__VLS_functionalComponentArgsRest(__VLS_40));
        let __VLS_43;
        let __VLS_44;
        let __VLS_45;
        const __VLS_46 = {
            onPointerdown: () => { }
        };
        const __VLS_47 = {
            onClick: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!!(__VLS_ctx.error))
                    return;
                if (!(__VLS_ctx.editEnabled && !__VLS_ctx.loading && !__VLS_ctx.error))
                    return;
                __VLS_ctx.openCreateProject(null);
            }
        };
        __VLS_42.slots.default;
        (__VLS_ctx.t('common.projectPicker.addProject'));
        var __VLS_42;
    }
}
const __VLS_48 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
    modelValue: (__VLS_ctx.showCreateGroup),
    title: (__VLS_ctx.t('common.projectPicker.createGroup.title')),
    closeOnBackdrop: (!__VLS_ctx.creating),
}));
const __VLS_50 = __VLS_49({
    modelValue: (__VLS_ctx.showCreateGroup),
    title: (__VLS_ctx.t('common.projectPicker.createGroup.title')),
    closeOnBackdrop: (!__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_49));
__VLS_51.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submitCreateGroup) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.creationError) {
    const __VLS_52 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
        variant: "error",
    }));
    const __VLS_54 = __VLS_53({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_53));
    __VLS_55.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.creationError);
    var __VLS_55;
}
const __VLS_56 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
    modelValue: (__VLS_ctx.newGroupName),
    label: (__VLS_ctx.t('common.projectPicker.createGroup.name')),
    help: (__VLS_ctx.t('common.projectPicker.createGroup.nameHelp')),
    required: true,
    disabled: (__VLS_ctx.creating),
}));
const __VLS_58 = __VLS_57({
    modelValue: (__VLS_ctx.newGroupName),
    label: (__VLS_ctx.t('common.projectPicker.createGroup.name')),
    help: (__VLS_ctx.t('common.projectPicker.createGroup.nameHelp')),
    required: true,
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_57));
const __VLS_60 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
    modelValue: (__VLS_ctx.newGroupTitle),
    label: (__VLS_ctx.t('common.projectPicker.createGroup.titleLabel')),
    disabled: (__VLS_ctx.creating),
}));
const __VLS_62 = __VLS_61({
    modelValue: (__VLS_ctx.newGroupTitle),
    label: (__VLS_ctx.t('common.projectPicker.createGroup.titleLabel')),
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_61));
{
    const { actions: __VLS_thisSlot } = __VLS_51.slots;
    const __VLS_64 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_66 = __VLS_65({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_65));
    let __VLS_68;
    let __VLS_69;
    let __VLS_70;
    const __VLS_71 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showCreateGroup = false;
        }
    };
    __VLS_67.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_67;
    const __VLS_72 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
        disabled: (!__VLS_ctx.newGroupName.trim()),
    }));
    const __VLS_74 = __VLS_73({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
        disabled: (!__VLS_ctx.newGroupName.trim()),
    }, ...__VLS_functionalComponentArgsRest(__VLS_73));
    let __VLS_76;
    let __VLS_77;
    let __VLS_78;
    const __VLS_79 = {
        onClick: (__VLS_ctx.submitCreateGroup)
    };
    __VLS_75.slots.default;
    (__VLS_ctx.t('common.projectPicker.createGroup.submit'));
    var __VLS_75;
}
var __VLS_51;
const __VLS_80 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
    modelValue: (__VLS_ctx.showCreateProject),
    title: (__VLS_ctx.t('common.projectPicker.createProject.title')),
    closeOnBackdrop: (!__VLS_ctx.creating),
}));
const __VLS_82 = __VLS_81({
    modelValue: (__VLS_ctx.showCreateProject),
    title: (__VLS_ctx.t('common.projectPicker.createProject.title')),
    closeOnBackdrop: (!__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_81));
__VLS_83.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submitCreateProject) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.creationError) {
    const __VLS_84 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
        variant: "error",
    }));
    const __VLS_86 = __VLS_85({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_85));
    __VLS_87.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.creationError);
    var __VLS_87;
}
const __VLS_88 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
    modelValue: (__VLS_ctx.newProjectName),
    label: (__VLS_ctx.t('common.projectPicker.createProject.name')),
    help: (__VLS_ctx.t('common.projectPicker.createProject.nameHelp')),
    required: true,
    disabled: (__VLS_ctx.creating),
}));
const __VLS_90 = __VLS_89({
    modelValue: (__VLS_ctx.newProjectName),
    label: (__VLS_ctx.t('common.projectPicker.createProject.name')),
    help: (__VLS_ctx.t('common.projectPicker.createProject.nameHelp')),
    required: true,
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_89));
const __VLS_92 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
    modelValue: (__VLS_ctx.newProjectTitle),
    label: (__VLS_ctx.t('common.projectPicker.createProject.titleLabel')),
    disabled: (__VLS_ctx.creating),
}));
const __VLS_94 = __VLS_93({
    modelValue: (__VLS_ctx.newProjectTitle),
    label: (__VLS_ctx.t('common.projectPicker.createProject.titleLabel')),
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_93));
const __VLS_96 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_97 = __VLS_asFunctionalComponent(__VLS_96, new __VLS_96({
    modelValue: (__VLS_ctx.newProjectGroupId),
    label: (__VLS_ctx.t('common.projectPicker.createProject.group')),
    options: (__VLS_ctx.groupSelectOptions),
    disabled: (__VLS_ctx.creating),
}));
const __VLS_98 = __VLS_97({
    modelValue: (__VLS_ctx.newProjectGroupId),
    label: (__VLS_ctx.t('common.projectPicker.createProject.group')),
    options: (__VLS_ctx.groupSelectOptions),
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_97));
if (__VLS_ctx.showKitField) {
    const __VLS_100 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_101 = __VLS_asFunctionalComponent(__VLS_100, new __VLS_100({
        modelValue: (__VLS_ctx.newProjectKitName),
        label: (__VLS_ctx.t('common.projectPicker.createProject.kit')),
        options: (__VLS_ctx.kitOptions),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_102 = __VLS_101({
        modelValue: (__VLS_ctx.newProjectKitName),
        label: (__VLS_ctx.t('common.projectPicker.createProject.kit')),
        options: (__VLS_ctx.kitOptions),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_101));
}
{
    const { actions: __VLS_thisSlot } = __VLS_83.slots;
    const __VLS_104 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_105 = __VLS_asFunctionalComponent(__VLS_104, new __VLS_104({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_106 = __VLS_105({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_105));
    let __VLS_108;
    let __VLS_109;
    let __VLS_110;
    const __VLS_111 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showCreateProject = false;
        }
    };
    __VLS_107.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_107;
    const __VLS_112 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_113 = __VLS_asFunctionalComponent(__VLS_112, new __VLS_112({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
        disabled: (!__VLS_ctx.newProjectName.trim()),
    }));
    const __VLS_114 = __VLS_113({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
        disabled: (!__VLS_ctx.newProjectName.trim()),
    }, ...__VLS_functionalComponentArgsRest(__VLS_113));
    let __VLS_116;
    let __VLS_117;
    let __VLS_118;
    const __VLS_119 = {
        onClick: (__VLS_ctx.submitCreateProject)
    };
    __VLS_115.slots.default;
    (__VLS_ctx.t('common.projectPicker.createProject.submit'));
    var __VLS_115;
}
var __VLS_83;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
// @ts-ignore
var __VLS_1 = __VLS_0, __VLS_16 = __VLS_15, __VLS_30 = __VLS_29, __VLS_32 = __VLS_31, __VLS_38 = __VLS_37;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            t: t,
            emit: emit,
            projectFilter: projectFilter,
            filteredProjectsByGroup: filteredProjectsByGroup,
            filteredProjectsCount: filteredProjectsCount,
            isProjectSelected: isProjectSelected,
            isGroupSelected: isGroupSelected,
            selectProject: selectProject,
            selectGroup: selectGroup,
            isGroupCollapsed: isGroupCollapsed,
            toggleGroupCollapsed: toggleGroupCollapsed,
            showCreateGroup: showCreateGroup,
            newGroupName: newGroupName,
            newGroupTitle: newGroupTitle,
            showCreateProject: showCreateProject,
            newProjectName: newProjectName,
            newProjectTitle: newProjectTitle,
            newProjectGroupId: newProjectGroupId,
            newProjectKitName: newProjectKitName,
            creating: creating,
            creationError: creationError,
            showKitField: showKitField,
            openCreateGroup: openCreateGroup,
            groupSelectOptions: groupSelectOptions,
            openCreateProject: openCreateProject,
            submitCreateGroup: submitCreateGroup,
            submitCreateProject: submitCreateProject,
            draggingProject: draggingProject,
            dragHoverKey: dragHoverKey,
            moveError: moveError,
            blockKey: blockKey,
            onProjectDragStart: onProjectDragStart,
            onProjectDragEnd: onProjectDragEnd,
            onBlockDragOver: onBlockDragOver,
            onBlockDragLeave: onBlockDragLeave,
            onBlockDrop: onBlockDrop,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
const __VLS_component = (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default {};
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ProjectListSidebar.vue.js.map