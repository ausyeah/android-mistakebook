#!/usr/bin/env python3
"""把发布签名材料写入 GitHub 仓库 Secrets（libsodium sealed box 加密）。

用法（PowerShell）：
  $env:JKS_PATH="...\\mistakebook-release.jks"
  $env:JKS_PASSWORD="..."      # 也可不给，则从 password.txt 读
  $env:REPO="ausyeah/android-mistakebook"
  python tools/set_signing_secrets.py

Token 从环境变量 GITHUB_TOKEN 读取，值不出现在命令行参数里。
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

from nacl.public import PublicKey, SealedBox

REPO = os.environ.get("REPO", "ausyeah/android-mistakebook")


def api(method: str, url: str, token: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "signing-setter")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise SystemExit(f"HTTP {e.code} {e.reason}: {detail}") from None


def put_secret(token: str, name: str, plaintext: str) -> None:
    pub = api("GET", f"https://api.github.com/repos/{REPO}/actions/secrets/public-key", token)
    # GitHub 返回的公钥是 base64 编码的，PublicKey 要的是 32 字节裸密钥
    key = PublicKey(base64.b64decode(pub["key"]))
    sealed = SealedBox(key).encrypt(plaintext.encode())
    body = {
        "encrypted_value": base64.b64encode(sealed).decode(),
        "key_id": pub["key_id"],
    }
    api("PUT", f"https://api.github.com/repos/{REPO}/actions/secrets/{name}", token, body)
    # 只回显长度，不回显内容
    print(f"  {name}: 已写入（{len(plaintext)} 字符）")


def main() -> None:
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise SystemExit("缺少 GITHUB_TOKEN 环境变量")

    jks_path = os.environ.get("JKS_PATH")
    password = os.environ.get("JKS_PASSWORD")
    alias = os.environ.get("KEY_ALIAS", "mistakebook")

    if not jks_path:
        raise SystemExit("缺少 JKS_PATH")
    with open(jks_path, "rb") as handle:
        jks_bytes = handle.read()

    if not password:
        raise SystemExit("缺少 JKS_PASSWORD")

    jks_b64 = base64.b64encode(jks_bytes).decode()

    print(f"写入仓库 {REPO} 的签名 Secrets：")
    put_secret(token, "RELEASE_KEYSTORE_B64", jks_b64)
    put_secret(token, "RELEASE_STORE_PASSWORD", password)
    put_secret(token, "RELEASE_KEY_ALIAS", alias)
    put_secret(token, "RELEASE_KEY_PASSWORD", password)
    print("完成。口令与密钥内容均未回显。")


if __name__ == "__main__":
    main()
