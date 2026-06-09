import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, MarkdownView, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useInbox } from '@/composables/useInbox';
import { useTeams } from '@/composables/useTeams';
import { getUsername } from '@vance/shared';
import { setDocumentDraft } from '@/platform';
import { AnswerOutcome, Criticality, InboxItemStatus, InboxItemType, } from '@vance/generated';
const { t } = useI18n();
const inbox = useInbox();
const teamsState = useTeams();
const currentUser = getUsername() ?? 'unknown';
const selection = ref({ kind: 'inbox', tag: null });
/**
 * Two-way bound focus zone for {@code <EditorShell>}'s focus model.
 * Local writes (e.g. on sidebar-nav click) shift the focus directly;
 * EditorShell still drives reads from its own pointer/focus/escape
 * listeners.
 */
const focusZone = ref('main');
function isSelected(other) {
    const s = selection.value;
    if (s.kind !== other.kind)
        return false;
    if (s.kind === 'inbox')
        return s.tag === other.tag;
    if (s.kind === 'team')
        return s.teamName === other.teamName;
    return true;
}
function selectInbox(tag = null) {
    selection.value = { kind: 'inbox', tag };
}
function selectArchive() {
    selection.value = { kind: 'archive' };
}
function selectTeam(teamName) {
    selection.value = { kind: 'team', teamName };
}
// Translate sidebar selection → backend filter.
function selectionToFilter(s) {
    if (s.kind === 'inbox') {
        return {
            assignedTo: { kind: 'self' },
            status: InboxItemStatus.PENDING,
            tag: s.tag,
        };
    }
    if (s.kind === 'archive') {
        return {
            assignedTo: { kind: 'self' },
            status: InboxItemStatus.ARCHIVED,
            tag: null,
        };
    }
    // team
    return {
        assignedTo: { kind: 'team', teamName: s.teamName },
        status: null,
        tag: null,
    };
}
// ─────── URL state ───────
//
// `?item=<id>` carries the master-detail mode: empty = list view,
// present = detail view. Push on selection change so the browser
// back-button takes the user from detail back to the list. Read on
// mount and on popstate to keep state and URL in sync.
function readItemFromUrl() {
    return new URLSearchParams(window.location.search).get('item');
}
function pushItemToUrl(id) {
    const url = new URL(window.location.href);
    if (id)
        url.searchParams.set('item', id);
    else
        url.searchParams.delete('item');
    if (url.toString() !== window.location.href) {
        window.history.pushState(null, '', url.toString());
    }
}
async function onPopstate() {
    const id = readItemFromUrl();
    if (id && inbox.selected.value?.id !== id) {
        await inbox.loadOne(id);
    }
    else if (!id && inbox.selected.value) {
        inbox.clearSelection();
    }
}
// Single source of truth: any time `inbox.selected` changes, mirror
// that into the URL. Includes both user-action paths (openItem, the
// close-button) and the filter-change watcher below that calls
// clearSelection.
watch(() => inbox.selected.value?.id ?? null, (id) => {
    pushItemToUrl(id);
});
// ─────── Lifecycle ───────
onMounted(async () => {
    await Promise.all([teamsState.reload(), inbox.loadTags()]);
    await inbox.loadList(selectionToFilter(selection.value));
    // Deep-link restore: if the URL points at an item, load it.
    const initial = readItemFromUrl();
    if (initial) {
        await inbox.loadOne(initial);
    }
    window.addEventListener('popstate', onPopstate);
});
onBeforeUnmount(() => {
    window.removeEventListener('popstate', onPopstate);
});
watch(selection, async (next) => {
    inbox.clearSelection();
    await inbox.loadList(selectionToFilter(next));
}, { deep: true });
// ─────── Item open / close ───────
async function openItem(item) {
    if (!item.id)
        return;
    await inbox.loadOne(item.id);
}
function closeItem() {
    inbox.clearSelection();
}
/**
 * Human label for the current list view — drives the sub-header.
 * Mirrors what {@link breadcrumbs} renders in the topbar, just
 * without the leading project name.
 */
const viewLabel = computed(() => {
    const s = selection.value;
    if (s.kind === 'archive')
        return t('inbox.sidebar.archive');
    if (s.kind === 'team')
        return t('inbox.breadcrumbTeam', { team: s.teamName });
    if (s.kind === 'inbox' && s.tag)
        return '#' + s.tag;
    return t('inbox.sidebar.inbox');
});
function formatTimestamp(value) {
    if (!value)
        return '';
    const d = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(d.getTime()))
        return String(value);
    return d.toLocaleString();
}
function shortPreview(text, max = 100) {
    if (!text)
        return '';
    return text.length > max ? text.substring(0, max) + '…' : text;
}
// ─────── Answer flows ───────
const feedbackText = ref('');
const reasonText = ref('');
const submitting = ref(false);
watch(() => inbox.selected.value?.id, () => {
    feedbackText.value = '';
    reasonText.value = '';
});
async function submitApproval(approved) {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.DECIDED, { approved });
    }
    finally {
        submitting.value = false;
    }
}
async function submitDecision(chosen) {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.DECIDED, { chosen });
    }
    finally {
        submitting.value = false;
    }
}
async function submitFeedback() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    if (!feedbackText.value.trim())
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.DECIDED, { text: feedbackText.value.trim() });
    }
    finally {
        submitting.value = false;
    }
}
async function submitInsufficientInfo() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.INSUFFICIENT_INFO, null, reasonText.value.trim() || null);
    }
    finally {
        submitting.value = false;
    }
}
async function submitUndecidable() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.UNDECIDABLE, null, reasonText.value.trim() || null);
    }
    finally {
        submitting.value = false;
    }
}
async function archiveItem() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.archive(sel.id);
    }
    finally {
        submitting.value = false;
    }
}
async function unarchiveItem() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.unarchive(sel.id);
    }
    finally {
        submitting.value = false;
    }
}
// ─── Bulk archive by type ───────────────────────────────────────────────
//
// Lets the user clear out the noise items (output text, output image,
// feedback, …) in one go. Modal exposes a checkbox per type; submit
// loops through the visible list and archives each matching active
// item via the existing per-id endpoint. No new server route — keeps
// the change small.
const showBulkArchive = ref(false);
const bulkArchiveBusy = ref(false);
const bulkArchiveTypes = ref(Object.fromEntries(Object.values(InboxItemType).map((t) => [
    t,
    // Default-on for the loud output-only types; explicit user-facing
    // request types (approval, decision, ordering, structure-edit)
    // start off so they don't get archived by accident.
    t === InboxItemType.OUTPUT_TEXT
        || t === InboxItemType.OUTPUT_IMAGE
        || t === InboxItemType.OUTPUT_DOCUMENT
        || t === InboxItemType.FEEDBACK,
])));
const inboxItemTypeValues = Object.values(InboxItemType);
/**
 * Items in the current list that match the selected types AND are
 * still archive-able (have an id, not yet archived). The user might
 * be on the archive view too — bulk-archive only operates on active
 * items there's nothing sensible to do otherwise.
 */
const bulkArchiveCandidates = computed(() => {
    return inbox.items.value.filter((item) => {
        if (!item.id)
            return false;
        if (item.status === InboxItemStatus.ARCHIVED)
            return false;
        return bulkArchiveTypes.value[item.type] === true;
    });
});
function openBulkArchive() {
    showBulkArchive.value = true;
}
async function submitBulkArchive() {
    const candidates = bulkArchiveCandidates.value.slice();
    if (candidates.length === 0) {
        showBulkArchive.value = false;
        return;
    }
    bulkArchiveBusy.value = true;
    try {
        // Sequential to keep server load steady + give the user a
        // deterministic order. Failures don't stop the loop — the
        // composable surfaces the last error in {@code inbox.error}.
        for (const item of candidates) {
            if (item.id)
                await inbox.archive(item.id);
        }
    }
    finally {
        bulkArchiveBusy.value = false;
        showBulkArchive.value = false;
    }
}
/**
 * Hand the current item over to the document editor as a fresh
 * draft. The Document editor reads the draft on mount (one-shot)
 * and opens its create-modal prefilled with title / path / content
 * — see specification/web-ui.md §… (Inbox → Document handoff).
 */
async function toDocument() {
    const sel = inbox.selected.value;
    if (!sel)
        return;
    const ts = sel.createdAt
        ? new Date(sel.createdAt).toISOString().slice(0, 10)
        : new Date().toISOString().slice(0, 10);
    // Slug for the suggested file path. Keep it conservative — the
    // user can edit before saving.
    const slug = (sel.title || sel.id || 'inbox-item')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 60) || 'inbox-item';
    setDocumentDraft({
        title: sel.title ?? '',
        path: `inbox/${ts}-${slug}.md`,
        content: sel.body ?? '',
        mimeType: 'text/markdown',
        source: `Inbox item «${sel.title ?? '(no title)'}» from ${sel.originatorUserId}`,
    });
    // Archive the item — once it's been promoted to a document, it's
    // no longer pending in the inbox. Skip if already archived.
    if (sel.id && sel.status !== InboxItemStatus.ARCHIVED) {
        submitting.value = true;
        try {
            await inbox.archive(sel.id);
        }
        finally {
            submitting.value = false;
        }
    }
    // Same-tab navigation — the Document editor mounts fresh and
    // consumes the draft on its first onMounted.
    window.location.href = '/documents.html?createDraft=1';
}
async function dismissItem() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.dismiss(sel.id);
    }
    finally {
        submitting.value = false;
    }
}
// ─────── Delegate modal ───────
const delegateOpen = ref(false);
const delegateTarget = ref('');
const delegateNote = ref('');
const delegating = ref(false);
const delegateOptions = computed(() => {
    // Build the union of all team-mates the current user can delegate
    // to, deduped, sorted, self excluded. Username strings only.
    const set = new Set();
    for (const t of teamsState.teams.value) {
        for (const m of t.members) {
            if (m && m !== currentUser)
                set.add(m);
        }
    }
    return [...set].sort().map((u) => ({ value: u, label: u }));
});
function openDelegateModal() {
    delegateTarget.value = delegateOptions.value[0]?.value ?? '';
    delegateNote.value = '';
    delegateOpen.value = true;
}
async function confirmDelegate() {
    const sel = inbox.selected.value;
    if (!sel?.id || !delegateTarget.value)
        return;
    delegating.value = true;
    try {
        const ok = await inbox.delegate(sel.id, delegateTarget.value, delegateNote.value || null);
        if (ok)
            delegateOpen.value = false;
    }
    finally {
        delegating.value = false;
    }
}
// ─────── Item-type detection ───────
function isAsk(item) {
    return item.requiresAction === true && item.status === InboxItemStatus.PENDING;
}
function decisionOptions(item) {
    const raw = item.payload?.options;
    if (Array.isArray(raw))
        return raw.map((o) => String(o));
    return [];
}
const breadcrumbs = computed(() => {
    // Breadcrumbs carry the path *within* the editor — the editor name
    // itself is in the topbar title and would otherwise read twice.
    const c = [];
    const s = selection.value;
    if (s.kind === 'inbox' && s.tag)
        c.push('#' + s.tag);
    if (s.kind === 'archive')
        c.push(t('inbox.breadcrumbArchive'));
    if (s.kind === 'team')
        c.push(t('inbox.breadcrumbTeam', { team: s.teamName }));
    if (inbox.selected.value)
        c.push(shortPreview(inbox.selected.value.title, 40));
    return c;
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['list-row']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('inbox.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    focusModel: "auto",
    titleClickable: true,
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('inbox.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    focusModel: "auto",
    titleClickable: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onTitleClick: (...[$event]) => {
        __VLS_ctx.focusZone = 'sidebar';
    }
};
var __VLS_8 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onPointerdown: (...[$event]) => {
                __VLS_ctx.focusZone = 'main';
            } },
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.selectInbox(null);
            } },
        ...{ class: "sidebar-item" },
        ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'inbox', tag: null }) }) },
        type: "button",
    });
    (__VLS_ctx.$t('inbox.sidebar.inbox'));
    for (const [tag] of __VLS_getVForSourceType((__VLS_ctx.inbox.tags.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onPointerdown: (...[$event]) => {
                    __VLS_ctx.focusZone = 'main';
                } },
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectInbox(tag);
                } },
            key: ('t-' + tag),
            ...{ class: "sidebar-item sidebar-item--child" },
            ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'inbox', tag }) }) },
            type: "button",
        });
        (tag);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onPointerdown: (...[$event]) => {
                __VLS_ctx.focusZone = 'main';
            } },
        ...{ onClick: (__VLS_ctx.selectArchive) },
        ...{ class: "sidebar-item mt-2" },
        ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'archive' }) }) },
        type: "button",
    });
    (__VLS_ctx.$t('inbox.sidebar.archive'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-3 text-xs uppercase opacity-50 px-2" },
    });
    (__VLS_ctx.$t('inbox.sidebar.teamInbox'));
    if (__VLS_ctx.teamsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
        (__VLS_ctx.$t('inbox.sidebar.loadingTeams'));
    }
    else if (__VLS_ctx.teamsState.teams.value.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
        (__VLS_ctx.$t('inbox.sidebar.noTeams'));
    }
    for (const [team] of __VLS_getVForSourceType((__VLS_ctx.teamsState.teams.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onPointerdown: (...[$event]) => {
                    __VLS_ctx.focusZone = 'main';
                } },
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectTeam(team.name);
                } },
            key: ('team-' + team.id),
            ...{ class: "sidebar-item sidebar-item--child" },
            ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'team', teamName: team.name }) }) },
            type: "button",
        });
        (team.title || team.name);
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
if (!__VLS_ctx.inbox.selected.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-0 font-semibold truncate" },
    });
    (__VLS_ctx.viewLabel);
    if (__VLS_ctx.selection.kind !== 'archive' && __VLS_ctx.inbox.items.value.length > 0) {
        const __VLS_9 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_11 = __VLS_10({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_10));
        let __VLS_13;
        let __VLS_14;
        let __VLS_15;
        const __VLS_16 = {
            onClick: (__VLS_ctx.openBulkArchive)
        };
        __VLS_12.slots.default;
        (__VLS_ctx.$t('inbox.bulkArchive.button'));
        var __VLS_12;
    }
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-x-3 gap-y-1 flex-wrap" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-3 min-w-0 flex-1 basis-[16rem]" },
    });
    const __VLS_17 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('inbox.detail.backToList')),
    }));
    const __VLS_19 = __VLS_18({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('inbox.detail.backToList')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    let __VLS_21;
    let __VLS_22;
    let __VLS_23;
    const __VLS_24 = {
        onClick: (__VLS_ctx.closeItem)
    };
    __VLS_20.slots.default;
    var __VLS_20;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold truncate" },
    });
    (__VLS_ctx.inbox.selected.value.title || __VLS_ctx.$t('inbox.detail.noTitle'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 flex items-center gap-3 shrink-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.inbox.selected.value.type);
    if (__VLS_ctx.inbox.selected.value.criticality !== __VLS_ctx.Criticality.NORMAL) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "badge badge-warning badge-sm" },
        });
        (__VLS_ctx.inbox.selected.value.criticality);
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-y-auto" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "container mx-auto px-4 py-4 max-w-4xl" },
});
if (!__VLS_ctx.inbox.selected.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
        ...{ class: "inbox-list p-2" },
    });
    if (__VLS_ctx.inbox.error.value) {
        const __VLS_25 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
            variant: "error",
            ...{ class: "mb-3" },
        }));
        const __VLS_27 = __VLS_26({
            variant: "error",
            ...{ class: "mb-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_26));
        __VLS_28.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.inbox.error.value);
        var __VLS_28;
    }
    if (!__VLS_ctx.inbox.loading.value && __VLS_ctx.inbox.items.value.length === 0) {
        const __VLS_29 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
            headline: (__VLS_ctx.$t('inbox.list.emptyHeadline')),
            body: (__VLS_ctx.$t('inbox.list.emptyBody')),
        }));
        const __VLS_31 = __VLS_30({
            headline: (__VLS_ctx.$t('inbox.list.emptyHeadline')),
            body: (__VLS_ctx.$t('inbox.list.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-1" },
    });
    for (const [item] of __VLS_getVForSourceType((__VLS_ctx.inbox.items.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ onClick: (...[$event]) => {
                    if (!(!__VLS_ctx.inbox.selected.value))
                        return;
                    __VLS_ctx.openItem(item);
                } },
            key: (item.id ?? ''),
            ...{ class: "list-row" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-medium truncate" },
        });
        (item.title || __VLS_ctx.$t('inbox.list.noTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60 shrink-0" },
        });
        (__VLS_ctx.formatTimestamp(item.createdAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 text-xs opacity-70" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (item.type);
        if (item.criticality !== __VLS_ctx.Criticality.NORMAL) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge badge-warning badge-sm" },
            });
            (item.criticality);
        }
        if (item.assignedToUserId !== __VLS_ctx.currentUser) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60" },
            });
            (item.assignedToUserId);
        }
        if (item.status !== __VLS_ctx.InboxItemStatus.PENDING) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60" },
            });
            (item.status);
        }
        if (item.tags && item.tags.length) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "mt-1 flex gap-1 flex-wrap" },
            });
            for (const [t] of __VLS_getVForSourceType((item.tags))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    key: (t),
                    ...{ class: "badge badge-ghost badge-sm" },
                });
                (t);
            }
        }
    }
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
        ...{ class: "inbox-detail p-2" },
    });
    const __VLS_33 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({}));
    const __VLS_35 = __VLS_34({}, ...__VLS_functionalComponentArgsRest(__VLS_34));
    __VLS_36.slots.default;
    {
        const { header: __VLS_thisSlot } = __VLS_36.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-semibold truncate" },
        });
        (__VLS_ctx.inbox.selected.value.title || __VLS_ctx.$t('inbox.detail.noTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.inbox.selected.value.type);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 flex flex-wrap gap-3 mb-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.detail.fromLabel', { user: __VLS_ctx.inbox.selected.value.originatorUserId }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.detail.toLabel', { user: __VLS_ctx.inbox.selected.value.assignedToUserId }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.detail.statusLabel', { status: __VLS_ctx.inbox.selected.value.status }));
    if (__VLS_ctx.inbox.selected.value.criticality !== __VLS_ctx.Criticality.NORMAL) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('inbox.detail.criticalityLabel', { criticality: __VLS_ctx.inbox.selected.value.criticality }));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formatTimestamp(__VLS_ctx.inbox.selected.value.createdAt));
    if (__VLS_ctx.inbox.selected.value.body) {
        const __VLS_37 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
            source: (__VLS_ctx.inbox.selected.value.body),
        }));
        const __VLS_39 = __VLS_38({
            source: (__VLS_ctx.inbox.selected.value.body),
        }, ...__VLS_functionalComponentArgsRest(__VLS_38));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-60 italic" },
        });
        (__VLS_ctx.$t('inbox.detail.noBody'));
    }
    if (__VLS_ctx.inbox.selected.value.payload && Object.keys(__VLS_ctx.inbox.selected.value.payload).length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 text-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
            ...{ class: "cursor-pointer opacity-70" },
        });
        (__VLS_ctx.$t('inbox.detail.payload'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
            ...{ class: "text-xs bg-base-200 p-2 rounded mt-1 overflow-auto" },
        });
        (JSON.stringify(__VLS_ctx.inbox.selected.value.payload, null, 2));
    }
    if (__VLS_ctx.inbox.selected.value.answer) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 p-3 rounded bg-base-200 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70 text-xs mb-1" },
        });
        (__VLS_ctx.$t('inbox.detail.answer'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        (__VLS_ctx.$t('inbox.detail.answerOutcome', { outcome: __VLS_ctx.inbox.selected.value.answer.outcome }));
        if (__VLS_ctx.inbox.selected.value.answer.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            (__VLS_ctx.$t('inbox.detail.answerValue'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
            (JSON.stringify(__VLS_ctx.inbox.selected.value.answer.value));
        }
        if (__VLS_ctx.inbox.selected.value.answer.reason) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            (__VLS_ctx.$t('inbox.detail.answerReason', { reason: __VLS_ctx.inbox.selected.value.answer.reason }));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-60 text-xs mt-1" },
        });
        (__VLS_ctx.$t('inbox.detail.answerBy', { user: __VLS_ctx.inbox.selected.value.answer.answeredBy }));
    }
    {
        const { actions: __VLS_thisSlot } = __VLS_36.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-4 w-full" },
        });
        if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap gap-2" },
            });
            const __VLS_41 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_43 = __VLS_42({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_42));
            let __VLS_45;
            let __VLS_46;
            let __VLS_47;
            const __VLS_48 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.inbox.selected.value))
                        return;
                    if (!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL))
                        return;
                    __VLS_ctx.submitApproval(true);
                }
            };
            __VLS_44.slots.default;
            (__VLS_ctx.$t('inbox.actions.yes'));
            var __VLS_44;
            const __VLS_49 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_51 = __VLS_50({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_50));
            let __VLS_53;
            let __VLS_54;
            let __VLS_55;
            const __VLS_56 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.inbox.selected.value))
                        return;
                    if (!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL))
                        return;
                    __VLS_ctx.submitApproval(false);
                }
            };
            __VLS_52.slots.default;
            (__VLS_ctx.$t('inbox.actions.no'));
            var __VLS_52;
        }
        else if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.DECISION) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap gap-2" },
            });
            for (const [opt] of __VLS_getVForSourceType((__VLS_ctx.decisionOptions(__VLS_ctx.inbox.selected.value)))) {
                const __VLS_57 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
                    ...{ 'onClick': {} },
                    key: (opt),
                    variant: "primary",
                    loading: (__VLS_ctx.submitting),
                }));
                const __VLS_59 = __VLS_58({
                    ...{ 'onClick': {} },
                    key: (opt),
                    variant: "primary",
                    loading: (__VLS_ctx.submitting),
                }, ...__VLS_functionalComponentArgsRest(__VLS_58));
                let __VLS_61;
                let __VLS_62;
                let __VLS_63;
                const __VLS_64 = {
                    onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.inbox.selected.value))
                            return;
                        if (!!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL))
                            return;
                        if (!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.DECISION))
                            return;
                        __VLS_ctx.submitDecision(opt);
                    }
                };
                __VLS_60.slots.default;
                (opt);
                var __VLS_60;
            }
            if (__VLS_ctx.decisionOptions(__VLS_ctx.inbox.selected.value).length === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-60" },
                });
                (__VLS_ctx.$t('inbox.actions.noOptionsHint'));
            }
        }
        else if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.FEEDBACK) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-col gap-2" },
            });
            const __VLS_65 = {}.VTextarea;
            /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
            // @ts-ignore
            const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
                modelValue: (__VLS_ctx.feedbackText),
                label: "",
                rows: (4),
                disabled: (__VLS_ctx.submitting),
            }));
            const __VLS_67 = __VLS_66({
                modelValue: (__VLS_ctx.feedbackText),
                label: "",
                rows: (4),
                disabled: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_66));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex justify-end" },
            });
            const __VLS_69 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
                disabled: (!__VLS_ctx.feedbackText.trim()),
            }));
            const __VLS_71 = __VLS_70({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
                disabled: (!__VLS_ctx.feedbackText.trim()),
            }, ...__VLS_functionalComponentArgsRest(__VLS_70));
            let __VLS_73;
            let __VLS_74;
            let __VLS_75;
            const __VLS_76 = {
                onClick: (__VLS_ctx.submitFeedback)
            };
            __VLS_72.slots.default;
            (__VLS_ctx.$t('inbox.actions.send'));
            var __VLS_72;
        }
        if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap items-center gap-2 pt-3 border-t border-base-300" },
            });
            const __VLS_77 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
                modelValue: (__VLS_ctx.reasonText),
                placeholder: (__VLS_ctx.$t('inbox.actions.reasonPlaceholder')),
                disabled: (__VLS_ctx.submitting),
                ...{ class: "flex-1 min-w-[14rem]" },
            }));
            const __VLS_79 = __VLS_78({
                modelValue: (__VLS_ctx.reasonText),
                placeholder: (__VLS_ctx.$t('inbox.actions.reasonPlaceholder')),
                disabled: (__VLS_ctx.submitting),
                ...{ class: "flex-1 min-w-[14rem]" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_78));
            const __VLS_81 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_83 = __VLS_82({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_82));
            let __VLS_85;
            let __VLS_86;
            let __VLS_87;
            const __VLS_88 = {
                onClick: (__VLS_ctx.submitInsufficientInfo)
            };
            __VLS_84.slots.default;
            (__VLS_ctx.$t('inbox.actions.insufficientInfo'));
            var __VLS_84;
            const __VLS_89 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_91 = __VLS_90({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_90));
            let __VLS_93;
            let __VLS_94;
            let __VLS_95;
            const __VLS_96 = {
                onClick: (__VLS_ctx.submitUndecidable)
            };
            __VLS_92.slots.default;
            (__VLS_ctx.$t('inbox.actions.undecidable'));
            var __VLS_92;
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-wrap gap-2 justify-end" },
            ...{ class: (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) ? 'pt-3 border-t border-base-300' : '') },
        });
        const __VLS_97 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }));
        const __VLS_99 = __VLS_98({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_98));
        let __VLS_101;
        let __VLS_102;
        let __VLS_103;
        const __VLS_104 = {
            onClick: (__VLS_ctx.toDocument)
        };
        __VLS_100.slots.default;
        (__VLS_ctx.$t('inbox.actions.toDocument'));
        var __VLS_100;
        const __VLS_105 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }));
        const __VLS_107 = __VLS_106({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_106));
        let __VLS_109;
        let __VLS_110;
        let __VLS_111;
        const __VLS_112 = {
            onClick: (__VLS_ctx.openDelegateModal)
        };
        __VLS_108.slots.default;
        (__VLS_ctx.$t('inbox.actions.delegate'));
        var __VLS_108;
        if (__VLS_ctx.inbox.selected.value.status !== __VLS_ctx.InboxItemStatus.DISMISSED) {
            const __VLS_113 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.submitting),
            }));
            const __VLS_115 = __VLS_114({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_114));
            let __VLS_117;
            let __VLS_118;
            let __VLS_119;
            const __VLS_120 = {
                onClick: (__VLS_ctx.dismissItem)
            };
            __VLS_116.slots.default;
            (__VLS_ctx.$t('inbox.actions.dismiss'));
            var __VLS_116;
        }
        if (__VLS_ctx.inbox.selected.value.status === __VLS_ctx.InboxItemStatus.ARCHIVED) {
            const __VLS_121 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_123 = __VLS_122({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_122));
            let __VLS_125;
            let __VLS_126;
            let __VLS_127;
            const __VLS_128 = {
                onClick: (__VLS_ctx.unarchiveItem)
            };
            __VLS_124.slots.default;
            (__VLS_ctx.$t('inbox.actions.unarchive'));
            var __VLS_124;
        }
        else {
            const __VLS_129 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_130 = __VLS_asFunctionalComponent(__VLS_129, new __VLS_129({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_131 = __VLS_130({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_130));
            let __VLS_133;
            let __VLS_134;
            let __VLS_135;
            const __VLS_136 = {
                onClick: (__VLS_ctx.archiveItem)
            };
            __VLS_132.slots.default;
            (__VLS_ctx.$t('inbox.actions.archive'));
            var __VLS_132;
        }
    }
    var __VLS_36;
}
const __VLS_137 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_138 = __VLS_asFunctionalComponent(__VLS_137, new __VLS_137({
    modelValue: (__VLS_ctx.showBulkArchive),
    title: (__VLS_ctx.$t('inbox.bulkArchive.title')),
    closeOnBackdrop: (!__VLS_ctx.bulkArchiveBusy),
}));
const __VLS_139 = __VLS_138({
    modelValue: (__VLS_ctx.showBulkArchive),
    title: (__VLS_ctx.$t('inbox.bulkArchive.title')),
    closeOnBackdrop: (!__VLS_ctx.bulkArchiveBusy),
}, ...__VLS_functionalComponentArgsRest(__VLS_138));
__VLS_140.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-80" },
});
(__VLS_ctx.$t('inbox.bulkArchive.body'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.fieldset, __VLS_intrinsicElements.fieldset)({
    ...{ class: "flex flex-col gap-1.5" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.legend, __VLS_intrinsicElements.legend)({
    ...{ class: "text-xs uppercase opacity-60 mb-1" },
});
(__VLS_ctx.$t('inbox.bulkArchive.typesLegend'));
for (const [t] of __VLS_getVForSourceType((__VLS_ctx.inboxItemTypeValues))) {
    const __VLS_141 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
        key: (t),
        modelValue: (__VLS_ctx.bulkArchiveTypes[t]),
        label: (t),
        disabled: (__VLS_ctx.bulkArchiveBusy),
    }));
    const __VLS_143 = __VLS_142({
        key: (t),
        modelValue: (__VLS_ctx.bulkArchiveTypes[t]),
        label: (t),
        disabled: (__VLS_ctx.bulkArchiveBusy),
    }, ...__VLS_functionalComponentArgsRest(__VLS_142));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs opacity-70" },
});
if (__VLS_ctx.bulkArchiveCandidates.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.bulkArchive.countNone'));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.bulkArchive.countLabel', { n: __VLS_ctx.bulkArchiveCandidates.length }));
}
{
    const { actions: __VLS_thisSlot } = __VLS_140.slots;
    const __VLS_145 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_146 = __VLS_asFunctionalComponent(__VLS_145, new __VLS_145({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.bulkArchiveBusy),
    }));
    const __VLS_147 = __VLS_146({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.bulkArchiveBusy),
    }, ...__VLS_functionalComponentArgsRest(__VLS_146));
    let __VLS_149;
    let __VLS_150;
    let __VLS_151;
    const __VLS_152 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showBulkArchive = false;
        }
    };
    __VLS_148.slots.default;
    (__VLS_ctx.$t('inbox.bulkArchive.cancel'));
    var __VLS_148;
    const __VLS_153 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.bulkArchiveBusy),
        disabled: (__VLS_ctx.bulkArchiveCandidates.length === 0),
    }));
    const __VLS_155 = __VLS_154({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.bulkArchiveBusy),
        disabled: (__VLS_ctx.bulkArchiveCandidates.length === 0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_154));
    let __VLS_157;
    let __VLS_158;
    let __VLS_159;
    const __VLS_160 = {
        onClick: (__VLS_ctx.submitBulkArchive)
    };
    __VLS_156.slots.default;
    (__VLS_ctx.$t('inbox.bulkArchive.confirm', { n: __VLS_ctx.bulkArchiveCandidates.length }));
    var __VLS_156;
}
var __VLS_140;
const __VLS_161 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_162 = __VLS_asFunctionalComponent(__VLS_161, new __VLS_161({
    modelValue: (__VLS_ctx.delegateOpen),
    title: (__VLS_ctx.$t('inbox.delegate.title')),
    closeOnBackdrop: (!__VLS_ctx.delegating),
}));
const __VLS_163 = __VLS_162({
    modelValue: (__VLS_ctx.delegateOpen),
    title: (__VLS_ctx.$t('inbox.delegate.title')),
    closeOnBackdrop: (!__VLS_ctx.delegating),
}, ...__VLS_functionalComponentArgsRest(__VLS_162));
__VLS_164.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-80 mb-3" },
});
(__VLS_ctx.$t('inbox.delegate.body'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_165 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_166 = __VLS_asFunctionalComponent(__VLS_165, new __VLS_165({
    modelValue: (__VLS_ctx.delegateTarget),
    options: (__VLS_ctx.delegateOptions),
    label: (__VLS_ctx.$t('inbox.delegate.recipient')),
    disabled: (__VLS_ctx.delegating || __VLS_ctx.delegateOptions.length === 0),
}));
const __VLS_167 = __VLS_166({
    modelValue: (__VLS_ctx.delegateTarget),
    options: (__VLS_ctx.delegateOptions),
    label: (__VLS_ctx.$t('inbox.delegate.recipient')),
    disabled: (__VLS_ctx.delegating || __VLS_ctx.delegateOptions.length === 0),
}, ...__VLS_functionalComponentArgsRest(__VLS_166));
const __VLS_169 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_170 = __VLS_asFunctionalComponent(__VLS_169, new __VLS_169({
    modelValue: (__VLS_ctx.delegateNote),
    label: (__VLS_ctx.$t('inbox.delegate.note')),
    rows: (3),
    disabled: (__VLS_ctx.delegating),
}));
const __VLS_171 = __VLS_170({
    modelValue: (__VLS_ctx.delegateNote),
    label: (__VLS_ctx.$t('inbox.delegate.note')),
    rows: (3),
    disabled: (__VLS_ctx.delegating),
}, ...__VLS_functionalComponentArgsRest(__VLS_170));
{
    const { actions: __VLS_thisSlot } = __VLS_164.slots;
    const __VLS_173 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_174 = __VLS_asFunctionalComponent(__VLS_173, new __VLS_173({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.delegating),
    }));
    const __VLS_175 = __VLS_174({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.delegating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_174));
    let __VLS_177;
    let __VLS_178;
    let __VLS_179;
    const __VLS_180 = {
        onClick: (...[$event]) => {
            __VLS_ctx.delegateOpen = false;
        }
    };
    __VLS_176.slots.default;
    (__VLS_ctx.$t('inbox.delegate.cancel'));
    var __VLS_176;
    const __VLS_181 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_182 = __VLS_asFunctionalComponent(__VLS_181, new __VLS_181({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.delegating),
        disabled: (!__VLS_ctx.delegateTarget || __VLS_ctx.delegateOptions.length === 0),
    }));
    const __VLS_183 = __VLS_182({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.delegating),
        disabled: (!__VLS_ctx.delegateTarget || __VLS_ctx.delegateOptions.length === 0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_182));
    let __VLS_185;
    let __VLS_186;
    let __VLS_187;
    const __VLS_188 = {
        onClick: (__VLS_ctx.confirmDelegate)
    };
    __VLS_184.slots.default;
    (__VLS_ctx.$t('inbox.delegate.confirm'));
    var __VLS_184;
}
var __VLS_164;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--child']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--child']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['basis-[16rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['inbox-list']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['list-row']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['inbox-detail']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-[14rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            MarkdownView: MarkdownView,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            VTextarea: VTextarea,
            Criticality: Criticality,
            InboxItemStatus: InboxItemStatus,
            InboxItemType: InboxItemType,
            inbox: inbox,
            teamsState: teamsState,
            currentUser: currentUser,
            selection: selection,
            focusZone: focusZone,
            isSelected: isSelected,
            selectInbox: selectInbox,
            selectArchive: selectArchive,
            selectTeam: selectTeam,
            openItem: openItem,
            closeItem: closeItem,
            viewLabel: viewLabel,
            formatTimestamp: formatTimestamp,
            feedbackText: feedbackText,
            reasonText: reasonText,
            submitting: submitting,
            submitApproval: submitApproval,
            submitDecision: submitDecision,
            submitFeedback: submitFeedback,
            submitInsufficientInfo: submitInsufficientInfo,
            submitUndecidable: submitUndecidable,
            archiveItem: archiveItem,
            unarchiveItem: unarchiveItem,
            showBulkArchive: showBulkArchive,
            bulkArchiveBusy: bulkArchiveBusy,
            bulkArchiveTypes: bulkArchiveTypes,
            inboxItemTypeValues: inboxItemTypeValues,
            bulkArchiveCandidates: bulkArchiveCandidates,
            openBulkArchive: openBulkArchive,
            submitBulkArchive: submitBulkArchive,
            toDocument: toDocument,
            dismissItem: dismissItem,
            delegateOpen: delegateOpen,
            delegateTarget: delegateTarget,
            delegateNote: delegateNote,
            delegating: delegating,
            delegateOptions: delegateOptions,
            openDelegateModal: openDelegateModal,
            confirmDelegate: confirmDelegate,
            isAsk: isAsk,
            decisionOptions: decisionOptions,
            breadcrumbs: breadcrumbs,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=InboxApp.vue.js.map