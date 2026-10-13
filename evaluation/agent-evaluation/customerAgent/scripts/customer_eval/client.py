"""Bounded HTTP requests; no redirects, implicit retries, or credential logging."""
from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def valid_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("URL must be http(s), without embedded credentials, query or fragment")
    return url.rstrip("/")


class HttpClient:
    def __init__(self, base_url: str, timeout: float = 100):
        self.base_url = valid_url(base_url)
        self.timeout = timeout

    def request(self, method: str, path: str, token: str = "", body=None, headers=None) -> dict:
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("API path must be relative to the configured server")
        return self.request_url(method, self.base_url + path, token, body, headers)

    def request_url(self, method: str, url: str, token: str = "", body=None, headers=None) -> dict:
        valid_url(url)
        request_headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if token:
            request_headers["Authorization"] = token
        for key, value in (headers or {}).items():
            if key not in {"X-User-Id", "X-User-Role"}:
                raise ValueError("only identity test headers may be overridden")
            request_headers[key] = str(value)
        payload = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
        request = urllib.request.Request(url, data=payload, method=method, headers=request_headers)
        started = time.monotonic()
        result = {"http_status": None, "body": None, "error": None}
        try:
            try:
                response = urllib.request.build_opener(NoRedirect()).open(request, timeout=self.timeout)
            except urllib.error.HTTPError as exc:
                response = exc
            with response:
                result["http_status"] = response.code
                data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                result["error"] = "RESPONSE_TOO_LARGE"
            else:
                try:
                    result["body"] = json.loads(data)
                except (ValueError, UnicodeDecodeError):
                    result["error"] = "INVALID_JSON"
        except (OSError, urllib.error.URLError, TimeoutError):
            # Do not persist exception messages: proxies/providers may echo credentials.
            result["error"] = "TRANSPORT_ERROR"
        result["elapsed_ms"] = round((time.monotonic() - started) * 1000, 3)
        return result


def data(response: dict):
    body = response.get("body")
    if response.get("error") or response.get("http_status") != 200 or not isinstance(body, dict) or body.get("code") != 200:
        raise ValueError("API request did not return a successful envelope")
    return body.get("data")


class Pacer:
    """Per-identity scheduling; 15.1s spacing stays below five runs/rolling minute."""
    def __init__(self, interval: float):
        self.interval = interval
        self.next_time = defaultdict(float)
        self.lock = threading.Lock()

    def wait(self, identity: str):
        with self.lock:
            now = time.monotonic()
            due = max(now, self.next_time[identity])
            self.next_time[identity] = due + self.interval
        delay = due - now
        if delay > 0:
            time.sleep(delay)
