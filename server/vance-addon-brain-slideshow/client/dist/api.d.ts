import type { SlideshowRebuildResponse } from './generated/slideshow/SlideshowRebuildResponse';
import type { SlideshowView } from './generated/slideshow/SlideshowView';
export declare function getSlideshow(projectId: string, folder: string): Promise<SlideshowView>;
export declare function rebuildSlideshow(projectId: string, folder: string): Promise<SlideshowRebuildResponse>;
//# sourceMappingURL=api.d.ts.map