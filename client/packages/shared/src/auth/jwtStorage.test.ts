import { afterEach, describe, expect, it } from 'vitest';
import { getCurrentEditorId, setCurrentEditorId } from './jwtStorage';

describe('current editorId', () => {
  afterEach(() => setCurrentEditorId(null));

  it('lives on globalThis so every bundled copy of @vance/shared sees it', () => {
    setCurrentEditorId('ed-1');
    // An addon remote's copy has its own module scope — it only shares globalThis.
    expect(globalThis.__VANCE_EDITOR_ID__).toBe('ed-1');
    expect(getCurrentEditorId()).toBe('ed-1');
  });

  it('reads a value set by another copy and resets to null', () => {
    globalThis.__VANCE_EDITOR_ID__ = 'ed-host';
    expect(getCurrentEditorId()).toBe('ed-host');
    setCurrentEditorId(null);
    expect(getCurrentEditorId()).toBeNull();
  });
});
