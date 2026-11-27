#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Разбор JWT без проверки подписи: alg, exp, iat, sub, aud, iss, scopes.

Нужен, чтобы понять, годится ли токен для StarCommand(160):
  * alg должен быть RS256;
  * exp должен быть в будущем (прошивка кэширует токен до exp);
  * полезно видеть scopes/sub — по ним понятно, какому клиенту выдан токен.

Запуск:
  python -X utf8 jwt_info.py "eyJhbGciOi..."
  python -X utf8 jwt_info.py --file token.txt
  echo eyJ... | python -X utf8 jwt_info.py

Скрипт не отправляет токен в сеть и не проверяет подпись (для этого нужен
публичный ключ Sber из star.json, что не даёт права доверять токену).
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import time


def b64url_decode(part: str) -> bytes:
    pad = "=" * (-len(part) % 4)
    return base64.urlsafe_b64decode(part + pad)


def fmt_time(ts: float) -> str:
    try:
        return time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime(ts))
    except (OverflowError, OSError, ValueError):
        return str(ts)


def main() -> int:
    ap = argparse.ArgumentParser(description="JWT inspector (no signature check)")
    ap.add_argument("token", nargs="?", help="JWT string")
    ap.add_argument("--file", help="read JWT from file")
    args = ap.parse_args()

    token = args.token
    if args.file:
        token = open(args.file, encoding="utf-8").read()
    if not token and not sys.stdin.isatty():
        token = sys.stdin.read()
    token = (token or "").strip()
    if token.lower().startswith("bearer "):
        token = token[7:].strip()

    parts = token.split(".")
    if len(parts) != 3:
        print("[!] Это не JWT: ожидается три части через точку.")
        return 2

    try:
        header = json.loads(b64url_decode(parts[0]))
        payload = json.loads(b64url_decode(parts[1]))
    except Exception as e:  # noqa: BLE001
        print(f"[!] Не удалось разобрать JWT: {e}")
        return 2

    print("== header ==")
    print(json.dumps(header, ensure_ascii=False, indent=2))
    print("\n== payload ==")
    print(json.dumps(payload, ensure_ascii=False, indent=2))

    now = time.time()
    print("\n== проверки ==")
    alg = header.get("alg")
    print(f"alg: {alg}" + ("  [ok]" if alg == "RS256" else "  [!] прошивка ждёт RS256"))

    exp = payload.get("exp")
    if isinstance(exp, (int, float)):
        left = exp - now
        print(f"exp: {fmt_time(exp)}  (осталось {left/60:.1f} мин)"
              + ("" if left > 0 else "  [ПРОСРОЧЕН]"))
    else:
        print("exp: отсутствует  [!]")

    iat = payload.get("iat")
    if isinstance(iat, (int, float)):
        print(f"iat: {fmt_time(iat)}")

    for key in ("iss", "aud", "sub", "client_id", "scope", "scopes"):
        if key in payload:
            v = payload[key]
            if isinstance(v, list):
                v = ", ".join(map(str, v))
            print(f"{key}: {v}")

    print("\n[i] Подпись не проверялась. Доверять токену можно только если он "
          "выдан бэкендом Сбера вашему клиенту.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
