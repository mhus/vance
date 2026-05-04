import type { ExpoConfig, ConfigContext } from 'expo/config';

/**
 * Expo runtime configuration. Read by the Expo CLI on every start
 * and bundled into the app at build time.
 *
 * The brain URL travels via {@code extra.brainUrl} into
 * {@code Constants.expoConfig?.extra} and is consumed by
 * {@code src/platform/bootNative.ts} when wiring up
 * {@code @vance/shared}'s {@code RestConfig}.
 *
 * Override per environment with the {@code VANCE_BRAIN_URL}
 * environment variable when starting Expo:
 *
 *   VANCE_BRAIN_URL=http://10.0.0.5:8080 pnpm start
 *
 * The default points at a same-host Brain on the standard dev port —
 * works on simulators, but iOS/Android *devices* on the same Wi-Fi
 * need the laptop's LAN IP because {@code localhost} resolves to the
 * device itself.
 */
export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: 'Vance',
  slug: 'vance-mobile',
  scheme: 'vance',
  version: '0.1.0',
  orientation: 'portrait',
  userInterfaceStyle: 'automatic',
  newArchEnabled: true,
  ios: {
    bundleIdentifier: 'de.mhus.vance.mobile',
    supportsTablet: false,
    infoPlist: {
      // Phase F (Voice) prompts trigger on the first mic-button press;
      // declaring the strings here avoids an App Store review block
      // even though the keys are not exercised in Phase A.
      NSMicrophoneUsageDescription:
        'Vance uses the microphone to dictate chat input.',
      NSSpeechRecognitionUsageDescription:
        'Vance uses speech recognition to convert your spoken input to chat text.',
      // Lets the chat speaker keep reading aloud when the screen
      // locks. Only relevant when Phase F is in.
      UIBackgroundModes: ['audio'],
    },
  },
  android: {
    package: 'de.mhus.vance.mobile',
    permissions: ['RECORD_AUDIO'],
  },
  // Expo config plugins. `expo-secure-store` registers itself here so
  // its native module (Keychain on iOS, Keystore on Android) is wired
  // into the prebuild output. Adding additional config plugins (e.g.
  // for `@react-native-voice/voice` in Phase F) goes in this array.
  plugins: ['expo-secure-store'],
  extra: {
    brainUrl: process.env.VANCE_BRAIN_URL ?? 'http://localhost:8080',
  },
});
