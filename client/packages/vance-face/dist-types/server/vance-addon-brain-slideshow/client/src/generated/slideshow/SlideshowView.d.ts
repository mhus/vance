import { SlideView } from './SlideView';
export interface SlideshowView {
    folder: string;
    manifestPath: string;
    title?: string;
    description?: string;
    autoplaySeconds: number;
    aspectRatio?: string;
    slides: SlideView[];
}
//# sourceMappingURL=SlideshowView.d.ts.map