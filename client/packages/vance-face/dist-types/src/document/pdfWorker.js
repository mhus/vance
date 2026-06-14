/**
 * Worker entry for {@code pdfjs-dist} that runs the
 * {@code Map.prototype.getOrInsertComputed} polyfill in the worker's
 * own global scope before loading the bundled pdf.js worker. Without
 * this, browsers that don't yet implement the TC39 upsert proposal
 * crash with {@code getOrInsertComputed is not a function} as soon as
 * the worker's message handler dispatches its first request.
 *
 * Instantiated from {@link PdfView.vue} / {@link DocumentPreview.vue}
 * via {@code new Worker(new URL('./pdfWorker.ts', import.meta.url),
 * { type: 'module' })} and passed to pdfjs as
 * {@code GlobalWorkerOptions.workerPort}.
 */
import '../polyfills/mapGetOrInsert';
import 'pdfjs-dist/build/pdf.worker.min.mjs';
//# sourceMappingURL=pdfWorker.js.map