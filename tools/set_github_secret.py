#!/usr/bin/env python3
"""设置/更新 GitHub 仓库 Secret（libsodium sealed box 加密）。

用法（PowerShell）：
  $env:SECRET_NAME="MINERU_API_KEY"; $env:SECRET_VALUE="sk-xxxx"; python tools/set_github_secret.py ausyeah/android-mistakebook

Token 从环境变量 GITHUB_TOKEN 读取（需 repo 权限），值与 Token 均不出现在命令行参数里。
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

from nacl.public import PublicKey, SealedBox


def api(method: str, url: str, token: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "secret-setter")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode()}", file=sys.stderr)
        raise SystemExit(1)


def main():
    if len(sys.argv) != 2 or "/" not in sys.argv[1]:
        print(__doc__)
        raise SystemExit(1)
    repo = sys.argv[1]
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    name = os.environ.get("SECRET_NAME")
    value = os.environ.get("SECRET_VALUE")
    if not token:
        print("缺少 GITHUB_TOKEN", file=sys.stderr)
        raise SystemExit(1)
    if not name or value is None:
        print("缺少 SECRET_NAME / SECRET_VALUE", file=sys.stderr)
        raise SystemExit(1)

    pk = api("GET", f"https://api.github.com/repos/{repo}/actions/secrets/public-key", token)
    sealed = SealedBox(PublicKey(base64.b64decode(pk["key"]))).encrypt(value.encode())
    api(
        "PUT",
        f"https://api.github.com/repos/{repo}/actions/secrets/{name}",
        token,
        {"encrypted_value": base64.b64encode(sealed).decode(), "key_id": pk["key_id"]},
    )
    print(f"OK: {repo} secret '{name}' updated")


if __name__ == "__main__":
    main()
