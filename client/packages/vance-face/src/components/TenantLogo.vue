<script setup lang="ts">
/**
 * Topbar brand mark with a tenant override: renders the tenant's own
 * logo (operator-uploaded to `_vance/config/logo.<ext>`, served by
 * `GET /brain/{tenant}/ui/logo`) and falls back to the bundled Vance
 * "v" (VanceLogo) when the endpoint answers anything other than an
 * image — 404 when the tenant has none, or a failed load.
 *
 * The fallback stays client-side on purpose: the glyph keeps its
 * `currentColor` tinting (theme-aware light/dark) and remains a single
 * source — a server-side fallback answer would need a baked-in fill
 * colour and would be wrong in one of the two themes. See the
 * controller javadoc (`UiCustomizationController#logo`) for the
 * contract.
 *
 * The size is pinned here, not at the call site: the box is fixed at
 * the topbar's sm height (20px) and `object-contain` absorbs whatever
 * aspect ratio the uploaded logo has — no layout jump, no distortion.
 */
import { ref } from 'vue';
import VanceLogo from './VanceLogo.vue';
import { tenantLogoUrl } from '@vance/shared';

const url = tenantLogoUrl();
const failed = ref(false);
</script>

<template>
  <img
    v-if="url && !failed"
    :src="url"
    class="inline-block align-middle h-5 w-5 object-contain"
    alt=""
    @error="failed = true"
  />
  <VanceLogo v-else size="sm" class="text-primary" />
</template>
