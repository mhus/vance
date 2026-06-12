"""Vance script-run helper.

Auto-installed into the Python workspace by ``PythonHelperBundler``
before the script executes. Reads connection details from environment
variables set by the brain at spawn time and exposes a minimal
:py:mod:`urllib`-based client — no third-party dependencies.

Environment contract:

==========================  ==================================================
``VANCE_BRAIN_URL``         Tenant-scoped base, e.g.
                            ``http://localhost:8080/brain/acme``.
``VANCE_TENANT``            Tenant id.
``VANCE_PROJECT``           Project id the script is scoped to.
``VANCE_SESSION``           Session id, when the spawn carried one.
``VANCE_RUN_ID``            Execution id minted at spawn time; the brain
                            ties token validity to its registry status.
``VANCE_TOKEN``             Bearer JWT with type ``SCRIPT_RUN``.
==========================  ==================================================

Usage::

    import vance

    text = vance.documents.read("notes/seed.md")
    vance.documents.write("output/report.md", "# Hello")
    for entry in vance.documents.list(prefix="data/"):
        ...

The helper raises :py:class:`VanceError` on transport or auth failures
so callers can ``try/except`` cleanly.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Iterable, Optional


class VanceError(RuntimeError):
    """Raised on transport, auth or contract failures against the brain."""


def _env(name: str, *, required: bool = True) -> Optional[str]:
    value = os.environ.get(name)
    if required and (value is None or value == ""):
        raise VanceError(
            f"Environment variable {name!r} is not set — script must be "
            f"spawned by the brain to use vance.py."
        )
    return value


def _base_url() -> str:
    return _env("VANCE_BRAIN_URL").rstrip("/")


def _project() -> str:
    return _env("VANCE_PROJECT")


def _token() -> str:
    return _env("VANCE_TOKEN")


def _request(
    method: str,
    path: str,
    *,
    params: Optional[dict] = None,
    body: Any = None,
    raw_body: Optional[bytes] = None,
    content_type: Optional[str] = None,
) -> tuple[int, bytes, dict]:
    url = _base_url() + path
    if params:
        query = urllib.parse.urlencode(
            {k: v for k, v in params.items() if v is not None}
        )
        url = f"{url}?{query}" if query else url

    headers = {"Authorization": "Bearer " + _token()}
    payload: Optional[bytes] = None
    if raw_body is not None:
        payload = raw_body
        if content_type:
            headers["Content-Type"] = content_type
    elif body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.getcode(), resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise VanceError(
            f"{method} {path} → HTTP {exc.code}: {detail[:200]}"
        ) from exc
    except urllib.error.URLError as exc:
        raise VanceError(f"{method} {path} failed: {exc.reason}") from exc


def _json_request(method: str, path: str, **kwargs) -> Any:
    status, body, _ = _request(method, path, **kwargs)
    if not body:
        return None
    return json.loads(body.decode("utf-8"))


# ─── Documents ────────────────────────────────────────────────────────────────


class _Documents:
    """Wraps the ``/brain/{tenant}/documents`` endpoints."""

    def read(self, path: str) -> str:
        """Return the document's content as UTF-8 text."""
        doc = self._find_or_raise(path)
        status, body, _ = _request("GET", f"/documents/{doc['id']}/content")
        return body.decode("utf-8")

    def exists(self, path: str) -> bool:
        try:
            self._find_or_raise(path)
            return True
        except VanceError:
            return False

    def list(self, prefix: Optional[str] = None) -> list[dict]:
        """List active documents in the project, optionally narrowed by
        path prefix. Returns the page content from
        ``GET /documents?projectId=...&pathPrefix=...``."""
        result = _json_request(
            "GET",
            "/documents",
            params={"projectId": _project(), "pathPrefix": prefix},
        )
        if isinstance(result, dict) and "content" in result:
            return list(result["content"])
        if isinstance(result, list):
            return result
        return []

    def write(self, path: str, content: str) -> dict:
        """Upsert: PUT-content when the doc already exists, otherwise
        POST-create. Returns the resulting document descriptor."""
        existing = self._find(path)
        if existing is not None:
            _request(
                "PUT",
                f"/documents/{existing['id']}/content",
                raw_body=content.encode("utf-8"),
                content_type="text/plain; charset=UTF-8",
            )
            return existing
        return _json_request(
            "POST",
            "/documents",
            params={"projectId": _project()},
            body={"path": path, "inlineText": content},
        )

    def delete(self, path: str) -> bool:
        existing = self._find(path)
        if existing is None:
            return False
        _request("DELETE", f"/documents/{existing['id']}")
        return True

    def meta(self, path: str) -> dict:
        return self._find_or_raise(path)

    # ── internal ────────────────────────────────────────────────────

    def _find(self, path: str) -> Optional[dict]:
        try:
            return _json_request(
                "GET",
                "/documents/by-path",
                params={"projectId": _project(), "path": path},
            )
        except VanceError as exc:
            if "HTTP 404" in str(exc):
                return None
            raise

    def _find_or_raise(self, path: str) -> dict:
        doc = self._find(path)
        if doc is None:
            raise VanceError(f"Document not found: {path!r}")
        return doc


documents = _Documents()


def _scope() -> dict:
    """Diagnostics helper — read-only snapshot of the script's scope."""
    return {
        "brainUrl": _env("VANCE_BRAIN_URL", required=False),
        "tenant": _env("VANCE_TENANT", required=False),
        "project": _env("VANCE_PROJECT", required=False),
        "session": _env("VANCE_SESSION", required=False),
        "runId": _env("VANCE_RUN_ID", required=False),
        "hasToken": bool(_env("VANCE_TOKEN", required=False)),
    }


scope = _scope


__all__ = ["VanceError", "documents", "scope"]
