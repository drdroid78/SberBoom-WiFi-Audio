#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Локальный UDP-приёмник для проверки формата потока SberBoom.

Проверяет:
  * заголовок seq|timestamp|opus;
  * монотонность seq;
  * приращение timestamp по TOC Opus;
  * размер пакета.

Пример:
  python loopback_receiver.py --port 12346 --duration 10
"""

from __future__ import annotations

import argparse
import socket
import struct
import time

import sberboom_proto as p


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--duration", type=float, default=10.0)
    ap.add_argument("--out", help="write raw Opus packets to this file")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(1.0)
    deadline = time.time() + args.duration
    count = 0
    total = 0
    last_seq = None
    last_ts = None
    seq_errors = 0
    ts_errors = 0
    out = open(args.out, "wb") if args.out else None

    print(f"[*] listening udp://0.0.0.0:{args.port} for {args.duration}s")
    try:
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(65536)
            except socket.timeout:
                continue
            if len(data) < 8:
                print(f"[!] short packet {len(data)} from {addr}")
                continue
            seq, ts = struct.unpack("<II", data[:8])
            opus = data[8:]
            count += 1
            total += len(data)
            if last_seq is not None and seq != (last_seq + 1) & 0x7FFFFFFF:
                seq_errors += 1
                print(f"[!] seq gap: {last_seq} -> {seq}")
            expected = p.opus_samples(opus)
            if last_ts is not None and ts != (last_ts + expected) & 0xFFFFFFFF:
                ts_errors += 1
                if ts_errors <= 5:
                    print(f"[!] ts mismatch: {last_ts}+{expected} != {ts}")
            last_seq, last_ts = seq, ts
            if out:
                out.write(struct.pack("<H", len(opus)) + opus)
            if count % 50 == 0:
                print(f"[*] {count} packets, {total} bytes, last seq={seq} ts={ts}")
    finally:
        sock.close()
        if out:
            out.close()

    print(f"[+] done: {count} packets, {total} bytes, "
          f"seq_errors={seq_errors}, ts_errors={ts_errors}")
    return 0 if seq_errors == 0 and ts_errors == 0 else 3


if __name__ == "__main__":
    raise SystemExit(main())
