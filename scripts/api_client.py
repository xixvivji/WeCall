"""Session + CSRF client shared by local demos; credentials come from environment."""
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request


class ApiClient:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        password = os.environ.get("WECALL_PASSWORD")
        if not password:
            raise SystemExit("WECALL_USERNAME과 WECALL_PASSWORD를 설정하세요. docs/design/auth-api.md 참고")
        self.csrf = self.request("/api/auth/csrf")
        form = urllib.parse.urlencode({"username": os.environ.get("WECALL_USERNAME", "demo-reviewer"), "password": password}).encode()
        self.request("/api/auth/login", form, "application/x-www-form-urlencoded", expected=200)
        self.csrf = self.request("/api/auth/csrf")

    def request(self, path, body=None, content_type="application/json", expected=None):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode()
        headers = {"Content-Type": content_type}
        if body is not None and self.csrf:
            headers[self.csrf["headerName"]] = self.csrf["token"]
        req = urllib.request.Request(self.base + path, data=body, headers=headers)
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            result = json.loads(raw) if raw else None
            if (expected is not None and response.code != expected) or (expected is None and response.code >= 400):
                raise SystemExit(f"HTTP {response.code}: {result}")
            return result

    def logout(self):
        self.request("/api/auth/logout", b"", expected=204)
