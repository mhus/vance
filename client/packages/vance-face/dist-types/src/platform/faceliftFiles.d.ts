export interface ExportToFilesOptions {
    /** Suggested filename for the share-sheet — the user can override
     *  it when picking "Save to Files". */
    name: string;
    /** MIME type for the temporary file. */
    mime: string;
    /** Bytes to export. */
    data: Blob;
}
/**
 * Open the iOS share-sheet with a temporary file, letting the user
 * choose where to save it ("Save to Files", "Save Image", AirDrop,
 * mail, third-party apps). The file is written into the app's
 * temporary directory on the native side and is best-effort cleaned
 * up by iOS later.
 */
export declare function exportToFiles(opts: ExportToFilesOptions): Promise<void>;
//# sourceMappingURL=faceliftFiles.d.ts.map