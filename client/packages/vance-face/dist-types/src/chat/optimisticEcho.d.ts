/**
 * Shared constant for optimistic-echo message ids. The composer
 * (ChatComposer) generates ids with this prefix when it pushes a
 * provisional bubble at send-time; the message stream (ChatView)
 * uses the prefix to detect those entries during the dedup pass when
 * the canonical server frame arrives.
 *
 * Kept here rather than inside either component because `<script setup>`
 * forbids ES module exports, and both components need it.
 */
export declare const OPTIMISTIC_PREFIX = "tmp_";
//# sourceMappingURL=optimisticEcho.d.ts.map