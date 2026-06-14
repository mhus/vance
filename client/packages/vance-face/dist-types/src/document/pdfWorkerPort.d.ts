/**
 * Singleton {@code GlobalWorkerOptions.workerPort} for {@code pdfjs-dist}.
 * Both {@link PdfView.vue} and {@link DocumentPreview.vue} render PDFs;
 * each instantiating its own Worker would waste memory and race the
 * {@code GlobalWorkerOptions} singleton. Lazy-initialised on first
 * call so the worker bundle only loads when a PDF is actually opened.
 *
 * The worker entry ({@code pdfWorker.ts}) polyfills
 * {@code Map.prototype.getOrInsertComputed} in the worker's global
 * scope before importing the bundled pdfjs worker — required on
 * runtimes that haven't shipped the TC39 upsert proposal yet.
 */
import type { GlobalWorkerOptions as PdfGlobalWorkerOptions } from 'pdfjs-dist';
export declare function getPdfWorkerPort(): Worker;
/**
 * Installs the singleton worker on {@code pdfjs.GlobalWorkerOptions}.
 * Idempotent — re-calling with the same module reference reuses the
 * port; the polyfill's {@code Map.prototype} extensions are also
 * applied lazily on first import of this module's siblings.
 */
export declare function configurePdfWorker(globalWorkerOptions: typeof PdfGlobalWorkerOptions): void;
//# sourceMappingURL=pdfWorkerPort.d.ts.map