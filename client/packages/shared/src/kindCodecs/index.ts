/**
 * The four **data** kind codecs: `records`, `sheet`, `list`, `tree`.
 *
 * <p>They read and write the on-disk body of a kind document — a
 * markdown CSV-light grammar, or json/yaml. Pure functions over strings: no
 * Vue, no browser API, which is why they belong in this package and not in a
 * UI one.
 *
 * <p><b>Why they moved here.</b> They lived in `vance-face/src/kindViews/`,
 * where only the web host could reach them. Two consumers need them outside
 * it: a Brain addon's client bundle (module federation — it cannot import from
 * the host), and any surface that has a document body and wants the structure
 * rather than the text. The first concrete one is the Bistromath runtime, whose
 * programs read documents the built-in editors also edit; without the codec, a
 * `kind: records` document arrives as a wall of markdown.
 *
 * <p><b>Parity is load-bearing.</b> Each of these has a Java twin in
 * `vance-shared/.../document/kind/` and a shared fixture corpus under
 * `test-fixtures/kind-codecs/`. The `*.parity.test.ts` files next to each codec
 * read that corpus, and so do the Java `*CodecParityTest`s. Edit a codec and
 * the corpus together, or the two halves drift and a document written by a tool
 * stops being readable by an editor.
 *
 * <p><b>Only the data kinds.</b> `chart`, `diagram`, `map`, `slides` and
 * `workflow` stay in the host: they are presentation, a program has little
 * reason to write one, and an `embed` already displays them.
 */
export * from './listItemsCodec';
export * from './recordsCodec';
export * from './sheetCodec';
export * from './treeItemsCodec';
