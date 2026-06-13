/**
 * Native file / camera / share bridges, scoped to the Facelift
 * Capacitor wrapper. Every helper here checks {@link isFacelift} and
 * throws when called from a plain browser context — UI code should
 * guard the entry-point with the same check before showing the
 * native-only affordance. See `instructions/web-ui.md` and
 * `repos/vance/client/packages/facelift-bridge/README.md` for the
 * detection convention.
 *
 * The helpers normalise their results to a {@link PickedFile} shape
 * that wraps a plain {@link File} object, so the existing
 * {@code useDocuments.upload(...)} and chat-attachment FormData
 * paths can consume them without changes.
 */
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';
import { Directory, Filesystem } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';
import { FilePicker } from '@capawesome/capacitor-file-picker';
import { isFacelift } from '@vance/shared';

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

function ensureFacelift(): void {
  if (!isFacelift()) {
    throw new Error(
      'Facelift file APIs are only available inside the Facelift wrapper.',
    );
  }
}

/**
 * Capture a photo with the camera or pick one from the photo
 * library. Resolves to {@code null} when the user cancels.
 */
export async function pickPhoto(opts: PickPhotoOptions): Promise<PickedFile | null> {
  ensureFacelift();
  try {
    const photo = await Camera.getPhoto({
      quality: 90,
      resultType: CameraResultType.Base64,
      source: opts.source === 'camera' ? CameraSource.Camera : CameraSource.Photos,
      saveToGallery: false,
    });
    if (photo.base64String === undefined) return null;
    const mime = `image/${photo.format ?? 'jpeg'}`;
    const blob = base64ToBlob(photo.base64String, mime);
    const name = `photo-${Date.now()}.${photo.format ?? 'jpg'}`;
    return { name, mime, file: new File([blob], name, { type: mime }) };
  } catch (e) {
    if (isUserCancelled(e)) return null;
    throw e;
  }
}

/**
 * Pick one or more files via the iOS Files picker. Includes Files
 * locations (iCloud Drive, on-device, third-party providers like
 * Google Drive when installed).
 *
 * The picker is dismissed without a result when the user cancels;
 * that case resolves to {@code []}.
 */
export async function pickFiles(opts: PickFilesOptions = {}): Promise<PickedFile[]> {
  ensureFacelift();
  try {
    const result = await FilePicker.pickFiles({
      types: opts.types,
      limit: opts.multiple === true ? 0 : 1,
      readData: true,
    });
    return await Promise.all(result.files.map(toPickedFile));
  } catch (e) {
    if (isUserCancelled(e)) return [];
    throw e;
  }
}

/**
 * Open the iOS share-sheet with a temporary file, letting the user
 * choose where to save it ("Save to Files", "Save Image", AirDrop,
 * mail, third-party apps). The file is written into the app's cache
 * directory and is best-effort cleaned up by iOS later.
 */
export async function exportToFiles(opts: ExportToFilesOptions): Promise<void> {
  ensureFacelift();
  const base64 = await blobToBase64(opts.data);
  // Sanitise filename — slashes would create directories in the
  // cache hierarchy and confuse the share sheet.
  const safeName = opts.name.replace(/[/\\]+/g, '_');
  const written = await Filesystem.writeFile({
    path: safeName,
    data: base64,
    directory: Directory.Cache,
  });
  await Share.share({
    url: written.uri,
    title: opts.name,
    dialogTitle: opts.name,
  });
}

async function toPickedFile(f: {
  name: string;
  mimeType?: string;
  data?: string;
  path?: string;
}): Promise<PickedFile> {
  const mime = f.mimeType ?? 'application/octet-stream';
  let blob: Blob;
  if (f.data !== undefined && f.data.length > 0) {
    blob = base64ToBlob(f.data, mime);
  } else if (f.path !== undefined && f.path.length > 0) {
    const read = await Filesystem.readFile({ path: f.path });
    const data = typeof read.data === 'string' ? read.data : '';
    blob = base64ToBlob(data, mime);
  } else {
    throw new Error(`File "${f.name}" has neither inline data nor a path`);
  }
  return { name: f.name, mime, file: new File([blob], f.name, { type: mime }) };
}

function base64ToBlob(base64: string, mime: string): Blob {
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: mime });
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      const comma = result.indexOf(',');
      resolve(comma === -1 ? result : result.slice(comma + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'));
    reader.readAsDataURL(blob);
  });
}

function isUserCancelled(e: unknown): boolean {
  if (typeof e !== 'object' || e === null) return false;
  const msg = (e as { message?: string }).message;
  if (typeof msg !== 'string') return false;
  return /user (cancel|cancelled|canceled)|pickerWasCancelled/i.test(msg);
}
