import { describe, expect, it } from 'vitest';
import {
  RestDeniedError,
  canonicalRestPath,
  checkRestAllowed,
  checkRestDeclared,
  vetRestMethod,
  vetRestPath,
} from './restPolicy';

describe('canonicalRestPath', () => {

  it('keeps an ordinary path and its query apart', () => {
    expect(canonicalRestPath('documents/folder?projectId=p&path=/x'))
      .toEqual({ path: 'documents/folder', query: 'projectId=p&path=/x' });
  });

  it('drops a leading slash, empty segments and dots', () => {
    expect(canonicalRestPath('/.//documents//folder/').path).toBe('documents/folder');
  });

  it('refuses another host rather than lending it this session', () => {
    for (const bad of ['https://evil/x', 'HTTP://evil/x', '//evil/x', 'javascript:alert(1)']) {
      expect(() => canonicalRestPath(bad)).toThrow(RestDeniedError);
    }
  });

  it('refuses climbing above the tenant instead of clamping', () => {
    // Clamping would send the call somewhere the author did not name.
    expect(() => canonicalRestPath('../other-tenant/documents')).toThrow(/above the tenant root/);
  });

  it('refuses an empty path', () => {
    for (const bad of ['', '   ', '/', './/.', null, 42]) {
      expect(() => canonicalRestPath(bad)).toThrow(RestDeniedError);
    }
  });
});

describe('checkRestAllowed', () => {

  it('closes the floor', () => {
    for (const denied of ['admin', 'share', 'mcp', 'oauth', 'access', 'refresh',
                          'logout', 'compose', 'python', 'script', 'scripts']) {
      expect(() => checkRestAllowed(denied)).toThrow(RestDeniedError);
      expect(() => checkRestAllowed(`${denied}/anything/deeper`)).toThrow(RestDeniedError);
    }
  });

  it('names the reason, not just the refusal', () => {
    expect(() => checkRestAllowed('admin/permission-grants'))
      .toThrow(/administration — grants, catalogues, session exports/);
    expect(() => checkRestAllowed('compose/run')).toThrow(/runs code on the server/);
  });

  it('leaves everything else to the permission system', () => {
    for (const ok of ['documents/folder', 'inbox', 'sessions/x', 'settings', 'me',
                      'templates', 'runs', 'addon/bistromath/scan', 'follow-up/p']) {
      expect(() => checkRestAllowed(ok)).not.toThrow();
    }
  });

  it('matches a whole segment, so a longer name is not caught by accident', () => {
    // `share` must not close `shared`, `admin` must not close `administration`.
    expect(() => checkRestAllowed('shared/thing')).not.toThrow();
    expect(() => checkRestAllowed('administration')).not.toThrow();
    expect(() => checkRestAllowed('scripting/x')).not.toThrow();
  });
});

describe('vetRestPath', () => {

  it('canonicalises before matching — the bypass this file exists to close', () => {
    for (const sneaky of [
      'foo/../admin/permission-grants',
      './/admin//x',
      'a/b/../../compose/run',
      '/admin/x',
    ]) {
      expect(() => vetRestPath(sneaky)).toThrow(RestDeniedError);
    }
  });

  it('is not fooled by case', () => {
    expect(() => vetRestPath('AdMiN/x')).toThrow(RestDeniedError);
  });

  it('hands back the canonical path with its query', () => {
    expect(vetRestPath('/documents/folder?projectId=p')).toBe('documents/folder?projectId=p');
    expect(vetRestPath('inbox')).toBe('inbox');
  });

  it('does not let a query smuggle a denied path in', () => {
    // The query is opaque to the check, so a denied name inside it is harmless —
    // but it must also not make an allowed path look denied.
    expect(vetRestPath('documents/folder?path=/admin/x')).toBe('documents/folder?path=/admin/x');
  });
});

describe('vetRestMethod', () => {

  it('defaults to GET and accepts the five', () => {
    expect(vetRestMethod(undefined)).toBe('GET');
    expect(vetRestMethod('post')).toBe('POST');
  });

  it('names a typo instead of sending it', () => {
    expect(() => vetRestMethod('GTE')).toThrow(/not an HTTP method/);
  });
});

describe('checkRestDeclared', () => {

  it('treats "not declared" as unrestricted, so an older app still runs', () => {
    for (const nothing of [null, undefined]) {
      expect(() => checkRestDeclared('documents/folder', nothing)).not.toThrow();
    }
  });

  it('treats an empty list as a declaration, not as silence', () => {
    // The distinction the nullable field exists for: `rest: []` says "no routes".
    expect(() => checkRestDeclared('documents/folder', [])).toThrow(/asks for no routes/);
  });

  it('allows a declared family and refuses an undeclared one by name', () => {
    expect(() => checkRestDeclared('documents/folder', ['documents', 'inbox'])).not.toThrow();
    expect(() => checkRestDeclared('sessions/x', ['documents', 'inbox']))
      .toThrow(/does not include 'sessions'/);
  });

  it('ignores case and padding in the declaration', () => {
    expect(() => checkRestDeclared('inbox', [' Inbox '])).not.toThrow();
  });
});

describe('vetRestPath with a declaration', () => {

  it('checks the floor first, so the message does not blame the manifest', () => {
    // `admin` is declared, and still refused — adding a line to the manifest
    // cannot re-open the floor, so the reason must not suggest it could.
    expect(() => vetRestPath('admin/x', ['admin'])).toThrow(/closed to apps/);
  });

  it('canonicalises before the declaration check too', () => {
    expect(() => vetRestPath('foo/../sessions/x', ['documents'])).toThrow(/'sessions'/);
    expect(vetRestPath('./documents//folder', ['documents'])).toBe('documents/folder');
  });
});
