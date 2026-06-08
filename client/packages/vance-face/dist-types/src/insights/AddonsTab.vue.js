import { computed, onMounted } from 'vue';
import { ChecksumStatus } from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useAddonInsights } from '@/composables/useAddons';
const state = useAddonInsights();
onMounted(() => {
    void state.load();
});
function refresh() {
    void state.load();
}
/**
 * Effective deployment status — combines DB-enabled, on-disk unpacked
 * and Spring-bean-registered into one badge.
 *
 *   loaded   = enabled + bean active + unpacked .vab
 *   built-in = enabled + bean active without an unpacked .vab —
 *              addon is shipped inside the brain image
 *   disabled = admin flipped enabled=false
 *   broken   = enabled + unpacked but no bean (Spring rejected addon)
 *   missing  = enabled but no bundle and no bean
 */
function deploymentStatus(addon) {
    if (!addon.enabled) {
        return { label: 'disabled', cssClass: 'badge-status badge-status--stopped',
            title: 'enabled=false in db.addons' };
    }
    if (addon.beanRegistered && addon.unpacked) {
        return { label: 'loaded', cssClass: 'badge-status badge-status--running',
            title: 'unpacked + Spring bean active' };
    }
    if (addon.unpacked && !addon.beanRegistered) {
        return { label: 'broken', cssClass: 'badge-status badge-status--stale',
            title: 'unpacked but Spring did not register the VanceAddon bean' };
    }
    if (addon.beanRegistered && !addon.unpacked) {
        return { label: 'built-in', cssClass: 'badge-status badge-status--running',
            title: 'addon ships inside the brain image (no separate .vab bundle)' };
    }
    return { label: 'missing', cssClass: 'badge-status badge-status--stale',
        title: 'no on-disk bundle and no Spring bean' };
}
function checksumBadge(addon) {
    switch (addon.checksumStatus) {
        case ChecksumStatus.VERIFIED:
            return { label: 'verified', cssClass: 'badge-status badge-status--running',
                title: 'on-disk .vab hash matches the configured checksum' };
        case ChecksumStatus.MISMATCH:
            return { label: 'mismatch', cssClass: 'badge-status badge-status--stale',
                title: 'on-disk .vab hash does NOT match — entrypoint should have refused this addon' };
        case ChecksumStatus.UNVERIFIED:
            return { label: 'unverified', cssClass: 'badge-status badge-status--starting',
                title: 'checksum set but no source .vab cached to verify against' };
        case ChecksumStatus.NONE:
        default:
            return null;
    }
}
/** "bundled:xyz" → "bundled", "builtin:xyz" → "built-in", URLs → "url". */
function sourceLabel(addon) {
    if (addon.path.startsWith('bundled:'))
        return 'bundled';
    if (addon.path.startsWith('builtin:'))
        return 'built-in';
    return 'url';
}
function sourceDetail(addon) {
    if (addon.path.startsWith('bundled:'))
        return addon.path.substring('bundled:'.length);
    if (addon.path.startsWith('builtin:'))
        return addon.path.substring('builtin:'.length);
    return addon.path;
}
// ─── Time formatting ─────────────────────────────────────────────────
function fmtTime(value) {
    if (value == null)
        return '—';
    if (value instanceof Date)
        return value.toISOString().replace('T', ' ').slice(0, 19);
    return String(value).replace('T', ' ').slice(0, 19);
}
// ─── Aggregates for the toolbar ──────────────────────────────────────
const loadedCount = computed(() => state.addons.value.filter(a => a.loaded).length);
const disabledCount = computed(() => state.addons.value.filter(a => !a.enabled).length);
const brokenCount = computed(() => state.addons.value.filter(a => a.enabled && a.unpacked && !a.beanRegistered).length);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3 p-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-wrap items-end gap-3 text-sm" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (__VLS_ctx.refresh)
};
__VLS_3.slots.default;
var __VLS_3;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs opacity-60 ml-auto" },
});
(__VLS_ctx.state.addons.value.length);
(__VLS_ctx.state.addons.value.length === 1 ? '' : 's');
(__VLS_ctx.loadedCount);
if (__VLS_ctx.disabledCount > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.disabledCount);
}
if (__VLS_ctx.brokenCount > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-red-600" },
    });
    (__VLS_ctx.brokenCount);
}
if (__VLS_ctx.state.loading.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
}
else if (__VLS_ctx.state.error.value) {
    const __VLS_8 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        variant: "error",
    }));
    const __VLS_10 = __VLS_9({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    (__VLS_ctx.state.error.value);
    var __VLS_11;
}
else if (__VLS_ctx.state.addons.value.length === 0) {
    const __VLS_12 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        headline: ('No addons'),
        body: ('The addons collection is empty — no first-party addons bundled, none installed via vance-anus.'),
    }));
    const __VLS_14 = __VLS_13({
        headline: ('No addons'),
        body: ('The addons collection is empty — no first-party addons bundled, none installed via vance-anus.'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
        ...{ class: "table table-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-44" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-28" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-28" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-28" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-32" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-24" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
    for (const [addon] of __VLS_getVForSourceType((__VLS_ctx.state.addons.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
            key: (addon.name),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-medium" },
        });
        (addon.displayName);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-[10px] opacity-50 font-mono" },
        });
        (addon.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (__VLS_ctx.deploymentStatus(addon).cssClass) },
            title: (__VLS_ctx.deploymentStatus(addon).title),
        });
        (__VLS_ctx.deploymentStatus(addon).label);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-80" },
        });
        (__VLS_ctx.sourceLabel(addon));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-[10px] opacity-50 font-mono truncate" },
            title: (__VLS_ctx.sourceDetail(addon)),
        });
        (__VLS_ctx.sourceDetail(addon));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "font-mono text-xs" },
        });
        (addon.version ?? '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        if (addon.status) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "addon-status" },
            });
            (addon.status);
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-30" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-70" },
            title: (__VLS_ctx.fmtTime(addon.unpackedAt)),
        });
        (addon.unpackedAt ? __VLS_ctx.fmtTime(addon.unpackedAt) : '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        if (__VLS_ctx.checksumBadge(addon)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (__VLS_ctx.checksumBadge(addon).cssClass) },
                title: (__VLS_ctx.checksumBadge(addon).title),
            });
            (__VLS_ctx.checksumBadge(addon).label);
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-50" },
            });
        }
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-[11px] opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono" },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['text-red-600']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-44']} */ ;
/** @type {__VLS_StyleScopedClasses['w-28']} */ ;
/** @type {__VLS_StyleScopedClasses['w-28']} */ ;
/** @type {__VLS_StyleScopedClasses['w-28']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['addon-status']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-30']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[11px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            state: state,
            refresh: refresh,
            deploymentStatus: deploymentStatus,
            checksumBadge: checksumBadge,
            sourceLabel: sourceLabel,
            sourceDetail: sourceDetail,
            fmtTime: fmtTime,
            loadedCount: loadedCount,
            disabledCount: disabledCount,
            brokenCount: brokenCount,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=AddonsTab.vue.js.map