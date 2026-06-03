import { a as __mf_93, b as __mf_45, c as __mf_80, d as __mf_126, e as __mf_122, f as __mf_132, g as __mf_83, h as __mf_84, i as __mf_61, j as __mf_90, k as __mf_69, l as __mf_82, m as __mf_81, n as __mf_55, o as __mf_166, p as __mf_91, q as __mf_60, r as __mf_24, s as __mf_138, t as __mf_58 } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare__vue__loadShare__.mjs-2_nCHSZU.js';
import { b as __mf_2, c as __mf_0, d as __mf_8 } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_components__loadShare__.mjs-BqfXZy3L.js';
import { a as __mf_26, b as __mf_30 } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_shared__loadShare__.mjs-CZrqzurX.js';

function qs(params) {
    const u = new URLSearchParams();
    for (const [k, v] of Object.entries(params))
        u.set(k, v);
    return u.toString();
}
async function getSlideshow(projectId, folder) {
    return __mf_26('GET', `slideshow/show?${qs({ projectId, folder })}`);
}
async function rebuildSlideshow(projectId, folder) {
    return __mf_26('POST', `slideshow/rebuild?${qs({ projectId, folder })}`);
}

const _hoisted_1 = { class: "flex flex-col h-full bg-base-100" };
const _hoisted_2 = { class: "flex items-center justify-between p-4 border-b border-base-300" };
const _hoisted_3 = { class: "text-xl font-semibold" };
const _hoisted_4 = { class: "text-sm text-base-content/60 mt-0.5" };
const _hoisted_5 = { class: "flex gap-2 items-center" };
const _hoisted_6 = {
  key: 1,
  class: "p-8 text-base-content/70"
};
const _hoisted_7 = { class: "flex-1 w-full flex items-center justify-center min-h-0" };
const _hoisted_8 = ["src", "alt"];
const _hoisted_9 = {
  key: 0,
  class: "text-center text-sm opacity-80 max-w-2xl"
};
const _hoisted_10 = {
  key: 3,
  class: "flex gap-1.5"
};
const _hoisted_11 = ["title", "onClick"];
const _sfc_main = /* @__PURE__ */ __mf_93({
  __name: "SlideshowApp",
  props: {
    projectId: {},
    folder: {},
    title: {}
  },
  setup(__props) {
    const props = __props;
    const show = __mf_45(null);
    const loading = __mf_45(true);
    const error = __mf_45(null);
    const currentIndex = __mf_45(0);
    const autoplaying = __mf_45(false);
    const fullscreen = __mf_45(false);
    const viewportEl = __mf_45(null);
    let autoplayTimer = null;
    const currentSlide = __mf_80(() => {
      if (!show.value || show.value.slides.length === 0) return null;
      return show.value.slides[currentIndex.value] ?? null;
    });
    const aspectRatio = __mf_80(() => {
      if (show.value?.aspectRatio) return show.value.aspectRatio.replace(":", "/");
      const slide = currentSlide.value;
      if (slide?.width && slide?.height) return `${slide.width} / ${slide.height}`;
      return "16 / 9";
    });
    const slideUrl = __mf_80(
      () => currentSlide.value ? __mf_30(currentSlide.value.documentId) : null
    );
    async function load() {
      loading.value = true;
      error.value = null;
      try {
        show.value = await getSlideshow(props.projectId, props.folder);
        if (currentIndex.value >= show.value.slides.length) {
          currentIndex.value = Math.max(0, show.value.slides.length - 1);
        }
      } catch (e) {
        error.value = `Could not load slideshow: ${e.message}`;
      } finally {
        loading.value = false;
      }
    }
    async function rebuild() {
      try {
        await rebuildSlideshow(props.projectId, props.folder);
        await load();
      } catch (e) {
        error.value = `Rebuild failed: ${e.message}`;
      }
    }
    function next() {
      if (!show.value || show.value.slides.length === 0) return;
      currentIndex.value = (currentIndex.value + 1) % show.value.slides.length;
    }
    function prev() {
      if (!show.value || show.value.slides.length === 0) return;
      currentIndex.value = (currentIndex.value - 1 + show.value.slides.length) % show.value.slides.length;
    }
    function jump(idx) {
      if (!show.value) return;
      currentIndex.value = Math.max(0, Math.min(idx, show.value.slides.length - 1));
    }
    function toggleAutoplay() {
      if (autoplaying.value) {
        stopAutoplay();
      } else {
        startAutoplay();
      }
    }
    function startAutoplay() {
      if (!show.value || show.value.autoplaySeconds <= 0) return;
      stopAutoplay();
      autoplaying.value = true;
      autoplayTimer = window.setInterval(() => next(), show.value.autoplaySeconds * 1e3);
    }
    function stopAutoplay() {
      if (autoplayTimer != null) {
        window.clearInterval(autoplayTimer);
        autoplayTimer = null;
      }
      autoplaying.value = false;
    }
    async function toggleFullscreen() {
      if (!document.fullscreenElement) {
        if (viewportEl.value) {
          await viewportEl.value.requestFullscreen?.();
        }
      } else {
        await document.exitFullscreen?.();
      }
    }
    function onFullscreenChange() {
      fullscreen.value = document.fullscreenElement != null;
    }
    function onKeydown(e) {
      const target = e.target;
      if (target && ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)) return;
      switch (e.key) {
        case "ArrowRight":
        case "PageDown":
        case " ":
          e.preventDefault();
          next();
          break;
        case "ArrowLeft":
        case "PageUp":
          e.preventDefault();
          prev();
          break;
        case "Home":
          e.preventDefault();
          jump(0);
          break;
        case "End":
          e.preventDefault();
          if (show.value) jump(show.value.slides.length - 1);
          break;
        case "f":
        case "F":
          e.preventDefault();
          void toggleFullscreen();
          break;
        case "p":
        case "P":
          e.preventDefault();
          toggleAutoplay();
          break;
      }
    }
    __mf_126(async () => {
      await load();
      window.addEventListener("keydown", onKeydown);
      document.addEventListener("fullscreenchange", onFullscreenChange);
    });
    __mf_122(() => {
      window.removeEventListener("keydown", onKeydown);
      document.removeEventListener("fullscreenchange", onFullscreenChange);
      stopAutoplay();
    });
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1, [
        __mf_84("div", _hoisted_2, [
          __mf_84("div", null, [
            __mf_84("h1", _hoisted_3, __mf_61(__props.title ?? __props.folder), 1),
            __mf_84("div", _hoisted_4, [
              __mf_90(__mf_61(__props.folder) + " ", 1),
              show.value && show.value.slides.length > 0 ? (__mf_132(), __mf_83(__mf_69, { key: 0 }, [
                __mf_90(" · Slide " + __mf_61(currentIndex.value + 1) + " / " + __mf_61(show.value.slides.length), 1)
              ], 64)) : __mf_82("", true)
            ])
          ]),
          __mf_84("div", _hoisted_5, [
            show.value && show.value.autoplaySeconds > 0 ? (__mf_132(), __mf_81(__mf_55(__mf_2), {
              key: 0,
              size: "sm",
              variant: autoplaying.value ? "primary" : "ghost",
              onClick: toggleAutoplay
            }, {
              default: __mf_166(() => [
                __mf_90(__mf_61(autoplaying.value ? "⏸ Pause" : `▶ Play (${show.value.autoplaySeconds}s)`), 1)
              ]),
              _: 1
            }, 8, ["variant"])) : __mf_82("", true),
            __mf_91(__mf_55(__mf_2), {
              size: "sm",
              variant: "ghost",
              onClick: toggleFullscreen
            }, {
              default: __mf_166(() => [
                __mf_90(__mf_61(fullscreen.value ? "Exit fullscreen" : "Fullscreen (F)"), 1)
              ]),
              _: 1
            }),
            __mf_91(__mf_55(__mf_2), {
              size: "sm",
              variant: "ghost",
              onClick: load
            }, {
              default: __mf_166(() => [..._cache[0] || (_cache[0] = [
                __mf_90("Reload", -1)
              ])]),
              _: 1
            }),
            __mf_91(__mf_55(__mf_2), {
              size: "sm",
              variant: "ghost",
              onClick: rebuild
            }, {
              default: __mf_166(() => [..._cache[1] || (_cache[1] = [
                __mf_90("Rebuild index", -1)
              ])]),
              _: 1
            })
          ])
        ]),
        error.value ? (__mf_132(), __mf_81(__mf_55(__mf_0), {
          key: 0,
          variant: "error",
          class: "m-4"
        }, {
          default: __mf_166(() => [
            __mf_90(__mf_61(error.value), 1)
          ]),
          _: 1
        })) : __mf_82("", true),
        loading.value ? (__mf_132(), __mf_83("div", _hoisted_6, "Loading slideshow…")) : show.value && show.value.slides.length === 0 ? (__mf_132(), __mf_81(__mf_55(__mf_8), {
          key: 2,
          class: "m-4",
          headline: "No slides",
          body: "Upload images into this folder, then click 'Rebuild index' to refresh the slideshow."
        })) : (__mf_132(), __mf_83("div", {
          key: 3,
          ref_key: "viewportEl",
          ref: viewportEl,
          class: "flex-1 flex flex-col items-center justify-center bg-neutral text-neutral-content p-6 gap-4 relative overflow-hidden",
          onClick: next
        }, [
          __mf_84("div", _hoisted_7, [
            slideUrl.value ? (__mf_132(), __mf_83("img", {
              key: 0,
              src: slideUrl.value,
              alt: currentSlide.value?.caption ?? "",
              style: __mf_60({ aspectRatio: aspectRatio.value }),
              class: "max-w-full max-h-full object-contain shadow-2xl",
              draggable: "false"
            }, null, 12, _hoisted_8)) : __mf_82("", true)
          ]),
          currentSlide.value?.caption ? (__mf_132(), __mf_83("div", _hoisted_9, __mf_61(currentSlide.value.caption), 1)) : __mf_82("", true),
          show.value && show.value.slides.length > 1 ? (__mf_132(), __mf_83("button", {
            key: 1,
            class: "absolute left-4 top-1/2 -translate-y-1/2 bg-base-100/20 hover:bg-base-100/40 text-base-content rounded-full w-12 h-12 flex items-center justify-center text-2xl",
            title: "Previous slide (←)",
            onClick: __mf_24(prev, ["stop"])
          }, "‹")) : __mf_82("", true),
          show.value && show.value.slides.length > 1 ? (__mf_132(), __mf_83("button", {
            key: 2,
            class: "absolute right-4 top-1/2 -translate-y-1/2 bg-base-100/20 hover:bg-base-100/40 text-base-content rounded-full w-12 h-12 flex items-center justify-center text-2xl",
            title: "Next slide (→)",
            onClick: __mf_24(next, ["stop"])
          }, "›")) : __mf_82("", true),
          show.value && show.value.slides.length > 1 && show.value.slides.length <= 30 ? (__mf_132(), __mf_83("div", _hoisted_10, [
            (__mf_132(true), __mf_83(__mf_69, null, __mf_138(show.value.slides, (_, idx) => {
              return __mf_132(), __mf_83("button", {
                key: idx,
                class: __mf_58(["w-2 h-2 rounded-full transition-all", idx === currentIndex.value ? "bg-primary w-6" : "bg-base-content/30 hover:bg-base-content/60"]),
                title: `Slide ${idx + 1}`,
                onClick: __mf_24(($event) => jump(idx), ["stop"])
              }, null, 10, _hoisted_11);
            }), 128))
          ])) : __mf_82("", true)
        ], 512))
      ]);
    };
  }
});

export { _sfc_main as default };
