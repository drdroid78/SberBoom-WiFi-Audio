#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Юнит- и интеграционные тесты протокола (без динамика)."""

from __future__ import annotations

import os
import socket
import struct
import tempfile
import threading
import unittest

import sberboom_proto as p


class TlvTest(unittest.TestCase):
    def test_varint(self):
        self.assertEqual(p.varint(0), b"\x00")
        self.assertEqual(p.varint(300), b"\xac\x02")
        self.assertEqual(p.varint(0xFFFFFFFF), b"\xff\xff\xff\xff\x0f")

    def test_roundtrip(self):
        data = p.concat(
            p.field_string(2, "hello"),
            p.field_varint(3, 42),
            p.field_string(7, "мир"),
        )
        m = p.decode(data)
        self.assertEqual(p.as_str(m, 2), "hello")
        self.assertEqual(m[3], 42)
        self.assertEqual(p.as_str(m, 7), "мир")

    def test_envelope(self):
        env = p.decode(p.envelope("id-1", p.op(p.OP_GET_STATE), "pin", "client", "cid"))
        self.assertEqual(env[p.ENV_TYPE], p.TYPE_REQUEST)
        self.assertEqual(p.as_str(env, p.ENV_MSG_ID), "id-1")
        self.assertEqual(p.as_str(env, p.ENV_TOKEN), "pin")
        self.assertEqual(env[p.ENV_TOKEN_TYPE], p.TOKEN_PIN)
        self.assertEqual(p.as_str(env, p.ENV_CLIENT_NAME), "client")

    def test_star_envelope(self):
        cmd = p.star_audio_start("speaker", "mr", "sender", "{}")
        env = p.decode(p.star_envelope("id-2", cmd, None, "client", "cid"))
        self.assertEqual(env[p.ENV_TYPE], p.TYPE_STAR_COMMAND)
        self.assertEqual(env[p.ENV_TOKEN_TYPE], p.TOKEN_BACKEND_JWT)
        cast = p.nested(env, p.ENV_STAR_COMMAND_DATA)
        self.assertIsNotNone(cast)
        star = p.decode(cast[1])
        self.assertIn(p.STAR_AUDIO, star)

    def test_audio_layout(self):
        settings = p.audio_settings_json("10.0.0.2", 12346, "mr-1")
        cmd = p.star_audio_start("speaker", "mr-1", "sender", settings)
        audio = p.nested(p.decode(cmd), p.STAR_AUDIO)
        self.assertEqual(p.as_str(audio, 2), "speaker")
        self.assertEqual(p.as_str(audio, 3), "mr-1")
        self.assertEqual(p.as_str(audio, 9), "sender")
        start = p.nested(audio, p.AUDIO_START)
        self.assertEqual(p.as_str(start, 1), settings)
        self.assertEqual(start[2], 0)

    def test_settings(self):
        s = p.audio_settings_json("10.0.0.2", 20000, "mr")
        self.assertIn('"ecc":0', s)
        self.assertIn('"msg":1', s)
        self.assertIn('"port":20000', s)
        self.assertIn('"audiosync":{"stereo":0}', s)
        self.assertIn('"sampleRate":48000', s)

    def test_udp_port(self):
        for _ in range(200):
            port = p.choose_udp_port()
            self.assertTrue(10000 <= port <= 35620)
            self.assertEqual(port % 2, 0)


class OpusTest(unittest.TestCase):
    def test_samples(self):
        self.assertEqual(p.opus_samples(bytes([31 << 3])), 960)
        self.assertEqual(p.opus_samples(bytes([16 << 3])), 120)
        self.assertEqual(p.opus_samples(bytes([11 << 3])), 2880)
        self.assertEqual(p.opus_samples(bytes([(31 << 3) | 1])), 1920)


class UdpTest(unittest.TestCase):
    def test_header(self):
        opus = b"\x11\x22\x33"
        packet = struct.pack("<II", 0x01020304, 0x0A0B0C0D) + opus
        seq, ts = struct.unpack("<II", packet[:8])
        self.assertEqual(seq, 0x01020304)
        self.assertEqual(ts, 0x0A0B0C0D)
        self.assertEqual(packet[8:], opus)

    def test_loopback(self):
        recv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        recv.bind(("127.0.0.1", 0))
        port = recv.getsockname()[1]
        recv.settimeout(2.0)

        sender = p.UdpAudioSender("127.0.0.1", port)
        try:
            for _ in range(10):
                sender.send(bytes([31 << 3]) + b"\x00" * 20)
        finally:
            sender.close()

        last_seq = None
        last_ts = None
        for _ in range(10):
            data, _ = recv.recvfrom(65536)
            self.assertGreaterEqual(len(data), 8)
            seq, ts = struct.unpack("<II", data[:8])
            if last_seq is not None:
                self.assertEqual(seq, (last_seq + 1) & 0x7FFFFFFF)
                self.assertEqual(ts, (last_ts + 960) & 0xFFFFFFFF)
            last_seq, last_ts = seq, ts
        recv.close()


def _ogg_page(packets, seq=0):
    table = bytearray()
    body = bytearray()
    for packet in packets:
        remaining = len(packet)
        offset = 0
        while remaining >= 255:
            table.append(255)
            body += packet[offset:offset + 255]
            offset += 255
            remaining -= 255
        table.append(remaining)
        body += packet[offset:]
    header = b"OggS" + bytes([0, 0]) + struct.pack("<q", 0) + \
        struct.pack("<I", 1) + struct.pack("<I", seq) + b"\x00\x00\x00\x00" + \
        bytes([len(table)]) + bytes(table)
    return header + bytes(body)


class OggTest(unittest.TestCase):
    def test_extract(self):
        packets = [b"OpusHead" + b"\x00" * 11, b"OpusTags" + b"\x00" * 8,
                   bytes([28 << 3]) + b"\x01" * 10, bytes([28 << 3]) + b"\x02" * 10]
        data = _ogg_page(packets[:2], 0) + _ogg_page(packets[2:], 1)
        fd, path = tempfile.mkstemp(suffix=".opus")
        try:
            os.write(fd, data)
            os.close(fd)
            extracted = p.ogg_opus_packets(path)
            self.assertEqual(len(extracted), 2)
            self.assertEqual(extracted[0][0], 28 << 3)
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
