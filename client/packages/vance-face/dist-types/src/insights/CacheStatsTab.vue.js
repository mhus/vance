import { computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VCard, VEmptyState } from '@/components';
import { useCacheStats } from '@/composables/useCacheStats';
const props = defineProps();
const { t } = useI18n();
const { stats, loading, error, load, reset } = useCacheStats();
watch(() => props.processId, async (next) => {
    if (!next) {
        reset();
        return;
    }
    await load(next);
}, { immediate: true });
/** Aggregate input including the cached portions — denominator of the
 *  hit-rate formula. Used to detect "no data" and to render absolute
 *  totals in the table. */
const totalInput = computed(() => {
    if (!stats.value)
        return 0;
    return (stats.value.inputTokens
        + stats.value.cacheCreationInputTokens
        + stats.value.cacheReadInputTokens);
});
/** Hit-rate as a percentage, 0–100. */
const hitRatePct = computed(() => (stats.value ? stats.value.hitRate * 100 : 0));
/** Cost-saved: cache-read tokens are billed at ~10% of input vs. ~100%
 *  if they had to come in fresh. Saved = cacheRead × 0.9. Display only,
 *  approximate ("~"); the actual price depends on model + tier. */
const tokensSaved = computed(() => {
    if (!stats.value)
        return 0;
    return Math.round(stats.value.cacheReadInputTokens * 0.9);
});
function fmt(n) {
    if (n < 1_000)
        return String(n);
    if (n < 1_000_000)
        return `${(n / 1_000).toFixed(1)}k`;
    return `${(n / 1_000_000).toFixed(1)}M`;
}
function fmtPct(n) {
    return `${n.toFixed(1)}%`;
}
/** Pick a colour bucket for the hit-rate bar — green for ≥70% (the
 *  spec target), amber for 40–70%, red below. */
const hitRateClass = computed(() => {
    if (hitRatePct.value >= 70)
        return 'rate-bar--good';
    if (hitRatePct.value >= 40)
        return 'rate-bar--mid';
    return 'rate-bar--bad';
});
async function reload() {
    await load(props.processId);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.error) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "error",
    }));
    const __VLS_2 = __VLS_1({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.error);
    var __VLS_3;
}
if (__VLS_ctx.loading && !__VLS_ctx.stats) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-70" },
    });
    (__VLS_ctx.t('insights.cacheStats.loading'));
}
else if (!__VLS_ctx.loading && (!__VLS_ctx.stats || __VLS_ctx.stats.roundTrips === 0)) {
    const __VLS_4 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        headline: (__VLS_ctx.t('insights.cacheStats.emptyHeadline')),
        body: (__VLS_ctx.t('insights.cacheStats.emptyBody')),
    }));
    const __VLS_6 = __VLS_5({
        headline: (__VLS_ctx.t('insights.cacheStats.emptyHeadline')),
        body: (__VLS_ctx.t('insights.cacheStats.emptyBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else if (__VLS_ctx.stats) {
    const __VLS_8 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        title: (__VLS_ctx.t('insights.cacheStats.headlineTitle')),
    }));
    const __VLS_10 = __VLS_9({
        title: (__VLS_ctx.t('insights.cacheStats.headlineTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-baseline gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-4xl font-semibold" },
    });
    (__VLS_ctx.fmtPct(__VLS_ctx.hitRatePct));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm opacity-70" },
    });
    (__VLS_ctx.t('insights.cacheStats.headlineSub', { rounds: __VLS_ctx.stats.roundTrips }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "rate-bar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: "rate-bar__fill" },
        ...{ class: (__VLS_ctx.hitRateClass) },
        ...{ style: ({ width: `${Math.min(__VLS_ctx.hitRatePct, 100)}%` }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.headlineHint'));
    var __VLS_11;
    const __VLS_12 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        title: (__VLS_ctx.t('insights.cacheStats.breakdownTitle')),
    }));
    const __VLS_14 = __VLS_13({
        title: (__VLS_ctx.t('insights.cacheStats.breakdownTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    __VLS_15.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
        ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.cacheRead'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.fmt(__VLS_ctx.stats.cacheReadInputTokens));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.cacheCreate'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.fmt(__VLS_ctx.stats.cacheCreationInputTokens));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.uncachedInput'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.fmt(__VLS_ctx.stats.inputTokens));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.totalInput'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
        ...{ class: "font-mono font-semibold" },
    });
    (__VLS_ctx.fmt(__VLS_ctx.totalInput));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.outputTokens'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.fmt(__VLS_ctx.stats.outputTokens));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.t('insights.cacheStats.tokensSaved'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.fmt(__VLS_ctx.tokensSaved));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60 mt-3" },
    });
    (__VLS_ctx.t('insights.cacheStats.savingsHint'));
    var __VLS_15;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex justify-end" },
    });
    const __VLS_16 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        onClick: (__VLS_ctx.reload)
    };
    __VLS_19.slots.default;
    (__VLS_ctx.t('insights.cacheStats.reload'));
    var __VLS_19;
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['rate-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['rate-bar__fill']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            t: t,
            stats: stats,
            loading: loading,
            error: error,
            totalInput: totalInput,
            hitRatePct: hitRatePct,
            tokensSaved: tokensSaved,
            fmt: fmt,
            fmtPct: fmtPct,
            hitRateClass: hitRateClass,
            reload: reload,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=CacheStatsTab.vue.js.map