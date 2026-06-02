import { brainFetch } from '@vance/shared';
function qs(params) {
    const u = new URLSearchParams();
    for (const [k, v] of Object.entries(params))
        u.set(k, v);
    return u.toString();
}
export async function getSlideshow(projectId, folder) {
    return brainFetch('GET', `slideshow/show?${qs({ projectId, folder })}`);
}
export async function rebuildSlideshow(projectId, folder) {
    return brainFetch('POST', `slideshow/rebuild?${qs({ projectId, folder })}`);
}
//# sourceMappingURL=api.js.map