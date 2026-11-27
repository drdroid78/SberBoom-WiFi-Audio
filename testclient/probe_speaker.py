#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Поиск сетевых возможностей колонки: DLNA/UPnP-рендерер, AirPlay, Cast, StarOS.

Идея: если колонка умеет принимать аудио-URL по DLNA/UPnP (AVTransport),
то звук с телефона можно отдать по Wi-Fi без backend-JWT — телефон
поднимает HTTP-сервер с аудио, колонка его проигрывает.

Запуск:
  python -X utf8 probe_speaker.py                 # SSDP + поиск по mDNS-именам
  python -X utf8 probe_speaker.py --host 192.168.1.50
  python -X utf8 probe_speaker.py --host 192.168.1.50 --ports 80,443,7000,8008,8009,8200,1900,20000,20023,21023

Скрипт ничего не меняет на устройствах: только SSDP M-SEARCH, TCP-connect
и один HTTP GET на открытые веб-порты.
"""

from __future__ import annotations

import argparse
import re
import socket
import sys
import time
from concurrent.futures import ThreadPoolExecutor

SSDP_ADDR = ("239.255.255.250", 1900)
SSDP_MSEARCH = (
    "M-SEARCH * HTTP/1.1\r\n"
    "HOST: 239.255.255.250:1900\r\n"
    'MAN: "ssdp:discover"\r\n'
    "MX: 2\r\n"
    "ST: ssdp:all\r\n"
    "\r\n"
).encode("ascii")

DEFAULT_PORTS = [80, 443, 1900, 7000, 8008, 8009, 8200, 20000, 20023, 21023]
DLNA_PORTS = [49152, 49153, 49154, 49155, 49156, 49157]


def ssdp_discover(timeout: float = 3.0) -> list[dict]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.settimeout(0.5)
    try:
        sock.sendto(SSDP_MSEARCH, SSDP_ADDR)
    except OSError as e:
        print(f"[!] SSDP send failed: {e}")
        return []

    found: dict[tuple[str, str], dict] = {}
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            data, addr = sock.recvfrom(8192)
        except socket.timeout:
            continue
        except OSError:
            break
        text = data.decode("utf-8", "replace")
        headers = {}
        for line in text.splitlines()[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        key = (addr[0], headers.get("usn", headers.get("st", "")))
        if key not in found:
            found[key] = {"ip": addr[0], "headers": headers}
    sock.close()
    return list(found.values())


def tcp_open(host: str, port: int, timeout: float = 0.7) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return s.connect_ex((host, port)) == 0
    finally:
        s.close()


def http_banner(host: str, port: int, timeout: float = 1.5) -> str:
    try:
        s = socket.create_connection((host, port), timeout=timeout)
    except OSError:
        return ""
    try:
        scheme = "https" if port == 443 else "http"
        s.sendall(
            f"GET / HTTP/1.0\r\nHost: {host}\r\nUser-Agent: probe\r\n\r\n".encode()
        )
        chunks = []
        total = 0
        while total < 2048:
            b = s.recv(1024)
            if not b:
                break
            chunks.append(b)
            total += len(b)
        text = b"".join(chunks).decode("utf-8", "replace")
        first = text.splitlines()[0] if text else ""
        server = ""
        for line in text.splitlines():
            if line.lower().startswith("server:"):
                server = line.strip()
                break
        return f"{scheme}: {first} {server}".strip()
    except OSError:
        return ""
    finally:
        s.close()


def fetch_description(location: str, timeout: float = 2.0) -> str:
    m = re.match(r"http://([^/:]+)(?::(\d+))?(/.*)?$", location)
    if not m:
        return ""
    host, port, path = m.group(1), int(m.group(2) or 80), m.group(3) or "/"
    try:
        s = socket.create_connection((host, port), timeout=timeout)
    except OSError:
        return ""
    try:
        s.sendall(
            f"GET {path} HTTP/1.0\r\nHost: {host}\r\nUser-Agent: probe\r\n\r\n".encode()
        )
        data = b""
        while len(data) < 65536:
            b = s.recv(8192)
            if not b:
                break
            data += b
        return data.decode("utf-8", "replace")
    except OSError:
        return ""
    finally:
        s.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="SberBoom network capability probe")
    ap.add_argument("--host", help="IP колонки (иначе — только SSDP-обнаружение)")
    ap.add_argument("--ports", help="список TCP-портов через запятую")
    ap.add_argument("--timeout", type=float, default=1.5)
    args = ap.parse_args()

    ports = DEFAULT_PORTS
    if args.ports:
        ports = [int(x) for x in args.ports.split(",") if x.strip()]

    print("[*] SSDP M-SEARCH…")
    devices = ssdp_discover()
    if not devices:
        print("[!] SSDP: ответов нет (DLNA/UPnP, скорее всего, не поддерживается)")
    for d in devices:
        h = d["headers"]
        print(f"  {d['ip']}  ST={h.get('st','?')}  SERVER={h.get('server','?')}")
        loc = h.get("location")
        if loc:
            xml = fetch_description(loc)
            if "MediaRenderer" in xml or "AVTransport" in xml:
                print(f"    [+] DLNA MediaRenderer: {loc}")
            elif xml:
                print(f"    [i] description: {loc} ({len(xml)} bytes)")

    if not args.host:
        print("\n[i] Укажите --host <IP колонки> для проверки портов.")
        return 0

    host = args.host
    print(f"\n[*] TCP-порты {host}: {', '.join(map(str, ports))}")
    with ThreadPoolExecutor(max_workers=16) as ex:
        results = list(ex.map(lambda p: (p, tcp_open(host, p, args.timeout)), ports))
    open_ports = [p for p, ok in results if ok]
    for p, ok in results:
        print(f"  {p:>5}  {'open' if ok else 'closed'}")
    if not open_ports:
        print("[!] Ни один порт не открыт — проверьте IP и что телефон в той же сети.")

    for p in open_ports:
        if p in (80, 443, 8080, 8200):
            print(f"  HTTP {p}: {http_banner(host, p, args.timeout) or 'no banner'}")

    print("\n[i] Дополнительно проверьте DLNA-порты 49152..49157:")
    with ThreadPoolExecutor(max_workers=8) as ex:
        dlna = list(ex.map(lambda p: (p, tcp_open(host, p, args.timeout)), DLNA_PORTS))
    for p, ok in dlna:
        if ok:
            print(f"  {p} open (возможен DLNA-рендерер)")

    print("\nИтог:")
    if 1900 in open_ports or devices:
        print("  UPnP/DLNA отвечает — можно попробовать отправить аудио-URL через AVTransport.")
    else:
        print("  UPnP/DLNA не обнаружен. Wi-Fi-приём аудио, кроме StarOS, не виден.")
    if 7000 in open_ports:
        print("  Порт 7000 открыт — возможно, AirPlay (требует проверки).")
    if 8008 in open_ports or 8009 in open_ports:
        print("  Порты Cast (8008/8009) открыты — возможно, Google Cast.")
    if 20000 in open_ports:
        print("  StarOS SberCast открыт — это нативный путь (нужен backend-JWT).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
