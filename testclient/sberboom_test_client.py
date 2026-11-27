#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Тестовый клиент SberBoom.

Примеры:
  python sberboom_test_client.py --host 192.168.1.50 --metadata
  python sberboom_test_client.py --host 192.168.1.50 --pair --metadata
  python sberboom_test_client.py --host 192.168.1.50 --pair --star-test
  python sberboom_test_client.py --host 192.168.1.50 --opus-file test.opus --duration 10

Без backend-JWT динамик отклонит StarCommand(160); клиент покажет это явно.
"""

from __future__ import annotations

import argparse
import sys
import time

import sberboom_proto as p


def main() -> int:
    ap = argparse.ArgumentParser(description="SberBoom test client")
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=p.DEFAULT_PORT)
    ap.add_argument("--pair", action="store_true", help="PIN pairing (press '+' on the speaker)")
    ap.add_argument("--metadata", action="store_true")
    ap.add_argument("--volume", type=int, help="set volume 0..100")
    ap.add_argument("--jwt", help="backend JWT (optional)")
    ap.add_argument("--master-id", help="override master_device_id")
    ap.add_argument("--star-test", action="store_true", help="send StartStreaming + synthetic UDP")
    ap.add_argument("--opus-file", help="Ogg/Opus file to stream")
    ap.add_argument("--udp-port", type=int, help="UDP port (default: random even)")
    ap.add_argument("--duration", type=float, default=5.0)
    ap.add_argument("--timeout", type=float, default=6.0)
    args = ap.parse_args()

    client = p.SberBoomClient(args.host, args.port)
    try:
        print(f"[*] connect wss://{args.host}:{args.port}/")
        client.connect()
        print("[+] connected")

        if args.pair:
            print("[*] pairing: press and hold '+' on the speaker…")
            token = client.pair(timeout=120)
            print(f"[+] token: {token[:8]}… ({len(token)} chars)")

        meta = None
        if args.metadata or args.star_test or args.opus_file:
            meta = client.metadata(timeout=args.timeout)
            if meta:
                print(f"[+] metadata: name={meta.name!r} model={meta.model!r} "
                      f"fw={meta.firmware!r} serial={meta.serial_number!r} "
                      f"udp={meta.udp_protocol_version}")
            else:
                print("[!] metadata: no response")

        if args.volume is not None:
            status = client.set_volume(args.volume, timeout=args.timeout)
            print(f"[+] volume {args.volume}% -> status={status}")

        if args.star_test or args.opus_file:
            master = args.master_id or (meta.serial_number if meta else None)
            if not master:
                print("[!] no master_device_id (use --master-id)")
                return 2
            mr = p.uuid.uuid4().hex
            udp_port = args.udp_port or p.choose_udp_port()
            local_ip = p.local_ip_towards(args.host)
            settings = p.audio_settings_json(local_ip, udp_port, mr)
            print(f"[*] UDP {local_ip}:{udp_port} -> {args.host}:{udp_port}")
            print(f"[*] settings: {settings}")

            star = p.star_audio_start(master, mr, client.client_id, settings)
            reply = client.send_star_command(star, token=args.jwt, timeout=4.0)
            print(f"[+] StarCommand reply: {reply}")
            if reply is None:
                print("[!] no reply — firmware likely rejected StarCommand "
                      "(needs token_type=0 backend JWT)")

            sender = p.UdpAudioSender(args.host, udp_port)
            try:
                packets = []
                if args.opus_file:
                    packets = p.ogg_opus_packets(args.opus_file)
                    print(f"[+] {len(packets)} Opus packets from {args.opus_file}")
                else:
                    # Синтетические пакеты: валидный TOC (CELT FB 20 ms), нулевой payload.
                    packets = [bytes([31 << 3]) + b"\x00" * 80 for _ in range(50)]
                    print("[*] synthetic Opus packets (wire test only)")

                deadline = time.time() + args.duration
                for packet in packets:
                    if time.time() >= deadline:
                        break
                    sender.send(packet)
                    time.sleep(0.02)
                print(f"[+] sent {sender.packets} packets, {sender.bytes_sent} bytes")
            finally:
                sender.close()

            stop = p.star_audio_stop(master, mr, client.client_id)
            client.send_star_command(stop, token=args.jwt, timeout=1.5)
            print("[+] stop sent")

        return 0
    except Exception as exc:
        print(f"[!] {type(exc).__name__}: {exc}")
        return 1
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
