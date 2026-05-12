import { useEffect, useRef, useState } from 'react';
import Constants from 'expo-constants';
import { resolveSpeechLanguage } from '@vance/shared';

/**
 * Push-to-talk speech recognition wrapper around
 * `expo-speech-recognition` (community module). Requires a
 * development build — Expo Go does not bundle the native module
 * and {@link isAvailable} returns false there.
 *
 * The hook returns a transcript that updates as partial results
 * arrive; the caller is responsible for committing the final value
 * (e.g. into a composer text input) when {@link stop} resolves.
 *
 * Loading strategy:
 *  - In Expo Go we never even try `import('expo-speech-recognition')`,
 *    so its `requireNativeModule(...)` top-level call doesn't fire.
 *    That keeps the dev console clean of "Cannot find native module"
 *    noise on the platform that intentionally can't run STT.
 *  - In dev / production builds, the dynamic import is wrapped in
 *    try/catch as a defence-in-depth: if the native module is still
 *    missing for any reason, we degrade to a disabled mic button.
 */

// `import type` is fully erased by Babel — the runtime bundle does
// not pull in `expo-speech-recognition` because of this line.
import type * as ExpoSpeechRecognitionLib from 'expo-speech-recognition';
type Module = typeof ExpoSpeechRecognitionLib;

let cachedModule: Module | null = null;
let moduleLoadAttempted = false;

const IS_EXPO_GO = Constants.appOwnership === 'expo';

async function loadModule(): Promise<Module | null> {
  if (IS_EXPO_GO) return null;
  if (cachedModule !== null) return cachedModule;
  if (moduleLoadAttempted) return null;
  moduleLoadAttempted = true;
  try {
    cachedModule = (await import('expo-speech-recognition')) as Module;
    return cachedModule;
  } catch {
    // Defence in depth — native module missing in a non-Expo-Go
    // build (e.g. user prebuilt locally without running `pod install`).
    return null;
  }
}

export async function isAvailable(): Promise<boolean> {
  const mod = await loadModule();
  return mod !== null;
}

export async function ensurePermission(): Promise<boolean> {
  const mod = await loadModule();
  if (mod === null) return false;
  const status = await mod.ExpoSpeechRecognitionModule.getPermissionsAsync();
  if (status.granted) return true;
  const next = await mod.ExpoSpeechRecognitionModule.requestPermissionsAsync();
  return next.granted;
}

interface UseRecognitionState {
  /** Cumulative transcript — partials concatenated into final text. */
  transcript: string;
  /** True while the mic is open and capturing audio. */
  listening: boolean;
  /** Last error message surfaced from the platform recognizer. */
  error: string | null;
  /** Native module is bundled (i.e. running on a dev build). */
  available: boolean;
  start: () => Promise<void>;
  stop: () => Promise<void>;
  reset: () => void;
}

/**
 * Lightweight push-to-talk hook. Hold the mic button → {@link start};
 * release → {@link stop}; commit the {@link transcript} into the
 * composer; call {@link reset} after sending so the next dictation
 * starts clean.
 */
export function useVoiceRecognition(): UseRecognitionState {
  const [transcript, setTranscript] = useState('');
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [available, setAvailable] = useState(false);
  const moduleRef = useRef<Module | null>(null);
  const listenersRef = useRef<Array<() => void>>([]);

  useEffect(() => {
    let cancelled = false;
    void loadModule().then((mod) => {
      if (cancelled) return;
      moduleRef.current = mod;
      setAvailable(mod !== null);
    });
    return () => {
      cancelled = true;
      for (const off of listenersRef.current) off();
      listenersRef.current = [];
      const mod = moduleRef.current;
      if (mod !== null) {
        // Make sure we don't leave the recognizer running when the
        // user navigates away mid-dictation.
        try {
          mod.ExpoSpeechRecognitionModule.stop();
        } catch {
          // best-effort cleanup
        }
      }
    };
  }, []);

  async function start(): Promise<void> {
    setError(null);
    const mod = moduleRef.current;
    if (mod === null) {
      setError('Speech recognition is not available on this build.');
      return;
    }
    const granted = await ensurePermission();
    if (!granted) {
      setError('Microphone permission denied.');
      return;
    }
    setTranscript('');

    // Wire event listeners. The module exposes them as
    // EventEmitter-style add/remove pairs; wrap each in a closure
    // we can dispose from the cleanup arm.
    const onResult = (event: { results: Array<{ transcript: string }>; isFinal: boolean }) => {
      const text = event.results[0]?.transcript ?? '';
      setTranscript(text);
    };
    const onError = (event: { message?: string; error?: string }) => {
      setError(event.message ?? event.error ?? 'Recognition error');
      setListening(false);
    };
    const onEnd = () => {
      setListening(false);
    };

    const subResult = mod.ExpoSpeechRecognitionModule.addListener('result', onResult);
    const subError = mod.ExpoSpeechRecognitionModule.addListener('error', onError);
    const subEnd = mod.ExpoSpeechRecognitionModule.addListener('end', onEnd);
    listenersRef.current = [
      () => subResult.remove(),
      () => subError.remove(),
      () => subEnd.remove(),
    ];

    try {
      await mod.ExpoSpeechRecognitionModule.start({
        lang: resolveSpeechLanguage(),
        interimResults: true,
        continuous: false,
      });
      setListening(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to start recognition');
      setListening(false);
    }
  }

  async function stop(): Promise<void> {
    const mod = moduleRef.current;
    if (mod === null) return;
    try {
      await mod.ExpoSpeechRecognitionModule.stop();
    } catch {
      // recognizer may already be stopping
    }
    setListening(false);
    for (const off of listenersRef.current) off();
    listenersRef.current = [];
  }

  function reset(): void {
    setTranscript('');
    setError(null);
  }

  return { transcript, listening, error, available, start, stop, reset };
}
