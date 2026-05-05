import { useEffect, useRef, useState } from 'react';
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
 * The module is loaded lazily so the rest of the app keeps working
 * on Expo Go even when this file is imported (the dynamic import
 * happens only inside the hook's start path).
 */

type Module = typeof import('expo-speech-recognition');

let cachedModule: Module | null = null;
let moduleLoadAttempted = false;

async function loadModule(): Promise<Module | null> {
  if (cachedModule !== null) return cachedModule;
  if (moduleLoadAttempted) return null;
  moduleLoadAttempted = true;
  try {
    cachedModule = (await import('expo-speech-recognition')) as Module;
    return cachedModule;
  } catch {
    // Expo Go: native module not bundled. Caller falls back to a
    // disabled mic button.
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
