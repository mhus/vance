export interface ApplicationDocument {
    /** Always `'application'`. */
    kind: string;
    /** App-type discriminator. Known v1 value: `'calendar'`. */
    app: string;
    title?: string;
    description?: string;
    /** App-specific configuration, keyed by the app type at the top
     *  level (e.g. `config.calendar = { … }`). The nested form lets a
     *  folder host multiple app faces in v2 without schema work. */
    config: Record<string, unknown>;
    extra: Record<string, unknown>;
}
export declare class ApplicationCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseApplication(body: string, mimeType: string): ApplicationDocument;
export declare function serializeApplication(doc: ApplicationDocument, mimeType: string): string;
export declare function isApplicationMime(mimeType: string | null | undefined): boolean;
export declare function emptyApplication(app?: string): ApplicationDocument;
export declare function applicationAppType(doc: ApplicationDocument): string;
//# sourceMappingURL=applicationCodec.d.ts.map