import { a as __mf_93, b as __mf_80, c as __mf_132, d as __mf_83, e as __mf_58, f as __mf_139, g as __mf_82, h as __mf_84, i as __mf_61, j as __mf_45, k as __mf_126, l as __mf_122, m as __mf_90, n as __mf_69, o as __mf_81, p as __mf_55, q as __mf_166, r as __mf_91, s as __mf_60, t as __mf_24, u as __mf_138 } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare__vue__loadShare__.mjs-CtZISMkN.js';
import './SettingType-DY2mUbjQ.js';

const _sfc_main$3 = /* @__PURE__ */ __mf_93({
  __name: "VAlert",
  props: {
    variant: { default: "info" }
  },
  setup(__props) {
    const props = __props;
    const variantClass = __mf_80(() => {
      switch (props.variant) {
        case "info":
          return "alert-info";
        case "warning":
          return "alert-warning";
        case "error":
          return "alert-error";
        case "success":
          return "alert-success";
      }
    });
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", {
        role: "alert",
        class: __mf_58(["alert", variantClass.value])
      }, [
        __mf_139(_ctx.$slots, "default")
      ], 2);
    };
  }
});

const _hoisted_1$2 = ["href"];
const _hoisted_2$2 = {
  key: 0,
  class: "loading loading-spinner loading-sm"
};
const _hoisted_3$2 = ["type", "disabled"];
const _hoisted_4$2 = {
  key: 0,
  class: "loading loading-spinner loading-sm"
};
const _sfc_main$2 = /* @__PURE__ */ __mf_93({
  __name: "VButton",
  props: {
    variant: { default: "primary" },
    href: {},
    type: { default: "button" },
    loading: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    block: { type: Boolean, default: false },
    size: { default: "md" }
  },
  emits: ["click"],
  setup(__props) {
    const props = __props;
    const variantClass = __mf_80(() => {
      switch (props.variant) {
        case "primary":
          return "btn-primary";
        case "secondary":
          return "btn-secondary";
        case "ghost":
          return "btn-ghost";
        case "danger":
          return "btn-error";
        case "link":
          return "btn-link";
      }
    });
    const sizeClass = __mf_80(() => props.size === "sm" ? "btn-sm" : "");
    return (_ctx, _cache) => {
      return __props.href ? (__mf_132(), __mf_83("a", {
        key: 0,
        href: __props.href,
        class: __mf_58(["btn", variantClass.value, sizeClass.value, { "btn-block": __props.block, "btn-disabled": __props.disabled }]),
        onClick: _cache[0] || (_cache[0] = (e) => _ctx.$emit("click", e))
      }, [
        __props.loading ? (__mf_132(), __mf_83("span", _hoisted_2$2)) : __mf_82("", true),
        __mf_139(_ctx.$slots, "default")
      ], 10, _hoisted_1$2)) : (__mf_132(), __mf_83("button", {
        key: 1,
        type: __props.type,
        disabled: __props.disabled || __props.loading,
        class: __mf_58(["btn", variantClass.value, sizeClass.value, { "btn-block": __props.block }]),
        onClick: _cache[1] || (_cache[1] = (e) => _ctx.$emit("click", e))
      }, [
        __props.loading ? (__mf_132(), __mf_83("span", _hoisted_4$2)) : __mf_82("", true),
        __mf_139(_ctx.$slots, "default")
      ], 10, _hoisted_3$2));
    };
  }
});

const _hoisted_1$1 = { class: "flex flex-col items-center justify-center text-center py-12 gap-3" };
const _hoisted_2$1 = {
  key: 0,
  class: "text-4xl opacity-60"
};
const _hoisted_3$1 = { class: "text-lg font-semibold" };
const _hoisted_4$1 = {
  key: 1,
  class: "text-sm opacity-70 max-w-md"
};
const _hoisted_5$1 = {
  key: 2,
  class: "mt-2"
};
const _sfc_main$1 = /* @__PURE__ */ __mf_93({
  __name: "VEmptyState",
  props: {
    headline: {},
    body: {}
  },
  setup(__props) {
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$1, [
        _ctx.$slots.icon ? (__mf_132(), __mf_83("div", _hoisted_2$1, [
          __mf_139(_ctx.$slots, "icon")
        ])) : __mf_82("", true),
        __mf_84("h3", _hoisted_3$1, __mf_61(__props.headline), 1),
        __props.body ? (__mf_132(), __mf_83("p", _hoisted_4$1, __mf_61(__props.body), 1)) : __mf_82("", true),
        _ctx.$slots.action ? (__mf_132(), __mf_83("div", _hoisted_5$1, [
          __mf_139(_ctx.$slots, "action")
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const GLOBAL_KEY = '__VANCE_PLATFORM__';
function readBindings() {
    return globalThis[GLOBAL_KEY] ?? null;
}
function require_() {
    const bindings = readBindings();
    if (bindings === null) {
        throw new Error('@vance/shared: platform not configured — call configurePlatform({ storage, rest }) at app startup.');
    }
    return bindings;
}
/**
 * Resolve the host-provided storage bindings. Throws if
 * {@link configurePlatform} has not been called yet — there is no
 * sensible default, since the choice between `localStorage` and
 * `AsyncStorage` (and their secure counterparts) is the host's
 * responsibility.
 */
function getStorage() {
    return require_().storage;
}
/**
 * Resolve the host-provided REST configuration. Throws if
 * {@link configurePlatform} has not been called yet.
 */
function getRestConfig() {
    return require_().rest;
}

/**
 * Canonical key strings for the platform's {@link KeyValueStore}
 * bindings. All keys are prefixed `vance.` to avoid collisions with
 * other apps that share the storage namespace (Web `localStorage`,
 * Mobile `AsyncStorage`).
 *
 * Each key documents which store it belongs to:
 * - `secureStore`: tokens and other sensitive material
 * - `prefsStore`: UI preferences, identity hints, draft state
 *
 * Web collapses both stores onto `localStorage`, so the distinction
 * is non-load-bearing there; Mobile honours it (Keychain vs.
 * AsyncStorage).
 *
 * This module replaces the legacy `persistence/keys.ts`, which is
 * kept for backwards compatibility until Phase 4 of the
 * platform-neutrality refactor (see
 * `readme/reorg-webui-to-clean-shared.md`).
 */
const StorageKeys = {
    // ── secureStore ─────────────────────────────────────────────────
    /** Access JWT — Bearer-mode REST/WS authentication. Mobile only.
     *  Web cookie-mode never sees the token. */
    authAccessToken: 'vance.auth.accessToken',
    // ── prefsStore ──────────────────────────────────────────────────
    /** Tenant the user belongs to. Set after successful login on both
     *  Web (mirrored from the `vance_data` cookie) and Mobile (read
     *  from the login response body). */
    identityTenantId: 'vance.identity.tenantId'};

// Identity helpers read from the platform-bound prefsStore. The host
// is responsible for keeping these keys in sync with the authoritative
// source: Web copies them from the `vance_data` cookie at boot,
// Mobile writes them after a successful body-mode login.
//
// JavaScript never sees the access JWT on Web (HttpOnly cookie); on
// Mobile the bearer token lives in the platform's `secureStore`
// (a different store under the same {@link PlatformStorage} binding).
function getTenantId() {
    return getStorage().prefsStore.get(StorageKeys.identityTenantId);
}

class RestError extends Error {
    status;
    path;
    constructor(status, path, message) {
        super(message);
        this.status = status;
        this.path = path;
        this.name = 'RestError';
    }
}
/**
 * Resolve the Brain's base URL from the host-bound configuration.
 * The host calls {@link configurePlatform} once at boot with the
 * appropriate value (`''` for same-origin Web, an explicit origin
 * for Mobile or cross-origin dev). This module never inspects the
 * environment directly.
 */
function brainBaseUrl() {
    return getRestConfig().baseUrl;
}
/**
 * Tenant-scoped REST request. The `path` is appended to
 * `${baseUrl}/brain/{tenant}/`, so callers pass relative paths like
 * `'sessions'` or `'documents/abc'`.
 *
 * On `401` the helper attempts a single silent re-mint and retries
 * the original request once. If the retry also fails (or no refresh
 * is possible), it triggers the host's `onUnauthorized` callback.
 */
async function brainFetch(method, path, options = {}) {
    const tenant = getTenantId();
    if (!tenant)
        throw new RestError(0, path, 'No tenant configured — user is not logged in.');
    const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
    const response = await doFetch(url, method, options);
    if (response.status === 401 && options.authenticated !== false) {
        const refreshed = await getRestConfig().refreshAccess();
        if (refreshed) {
            const retry = await doFetch(url, method, options);
            if (retry.ok)
                return parseJson(retry);
        }
        redirectToLogin();
        return new Promise(() => { });
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new RestError(response.status, path, text || response.statusText);
    }
    return parseJson(response);
}
async function doFetch(url, method, options) {
    const config = getRestConfig();
    const headers = { ...(options.headers ?? {}) };
    // FormData carries its own multipart boundary — let the host set
    // Content-Type so the boundary is correct, and never JSON-stringify it.
    const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
    if (options.body !== undefined && !isFormData) {
        headers['Content-Type'] = 'application/json';
    }
    if (config.authMode === 'bearer' && options.authenticated !== false) {
        const token = getStorage().secureStore.get(StorageKeys.authAccessToken);
        if (token !== null)
            headers['Authorization'] = `Bearer ${token}`;
    }
    let body;
    if (options.body !== undefined) {
        body = isFormData ? options.body : JSON.stringify(options.body);
    }
    return fetch(url, {
        method,
        headers,
        body,
        credentials: config.authMode === 'cookie' && options.authenticated !== false ? 'include' : 'omit',
    });
}
async function parseJson(response) {
    if (response.status === 204)
        return undefined;
    const contentType = response.headers.get('Content-Type') ?? '';
    if (!contentType.includes('application/json'))
        return undefined;
    return (await response.json());
}
function redirectToLogin() {
    getRestConfig().onUnauthorized();
}
/**
 * Build a tenant-scoped URL for a document's streaming-content
 * endpoint. Used by `<img src>` / PDF.js viewers / `<a href download>`
 * — places where we cannot inject an `Authorization` header.
 *
 * On Web (cookie auth) the same-origin `<img>` load carries the
 * `vance_access` cookie automatically. On Mobile (bearer auth) the
 * caller must replace this with an authorised fetch + blob — `<img>`
 * cannot send custom headers.
 */
function documentContentUrl(documentId, download = false) {
    const tenant = getTenantId();
    if (!tenant)
        return '';
    const params = new URLSearchParams();
    if (download)
        params.set('download', '1');
    const query = params.toString();
    return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/documents/${encodeURIComponent(documentId)}/content${query ? '?' + query : ''}`;
}

function qs(params) {
    const u = new URLSearchParams();
    for (const [k, v] of Object.entries(params))
        u.set(k, v);
    return u.toString();
}
async function getSlideshow(projectId, folder) {
    return brainFetch('GET', `slideshow/show?${qs({ projectId, folder })}`);
}
async function rebuildSlideshow(projectId, folder) {
    return brainFetch('POST', `slideshow/rebuild?${qs({ projectId, folder })}`);
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
      () => currentSlide.value ? documentContentUrl(currentSlide.value.documentId) : null
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
            show.value && show.value.autoplaySeconds > 0 ? (__mf_132(), __mf_81(__mf_55(_sfc_main$2), {
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
            __mf_91(__mf_55(_sfc_main$2), {
              size: "sm",
              variant: "ghost",
              onClick: toggleFullscreen
            }, {
              default: __mf_166(() => [
                __mf_90(__mf_61(fullscreen.value ? "Exit fullscreen" : "Fullscreen (F)"), 1)
              ]),
              _: 1
            }),
            __mf_91(__mf_55(_sfc_main$2), {
              size: "sm",
              variant: "ghost",
              onClick: load
            }, {
              default: __mf_166(() => [..._cache[0] || (_cache[0] = [
                __mf_90("Reload", -1)
              ])]),
              _: 1
            }),
            __mf_91(__mf_55(_sfc_main$2), {
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
        error.value ? (__mf_132(), __mf_81(__mf_55(_sfc_main$3), {
          key: 0,
          variant: "error",
          class: "m-4"
        }, {
          default: __mf_166(() => [
            __mf_90(__mf_61(error.value), 1)
          ]),
          _: 1
        })) : __mf_82("", true),
        loading.value ? (__mf_132(), __mf_83("div", _hoisted_6, "Loading slideshow…")) : show.value && show.value.slides.length === 0 ? (__mf_132(), __mf_81(__mf_55(_sfc_main$1), {
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
