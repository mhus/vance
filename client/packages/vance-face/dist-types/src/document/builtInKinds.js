/**
 * Bootstrap-time registration of host-built-in document Kinds.
 *
 * The runtime {@code @vance/kind-registry} is the single place
 * DocumentApp.vue looks up a Kind's view + codec for any
 * registry-driven branch. Built-ins land here; addons populate
 * the same registry from their {@code ./register} federation
 * expose. When a Kind moves from built-in to addon, the call
 * below moves verbatim into the addon's register.ts and this file
 * shrinks by one entry — DocumentApp.vue stays unchanged.
 *
 * Only Kinds that DocumentApp.vue dispatches *via the registry*
 * land here. Most built-ins still use the static {@code if/else}
 * dispatch and don't need a registration — they'll migrate as
 * additional addons get carved out.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
export function registerBuiltInKinds() {
    // ── Markdown: code-preview toggle ──────────────────────────────
    // Markdown files resolve to the catch-all 'code' binding in
    // docTypeRegistry (resolveBinding skips Kind entries without a
    // view). The codePreview field gives the shell a rendered
    // MarkdownView for the View/Edit toggle — raw CodeEditor in
    // 'edit', rendered HTML in 'view'. No view/codec needed.
    registerKind({
        id: 'markdown',
        matches: (_kind, mime) => mime === 'text/markdown',
        codePreview: defineAsyncComponent(() => import('@/components/MarkdownView.vue')),
    });
    // ── TeX: KaTeX code-preview toggle ─────────────────────────────
    // Same pattern as Markdown: .tex files resolve to the catch-all
    // 'code' binding, but get a View/Edit toggle via codePreview —
    // KaTeX-rendered formula preview in 'view', raw CodeEditor with
    // stex highlighting in 'edit'. The "Generate PDF" run adapter
    // handles full LaTeX compilation independently.
    registerKind({
        id: 'tex',
        matches: (_kind, mime) => mime === 'text/x-tex' || mime === 'application/x-tex',
        codePreview: defineAsyncComponent(() => import('@/cortex/components/TexPreview.vue')),
    });
}
//# sourceMappingURL=builtInKinds.js.map