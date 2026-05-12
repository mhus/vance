// Barrel for the mobile platform layer. Editor screens import from
// here. `bootNative` is imported separately as a side effect at the
// top of `App.tsx` before this barrel is touched.

export { storageNative, preloadStorage } from './storageNative';
export { bootStoragePromise } from './bootNative';
