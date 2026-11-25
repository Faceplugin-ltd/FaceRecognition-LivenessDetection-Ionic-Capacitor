import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';
import { Capacitor } from '@capacitor/core';
import { pickFolderImages as nativePickFolder } from 'face-recognition-capacitor';

export async function pickGalleryPhoto(): Promise<string> {
  const photo = await Camera.getPhoto({
    quality: 90,
    source: CameraSource.Photos,
    resultType: CameraResultType.Uri,
  });
  const uri = photo.webPath || photo.path;
  if (!uri) {
    throw new Error('No photo selected');
  }
  return photo.path
    ? photo.path.startsWith('file://') || photo.path.startsWith('content:')
      ? photo.path
      : `file://${photo.path}`
    : uri;
}

/** The most photos one import run will take, so the page cannot hold an unbounded set. */
export const MAX_IMPORT_PHOTOS = 50;

/**
 * Several photos at once, for Bulk enroll from photos. Each becomes one person.
 *
 * Returns data URIs the native engine can decode (same approach as Cordova). Capacitor
 * `pickImages` paths are often unreadable from the plugin process on iOS, which made
 * every bulk enroll look like "No face found".
 */
export async function pickImages(): Promise<string[]> {
  try {
    const files = await chooseFiles();
    const out: string[] = [];
    for (const file of files.slice(0, MAX_IMPORT_PHOTOS)) {
      const uri = await readAsDataUri(file);
      if (uri) out.push(uri);
    }
    return out;
  } catch {
    // The user cancelling the picker is the common case.
    return [];
  }
}

export async function pickFolderImages(): Promise<string[]> {
  try {
    const items = await nativePickFolder();
    return items
      .map((i) => i.uri)
      .filter((p) => p.length > 0)
      .map((p) =>
        p.startsWith('file://') || p.startsWith('content:') || p.startsWith('data:')
          ? p
          : `file://${p}`
      )
      .slice(0, MAX_IMPORT_PHOTOS);
  } catch {
    return [];
  }
}

function chooseFiles(): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.style.display = 'none';
    input.onchange = () => {
      resolve(Array.from(input.files ?? []));
      input.remove();
    };
    document.body.appendChild(input);
    input.click();
  });
}

function readAsDataUri(file: File): Promise<string | null> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () =>
      resolve(typeof reader.result === 'string' ? reader.result : null);
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(file);
  });
}

export function thumbSrc(b64: string | null | undefined): string | undefined {
  if (!b64) return undefined;
  return b64.startsWith('data:') ? b64 : `data:image/jpeg;base64,${b64}`;
}

export function displayUri(uri: string | null | undefined): string | undefined {
  if (!uri) return undefined;
  if (uri.startsWith('data:') || uri.startsWith('blob:') || uri.startsWith('http')) {
    return uri;
  }
  try {
    return Capacitor.convertFileSrc(uri);
  } catch {
    return uri;
  }
}

export function measureImage(uri: string): Promise<{ w: number; h: number }> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () =>
      resolve({
        w: img.naturalWidth || img.width,
        h: img.naturalHeight || img.height,
      });
    img.onerror = () => resolve({ w: 0, h: 0 });
    img.src = displayUri(uri) ?? uri;
  });
}
