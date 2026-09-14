<script setup lang="ts">
/**
 * Renderer for `kind: qrcode` documents — draws the payload (usually
 * a URL) as a scannable QR symbol on a canvas, powered by the
 * `qrcode` package (client-side only; the server never generates
 * image bytes).
 *
 * Three modes, same contract as MapView / FormulaView:
 *   - `editor`   — inside the Cortex document editor; `doc` is the
 *                  raw body string (identity codec from the kind
 *                  registry), the shell's Edit toggle shows the raw
 *                  source in a CodeEditor.
 *   - `inline`   — chat fence body in `content`, options via fence
 *                  language params (` ```qrcode size=512 `).
 *   - `embedded` — loaded {@link DocumentDto} via a `vance://` ref.
 *
 * Read-only in all modes — editing happens on the raw text; the
 * payload is whatever the user (or the LLM) wrote.
 *
 * Spec: `specification/public/doc-kind-qrcode.md`.
 */
import { computed, ref, watch } from 'vue';
import QRCode from 'qrcode';
import { VAlert, VButton, VEmptyState } from '@vance/components';
import { useT } from '@vance/components';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import { applyFenceMeta, parseQrCodeDoc, type QrCodeDoc } from './qrcodeCodec';

defineOptions({ name: 'QrCodeView' });

const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  /** Editor mode — raw body string (identity codec). */
  doc?: string;
  /** Inline mode — fence body. */
  content?: string;
  /** Inline mode — fence-language params. */
  meta?: FenceMeta;
  /** Embedded mode — loaded Document. */
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  doc: '',
  meta: () => ({}),
});

const t = useT();

const parsed = computed<QrCodeDoc>(() => {
  if (props.mode === 'inline') {
    return applyFenceMeta(parseQrCodeDoc(props.content ?? ''), props.meta);
  }
  if (props.mode === 'embedded') {
    return parseQrCodeDoc(props.document?.inlineText ?? '');
  }
  return parseQrCodeDoc(props.doc);
});

const canvasRef = ref<HTMLCanvasElement | null>(null);
const renderError = ref<string | null>(null);

/** Render (or re-render) the symbol whenever the parsed doc changes. */
async function render(): Promise<void> {
  const canvas = canvasRef.value;
  const payload = parsed.value.payload;
  if (!canvas || !payload) return;
  try {
    await QRCode.toCanvas(canvas, payload, {
      width: parsed.value.size,
      margin: parsed.value.margin,
      errorCorrectionLevel: parsed.value.ecc,
      color: { dark: parsed.value.dark, light: parsed.value.light },
    });
    renderError.value = null;
  } catch (e) {
    // Payload too long for a QR symbol (or a broken canvas) — surface
    // the message instead of an empty box.
    renderError.value = e instanceof Error ? e.message : String(e);
  }
}

watch(
  () => [parsed.value, canvasRef.value] as const,
  () => void render(),
  { immediate: true, flush: 'post' },
);

/** Download the rendered canvas as a PNG file. */
function downloadPng(): void {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const url = canvas.toDataURL('image/png');
  const a = document.createElement('a');
  a.href = url;
  a.download = 'qrcode.png';
  a.click();
}
</script>

<template>
  <div class="flex flex-col items-center gap-4 p-4">
    <VEmptyState
      v-if="!parsed.payload"
      :headline="t('kindViews.qrcode.empty')"
    />
    <template v-else>
      <VAlert v-if="renderError" variant="error">
        {{ t('kindViews.qrcode.error') }} {{ renderError }}
      </VAlert>
      <canvas
        ref="canvasRef"
        class="rounded border border-base-300 bg-white max-w-full h-auto"
        :aria-label="t('kindViews.qrcode.canvasLabel')"
        role="img"
      />
      <div v-if="parsed.label" class="text-sm text-base-content/70">
        {{ parsed.label }}
      </div>
      <code class="text-xs break-all text-center max-w-full text-base-content/60">
        {{ parsed.payload }}
      </code>
      <VButton variant="secondary" size="sm" @click="downloadPng">
        {{ t('kindViews.qrcode.download') }}
      </VButton>
    </template>
  </div>
</template>
