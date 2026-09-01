import { api } from './browserApi';

/**
 * Getting hold of what the tab is actually showing.
 *
 * <p>This is the part that justifies an extension existing at all. A grab is
 * worth having for pages the server cannot fetch — behind a login, behind a
 * paywall, on an intranet, or assembled by JavaScript. For anything else the
 * brain's own `web_fetch` already works and an agent already has it. So we send
 * what this browser rendered, with this browser's session.
 */

export interface PageContent {
  /** What the tab is: `text/html`, `application/pdf`, `image/png`, … */
  contentType: string;
  /** The bytes to send. For HTML this is the rendered DOM, not the source. */
  blob: Blob;
}

/** What the injected probe reports back. Must stay serialisable. */
interface Probe {
  contentType: string;
  html: string;
}

/**
 * Runs *in the page*, not in the extension. Deliberately tiny and read-only:
 * it takes a snapshot and returns, so the page never sees a script that does
 * anything to it.
 *
 * `document.contentType` is the discriminator. Guessing from the URL would get
 * `…/download?id=17` wrong in both directions, and Chrome's PDF viewer reports
 * `application/pdf` here even though the tab holds an HTML wrapper around an
 * `<embed>`.
 */
function probe(): Probe {
  return {
    contentType: document.contentType,
    html: document.documentElement.outerHTML,
  };
}

function isHtml(contentType: string): boolean {
  return contentType.startsWith('text/html') || contentType.startsWith('application/xhtml');
}

/**
 * Read the tab.
 *
 * <p>Two paths, and the fallback matters as much as the main one:
 *
 * <ul>
 *   <li><b>HTML</b> — the rendered DOM, taken in the page.</li>
 *   <li><b>Anything else</b> — fetched by the extension. `credentials:
 *       'include'` because a PDF behind a login is the case this exists for;
 *       without it we would faithfully save the login page.</li>
 * </ul>
 *
 * <p>The probe is allowed to fail — some tabs refuse injection outright — and
 * then the fetch path takes over and asks the server what the thing is. That
 * covers PDFs on the browsers where the viewer is not scriptable.
 */
export async function readTab(tabId: number, url: string): Promise<PageContent> {
  let snapshot: Probe | null = null;
  try {
    const [result] = await api.scripting.executeScript({ target: { tabId }, func: probe });
    snapshot = (result?.result as Probe | undefined) ?? null;
  } catch {
    // Injection refused. Not an error worth surfacing — the fetch below is a
    // complete answer for everything that is not a script-rendered page.
    snapshot = null;
  }

  if (snapshot && isHtml(snapshot.contentType)) {
    return {
      contentType: 'text/html',
      blob: new Blob([snapshot.html], { type: 'text/html' }),
    };
  }

  const response = await fetch(url, { credentials: 'include' });
  if (!response.ok) {
    throw new Error(`The page answered ${response.status} when fetching it.`);
  }
  const fetched = await response.blob();
  // Re-blob under the resolved type. The multipart part's Content-Type comes
  // from the Blob, not from anything else we say — a mismatch here is how the
  // server ends up deciding "HTML" for a PDF, or nothing at all.
  const contentType = snapshot?.contentType || fetched.type || 'application/octet-stream';
  return { contentType, blob: new Blob([fetched], { type: contentType }) };
}
