#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Минимальный клиент SberCast/StarOS без внешних зависимостей.

Реализует:
  * protobuf-wire (varint / length-delimited);
  * WebSocket-клиент поверх TLS (самоподписанный сертификат);
  * PIN-сопряжение, GetMetaData, SetVolume;
  * StarCommand(160) StartStreaming/StopStreaming;
  * UDP-отправку пакетов seq|timestamp|opus;
  * разбор Ogg/Opus в отдельные пакеты.

Используется как тестовый клиент и как эталон для сверки с Kotlin-кодом.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import random
import socket
import ssl
import struct
import time
import uuid
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# protobuf-wire
# ---------------------------------------------------------------------------

WIRE_VARINT = 0
WIRE_BYTES = 2
WIRE_FIXED32 = 5


def varint(value: int) -> bytes:
    value &= 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while value & ~0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value & 0x7F)
    return bytes(out)


def field_varint(tag: int, value: int) -> bytes:
    return varint((tag << 3) | WIRE_VARINT) + varint(value)


def field_bytes(tag: int, payload: bytes) -> bytes:
    return varint((tag << 3) | WIRE_BYTES) + varint(len(payload)) + payload


def field_string(tag: int, value: str) -> bytes:
    return field_bytes(tag, value.encode("utf-8"))


def concat(*parts: bytes) -> bytes:
    return b"".join(parts)


def read_varint(data: bytes, pos: int) -> Tuple[int, int]:
    value = 0
    shift = 0
    while pos < len(data):
        b = data[pos]
        pos += 1
        value |= (b & 0x7F) << shift
        if not (b & 0x80):
            return value, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")
    raise ValueError("truncated varint")


def decode(data: bytes) -> Dict[int, object]:
    out: Dict[int, object] = {}
    pos = 0
    while pos < len(data):
        key, pos = read_varint(data, pos)
        tag, wire = key >> 3, key & 7
        if wire == WIRE_VARINT:
            value, pos = read_varint(data, pos)
            out[tag] = value
        elif wire == WIRE_BYTES:
            length, pos = read_varint(data, pos)
            out[tag] = data[pos:pos + length]
            pos += length
        elif wire == WIRE_FIXED32:
            out[tag] = data[pos:pos + 4]
            pos += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
    return out


def nested(mapping: Dict[int, object], tag: int) -> Optional[Dict[int, object]]:
    value = mapping.get(tag)
    return decode(value) if isinstance(value, (bytes, bytearray)) else None


def as_str(mapping: Dict[int, object], tag: int) -> Optional[str]:
    value = mapping.get(tag)
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", "replace")
    return None


# ---------------------------------------------------------------------------
# SberCastMessage
# ---------------------------------------------------------------------------

ENV_TYPE = 1
ENV_MSG_ID = 2
ENV_TOKEN = 3
ENV_DIRECTIVE_DATA = 4
ENV_REQUEST_DATA = 5
ENV_TOKEN_TYPE = 6
ENV_CLIENT_NAME = 7
ENV_STAR_COMMAND_DATA = 8
ENV_WSS_TYPE = 9
ENV_IS_REQUEST = 10
ENV_CLIENT_ID = 11

TYPE_DIRECTIVE = 1
TYPE_REQUEST = 2
TYPE_STAR_COMMAND = 3

TOKEN_BACKEND_JWT = 0
TOKEN_PIN = 1

OP_PIN_CONNECT = 4
OP_CONFIRM_PIN_CONNECT = 6
OP_GET_META_DATA = 10
OP_GET_STATE = 12
OP_SET_VOLUME = 14
OP_MEDIA_COMMAND = 16

STAR_AUDIO = 160
AUDIO_START = 4
AUDIO_STOP = 5
AUDIO_ALIVE = 6
AUDIO_DEAD = 7

DEFAULT_PORT = 20000
USER_AGENT = "WebSocket++/0.8.2"


def envelope(msg_id: str, request_data: bytes, token: Optional[str], client_name: str,
             client_id: str, msg_type: int = TYPE_REQUEST,
             token_type: int = TOKEN_PIN) -> bytes:
    parts = [
        field_varint(ENV_TYPE, msg_type),
        field_string(ENV_MSG_ID, msg_id),
    ]
    if token:
        parts.append(field_string(ENV_TOKEN, token))
    parts += [
        field_bytes(ENV_REQUEST_DATA, request_data),
        field_varint(ENV_TOKEN_TYPE, token_type),
        field_string(ENV_CLIENT_NAME, client_name),
        field_varint(ENV_IS_REQUEST, 1),
        field_string(ENV_CLIENT_ID, client_id),
    ]
    return concat(*parts)


def star_envelope(msg_id: str, star_command: bytes, token: Optional[str],
                  client_name: str, client_id: str) -> bytes:
    parts = [
        field_varint(ENV_TYPE, TYPE_STAR_COMMAND),
        field_string(ENV_MSG_ID, msg_id),
    ]
    if token:
        parts.append(field_string(ENV_TOKEN, token))
    parts += [
        field_bytes(ENV_STAR_COMMAND_DATA, field_bytes(1, star_command)),
        field_varint(ENV_TOKEN_TYPE, TOKEN_BACKEND_JWT),
        field_string(ENV_CLIENT_NAME, client_name),
        field_varint(ENV_IS_REQUEST, 1),
        field_string(ENV_CLIENT_ID, client_id),
    ]
    return concat(*parts)


def op(code: int, inner: bytes = None) -> bytes:
    if inner is None:
        inner = field_bytes(1, b"")
    return field_bytes(code, inner)


def audio_settings_json(unicast_ip: str, port: int, mr_session_id: str,
                        sample_rate: int = 48000, opus_frame_ms: int = 20,
                        fec_ecc: int = 0, fec_msg: int = 1) -> str:
    return json.dumps({
        "sampleRate": sample_rate,
        "udpSettings": {
            "FEC": {"ecc": fec_ecc, "msg": fec_msg},
            "unicastList": unicast_ip,
            "udpProtocolVersion": 0,
            "type": "BOOM_BOX",
            "mrSessionId": mr_session_id,
            "port": port,
            "opusFrameSizeMs": opus_frame_ms,
            "resamplerQuality": "DEFAULT",
        },
        "audiosync": {"stereo": 0},
        "echo": False,
        "correctUnderrunMaster": False,
    }, separators=(",", ":"))


def star_audio_start(master_id: str, mr_session_id: str, sender_id: str,
                     settings: str, mono_timestamp: int = 0) -> bytes:
    start = concat(
        field_string(1, settings),
        field_varint(2, mono_timestamp),
    )
    session = concat(
        field_string(2, master_id),
        field_string(3, mr_session_id),
        field_string(9, sender_id),
        field_bytes(AUDIO_START, start),
    )
    return field_bytes(STAR_AUDIO, session)


def star_audio_stop(master_id: str, mr_session_id: str, sender_id: str) -> bytes:
    session = concat(
        field_string(2, master_id),
        field_string(3, mr_session_id),
        field_string(9, sender_id),
        field_bytes(AUDIO_STOP, field_bytes(1, b"")),
    )
    return field_bytes(STAR_AUDIO, session)


def choose_udp_port(rng: random.Random = None) -> int:
    rng = rng or random.Random()
    steps = (35620 - 10000) // 2
    return 10000 + 2 * rng.randint(0, steps)


# ---------------------------------------------------------------------------
# WebSocket (TLS, без внешних библиотек)
# ---------------------------------------------------------------------------

class WebSocketError(RuntimeError):
    pass


class WssClient:
    def __init__(self, host: str, port: int = DEFAULT_PORT, timeout: float = 8.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.sock: Optional[ssl.SSLSocket] = None
        self._buf = bytearray()

    def connect(self) -> None:
        raw = socket.create_connection((self.host, self.port), timeout=self.timeout)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.sock = ctx.wrap_socket(raw, server_hostname=self.host)
        self.sock.settimeout(self.timeout)

        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET / HTTP/1.1\r\n"
            f"Host: {self.host}:{self.port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"User-Agent: {USER_AGENT}\r\n"
            f"Origin: https://localhost\r\n"
            f"\r\n"
        )
        self.sock.sendall(request.encode())

        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WebSocketError("handshake: connection closed")
            response += chunk
        head, _, rest = response.partition(b"\r\n\r\n")
        status = head.split(b"\r\n", 1)[0].decode("latin1")
        if "101" not in status:
            raise WebSocketError(f"handshake failed: {status}")
        expected = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        ).decode()
        if expected.lower() not in head.decode("latin1").lower():
            raise WebSocketError("handshake: bad Sec-WebSocket-Accept")
        self._buf = bytearray(rest)

    def close(self) -> None:
        if self.sock is not None:
            try:
                self._send_frame(0x8, b"")
            except Exception:
                pass
            try:
                self.sock.close()
            finally:
                self.sock = None

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        if self.sock is None:
            raise WebSocketError("not connected")
        header = bytearray([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header += struct.pack("!H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack("!Q", length)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def send_binary(self, payload: bytes) -> None:
        self._send_frame(0x2, payload)

    def _read_exact(self, n: int) -> bytes:
        if self.sock is None:
            raise WebSocketError("not connected")
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise WebSocketError("connection closed")
            self._buf += chunk
        out = bytes(self._buf[:n])
        del self._buf[:n]
        return out

    def recv_message(self) -> bytes:
        fragments = bytearray()
        while True:
            b1, b2 = self._read_exact(2)
            fin = b1 & 0x80
            opcode = b1 & 0x0F
            masked = b2 & 0x80
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else None
            payload = self._read_exact(length)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

            if opcode == 0x9:  # ping
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode == 0x8:  # close
                raise WebSocketError("server closed connection")
            if opcode in (0x1, 0x2):
                fragments = bytearray(payload)
                if fin:
                    return bytes(fragments)
            elif opcode == 0x0:
                fragments += payload
                if fin:
                    return bytes(fragments)
            else:
                raise WebSocketError(f"unsupported opcode {opcode}")


# ---------------------------------------------------------------------------
# Клиент протокола
# ---------------------------------------------------------------------------

@dataclass
class MetaData:
    serial_number: Optional[str]
    name: Optional[str]
    model: Optional[str]
    firmware: Optional[str]
    udp_protocol_version: Optional[int]


class SberBoomClient:
    def __init__(self, host: str, port: int = DEFAULT_PORT,
                 client_name: str = "SberBoom Test Client",
                 client_id: Optional[str] = None):
        self.host = host
        self.port = port
        self.client_name = client_name
        self.client_id = client_id or str(uuid.uuid4())
        self.ws = WssClient(host, port)
        self.token: Optional[str] = None
        self.last_envelope: Optional[Dict[int, object]] = None

    def connect(self) -> None:
        self.ws.connect()

    def close(self) -> None:
        self.ws.close()

    def _send(self, msg_id: str, request_data: bytes, token: Optional[str],
              token_type: int) -> None:
        self.ws.send_binary(
            envelope(msg_id, request_data, token, self.client_name, self.client_id,
                     token_type=token_type)
        )

    def _wait_for(self, predicate, timeout: float):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.ws.sock.settimeout(max(0.1, deadline - time.time()))
            try:
                raw = self.ws.recv_message()
            except (socket.timeout, TimeoutError):
                return None
            env = decode(raw)
            self.last_envelope = env
            result = predicate(env)
            if result is not None:
                return result
        return None

    def request(self, request_data: bytes, timeout: float = 6.0) -> Optional[Dict[int, object]]:
        msg_id = str(uuid.uuid4())
        self._send(msg_id, request_data, self.token, TOKEN_PIN)

        def match(env):
            if as_str(env, ENV_MSG_ID) == msg_id:
                return env.get(ENV_REQUEST_DATA) or b""
            return None

        payload = self._wait_for(match, timeout)
        if payload is None:
            return None
        return decode(payload)

    def pair(self, timeout: float = 120.0) -> str:
        msg_id = str(uuid.uuid4())
        self._send(msg_id, op(OP_PIN_CONNECT), None, TOKEN_PIN)

        def match(env):
            data = env.get(ENV_REQUEST_DATA)
            if not isinstance(data, (bytes, bytearray)):
                return None
            req = decode(data)
            init = nested(req, OP_PIN_CONNECT)
            if init is not None:
                status = init.get(1)
                if status == 2:
                    token = as_str(init, 2)
                    if token and len(token) >= 16:
                        return token
                elif status in (3, 5):
                    raise RuntimeError(f"pairing status {status}")
            confirm = nested(req, OP_CONFIRM_PIN_CONNECT)
            if confirm is not None:
                if confirm.get(1) == 1:
                    token = as_str(confirm, 2)
                    if token and len(token) >= 16:
                        return token
                elif confirm.get(1) == 2:
                    raise RuntimeError("pairing rejected")
            return None

        token = self._wait_for(match, timeout)
        if not token:
            raise TimeoutError("pairing timeout: press '+' on the speaker")
        self.token = token
        return token

    def metadata(self, timeout: float = 6.0) -> Optional[MetaData]:
        req = self.request(op(OP_GET_META_DATA), timeout)
        if req is None:
            return None
        response = nested(req, OP_GET_META_DATA)
        if response is None:
            return None
        meta = nested(response, 2)
        if meta is None:
            return None
        info = nested(meta, 1)
        return MetaData(
            serial_number=as_str(info, 5) if info else None,
            name=as_str(info, 1) if info else None,
            model=as_str(info, 2) if info else None,
            firmware=as_str(info, 3) if info else None,
            udp_protocol_version=meta.get(8) if isinstance(meta.get(8), int) else None,
        )

    def set_volume(self, percent: int, timeout: float = 5.0) -> Optional[int]:
        req = self.request(op(OP_SET_VOLUME, field_varint(1, max(0, min(100, percent)))), timeout)
        if req is None:
            return None
        status = nested(req, OP_SET_VOLUME)
        return status.get(1) if status else None

    def send_star_command(self, star_command: bytes, token: Optional[str] = None,
                          timeout: float = 4.0) -> Optional[Dict[int, object]]:
        msg_id = str(uuid.uuid4())
        self.ws.send_binary(
            star_envelope(msg_id, star_command, token or self.token,
                          self.client_name, self.client_id)
        )

        def match(env):
            data = env.get(ENV_STAR_COMMAND_DATA)
            if isinstance(data, (bytes, bytearray)):
                return decode(data)
            return None

        payload = self._wait_for(match, timeout)
        if payload is None:
            return None
        inner = payload.get(1)
        return decode(inner) if isinstance(inner, (bytes, bytearray)) else payload


# ---------------------------------------------------------------------------
# UDP
# ---------------------------------------------------------------------------

OPUS_FRAME_MS = [10.0, 20.0, 40.0, 60.0] * 3 + [10.0, 20.0, 10.0, 20.0] + \
                [2.5, 5.0, 10.0, 20.0] * 4


def opus_samples(packet: bytes) -> int:
    if not packet:
        return 0
    toc = packet[0]
    config = toc >> 3
    code = toc & 3
    frame = int(OPUS_FRAME_MS[config] * 48)
    if code == 0:
        return frame
    if code in (1, 2):
        return frame * 2
    frames = packet[1] & 0x3F if len(packet) > 1 else 2
    return frame * max(1, frames)


class UdpAudioSender:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.connect((host, port))
        self.sequence = 0
        self.timestamp = 0
        self.packets = 0
        self.bytes_sent = 0

    def local_ip(self) -> str:
        return self.sock.getsockname()[0]

    def send(self, opus: bytes) -> None:
        header = struct.pack("<II", self.sequence & 0xFFFFFFFF,
                             self.timestamp & 0xFFFFFFFF)
        self.sock.send(header + opus)
        self.sequence = (self.sequence + 1) & 0x7FFFFFFF
        self.timestamp += opus_samples(opus)
        self.packets += 1
        self.bytes_sent += len(opus) + 8

    def close(self) -> None:
        self.sock.close()


def ogg_opus_packets(path: str) -> List[bytes]:
    """Извлекает Opus-пакеты из Ogg-файла (OpusHead/OpusTags пропускаются)."""
    with open(path, "rb") as handle:
        data = handle.read()
    packets: List[bytes] = []
    current = bytearray()
    pos = 0
    while pos + 27 <= len(data):
        if data[pos:pos + 4] != b"OggS":
            pos += 1
            continue
        version = data[pos + 4]
        if version != 0:
            raise ValueError("unsupported Ogg version")
        segments = data[pos + 26]
        table = data[pos + 27:pos + 27 + segments]
        body = pos + 27 + segments
        offset = body
        for seg in table:
            current += data[offset:offset + seg]
            offset += seg
            if seg < 255:
                if current:
                    packets.append(bytes(current))
                current = bytearray()
        pos = offset
    # Первые два пакета — OpusHead и OpusTags.
    return packets[2:]


def local_ip_towards(host: str) -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect((host, 9))
        return sock.getsockname()[0]
    finally:
        sock.close()
