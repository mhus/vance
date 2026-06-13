export interface PickedFile {
    /** Best-effort filename for upload metadata. */
    name: string;
    /** MIME type — `application/octet-stream` when iOS cannot tell. */
    mime: string;
    /** Wrapped as a native {@link File} so callers can drop it into a
     *  {@link FormData} without further conversion. */
    file: File;
}
export interface PickPhotoOptions {
    /** {@code 'camera'} launches the capture UI; {@code 'library'}
     *  shows the Photo-Library picker. */
    source: 'camera' | 'library';
}
export interface PickFilesOptions {
    /** Optional MIME filter (e.g. {@code ['application/pdf']}). When
     *  omitted, all file types are visible in the picker. */
    types?: string[];
    /** Allow multi-selection. Default {@code false}. */
    multiple?: boolean;
}
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
 * Capture a photo with the camera or pick one from the photo
 * library. Resolves to {@code null} when the user cancels.
 */
export declare function pickPhoto(opts: PickPhotoOptions): Promise<PickedFile | null>;
/**
 * Pick one or more files via the iOS Files picker. Includes Files
 * locations (iCloud Drive, on-device, third-party providers like
 * Google Drive when installed).
 *
 * The picker is dismissed without a result when the user cancels;
 * that case resolves to {@code []}.
 */
export declare function pickFiles(opts?: PickFilesOptions): Promise<PickedFile[]>;
/**
 * Open the iOS share-sheet with a temporary file, letting the user
 * choose where to save it ("Save to Files", "Save Image", AirDrop,
 * mail, third-party apps). The file is written into the app's cache
 * directory and is best-effort cleaned up by iOS later.
 */
export declare function exportToFiles(opts: ExportToFilesOptions): Promise<void>;
//# sourceMappingURL=faceliftFiles.d.ts.map