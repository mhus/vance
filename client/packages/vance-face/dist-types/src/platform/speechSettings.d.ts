export declare function getSpeechVoiceURI(): string | null;
export declare function getSpeechRate(): number;
export declare function getSpeechVolume(): number;
export declare function getSpeakerEnabled(): boolean;
/**
 * Speech recognition / synthesis language. Cascade:
 *   1. `chat.language` — explicit assistant-language choice from the
 *      profile page; wins because the user picked it for the chat
 *      itself.
 *   2. `webui.language` — the broader UI-language choice. Used when
 *      the user has not split assistant-language from UI-language —
 *      the common case.
 *   3. {@code navigator.language}.
 *   4. `'en-US'`.
 *
 * Short tags (`'de'`, `'en'`) get expanded to a BCP-47 region tag the
 * Web Speech API understands.
 */
export declare function resolveSpeechLanguage(): string;
export declare function saveSpeechVoiceURI(uri: string | null): Promise<void>;
export declare function saveSpeechRate(rate: number): Promise<void>;
export declare function saveSpeechVolume(volume: number): Promise<void>;
export declare function saveSpeakerEnabled(enabled: boolean): Promise<void>;
//# sourceMappingURL=speechSettings.d.ts.map