/**
 * The interactive {@code ASK} surface of the desktop permission gate —
 * the Electron analog of foot's REPL prompt (foot-sandbox.md §7).
 *
 * Shows a **native** {@code dialog.showMessageBox} owned by the main
 * process — never an HTML overlay: the account WebView runs remote
 * content, so a forged dialog must not be able to grant permissions.
 *
 * Rules mirrored from foot: one prompt at a time (mutex), 25 s timeout
 * → DENY (comfortably under the brain's ~30 s tool timeout), no
 * drawable surface → immediate DENY. "Always" answers persist through
 * the policy.
 */
import { BrowserWindow, dialog } from 'electron';

import type { AskSubject } from './permissionPolicy';
import type { PermissionPolicy } from './permissionPolicy';

/** foot-sandbox.md §7: 25 s, knapp unter dem ~30 s-Tool-Timeout des Brain. */
const ASK_TIMEOUT_MS = 25_000;

export type AskAnswer = 'allow-once' | 'allow-always' | 'deny-once' | 'deny-always';

/** Serialized native ask. Second caller while a prompt is up → DENY. */
export class PermissionGate {
  private prompting = false;

  constructor(private readonly windowProvider: () => BrowserWindow | null) {}

  /**
   * Resolve an {@code ASK} verdict interactively. Returns {@code true}
   * when the user allowed the call; persists and reloads "always"
   * answers into the given policy.
   */
  async resolve(
    subject: AskSubject,
    accountLabel: string,
    policy: PermissionPolicy,
  ): Promise<boolean> {
    if (this.prompting) return false;
    this.prompting = true;
    try {
      const win = this.windowProvider();
      if (!win || win.isDestroyed()) return false;
      const answer = await withTimeout(
        showAskDialog(win, subject, accountLabel),
        ASK_TIMEOUT_MS,
        'deny-once' as AskAnswer,
      );
      if (answer === 'allow-always' || answer === 'deny-always') {
        await policy.persistAlways(subject, answer === 'allow-always');
      }
      return answer === 'allow-once' || answer === 'allow-always';
    } finally {
      this.prompting = false;
    }
  }
}

/** Native dialog in English — runtime strings are English by convention;
 *  the output language is a runtime setting, not the prompt author's. */
async function showAskDialog(
  win: BrowserWindow,
  subject: AskSubject,
  accountLabel: string,
): Promise<AskAnswer> {
  const detail =
    subject.domain === 'commands'
      ? `command: ${subject.subject}`
      : `path: ${subject.subject}`;
  const buttons = ['Allow once', 'Allow always', 'Deny once', 'Deny always'];
  const { response } = await dialog.showMessageBox(win, {
    type: 'question',
    title: 'Permission required',
    message: `🔒 ${subject.toolName}`,
    detail: `${detail}\naccount: ${accountLabel}`,
    buttons,
    defaultId: 0,
    cancelId: 3,
    noLink: true,
  });
  const answers: AskAnswer[] = ['allow-once', 'allow-always', 'deny-once', 'deny-always'];
  return answers[response] ?? 'deny-once';
}

/** Race the dialog against the timeout — a silent prompt must never
 *  stall the brain's tool future (it DENYs at ~30 s anyway, but our own
 *  25 s keeps the message under our control). */
function withTimeout(
  promise: Promise<AskAnswer>,
  ms: number,
  fallback: AskAnswer,
): Promise<AskAnswer> {
  return new Promise<AskAnswer>((resolve) => {
    const timer = setTimeout(() => resolve(fallback), ms);
    promise
      .then((answer) => {
        clearTimeout(timer);
        resolve(answer);
      })
      .catch(() => {
        clearTimeout(timer);
        resolve(fallback);
      });
  });
}
