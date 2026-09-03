/**
 * English messages of the slideshow addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  slideshow: {
    position: 'Slide {index} / {total}',
    pause: 'Pause',
    play: 'Play ({seconds}s)',
    exitFullscreen: 'Exit fullscreen',
    fullscreen: 'Fullscreen (F)',
    reload: 'Reload',
    rebuild: 'Rebuild index',
    loading: 'Loading slideshow…',
    emptyHeadline: 'No slides',
    emptyBody: 'Upload images into this folder, then click “Rebuild index” to refresh the slideshow.',
    prevSlide: 'Previous slide (←)',
    nextSlide: 'Next slide (→)',
    slideNumber: 'Slide {index}',
    error: {
      load: 'Could not load slideshow: {message}',
      rebuild: 'Rebuild failed: {message}',
    },
  },
};
