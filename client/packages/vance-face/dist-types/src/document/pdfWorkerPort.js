let port = null;
export function getPdfWorkerPort() {
    if (port === null) {
        port = new Worker(new URL('./pdfWorker.ts', import.meta.url), {
            type: 'module',
        });
    }
    return port;
}
/**
 * Installs the singleton worker on {@code pdfjs.GlobalWorkerOptions}.
 * Idempotent — re-calling with the same module reference reuses the
 * port; the polyfill's {@code Map.prototype} extensions are also
 * applied lazily on first import of this module's siblings.
 */
export function configurePdfWorker(globalWorkerOptions) {
    globalWorkerOptions.workerPort =
        getPdfWorkerPort();
}
//# sourceMappingURL=pdfWorkerPort.js.map