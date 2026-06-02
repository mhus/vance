// Barrel for the slideshow addon's client surface. Consumers
// (vance-face today, federated remotes in the future) import the
// editor component, REST helpers and wire-contract DTOs from here.

export { default as SlideshowApp } from './SlideshowApp.vue';
export { getSlideshow, rebuildSlideshow } from './api';
export type { SlideView } from './generated/slideshow/SlideView';
export type { SlideshowView } from './generated/slideshow/SlideshowView';
export type { SlideshowRebuildResponse } from './generated/slideshow/SlideshowRebuildResponse';
