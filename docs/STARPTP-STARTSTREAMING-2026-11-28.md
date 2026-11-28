# StarPTP и StartStreaming — подтверждённая wire-спецификация

Дата уточнения: 28.11.2026. Автор и владелец проекта: **DrDroid78**.

## Источник и граница доказательства

Схемы извлечены из embedded `FileDescriptorProto` библиотеки `libstardroid.so` из сохранённого Salute TV APK:

- SHA-256 библиотеки: `991c2a802bfaa7157df50f979009d43c6f175e3150f1705511c271e3553e6440`.
- Полный текстовый вывод 79 дескрипторов сохранён в `artifacts/protocol/embedded-descriptors-salute-tv-0153.txt` рабочей папки; SHA-256 отчёта: `4c8bd39a3c475a38961419588cd5ad58713ed5d902c33a4c6a755bbaf3778608`.
- Поведение тайминга проверено по существующему Ghidra-отчёту `artifacts/ghidra/extracted/46044552630-audio/a-resumed/decompiled.txt`.

Это протокольные свидетельства из Salute TV/StarOS, а не дамп прошивки конкретного SberBoom. Совместимость с большим SberBoom SBDV-00090 остаётся предметом физической приёмки.

## Точные protobuf-поля

```text
StarPtpInfo
  1 optional base_command: .ru.sber.staros.protobuf.BaseCommand
  2 optional master_device_id: string
  3 repeated synced_masters: string
  4 repeated synced_masters_offsets: StarPtpInfo.SyncedMasters

StarPtpInfo.SyncedMasters
  1 optional master: string
  2 optional offset: int64
  3 optional slope: double

AudioStreamingSessionMessage
  1 optional base_command: .ru.sber.staros.protobuf.BaseCommand
  2 optional master_device_id: string
  3 optional mr_session_id: string
  9 optional sender_device_id: string
  4 optional start_streaming: AudioStreamingSessionMessage.StartStreaming
  5 optional stop_streaming: Empty
  6 optional alive: AudioStreamingSessionMessage.Alive
  7 optional dead: Empty
  8 optional change_channel: AudioStreamingSessionMessage.ChangeChannel

AudioStreamingSessionMessage.StartStreaming
  1 optional settings: string
  2 optional mono_timestamp: int64
  3 repeated synced_masters: StartStreaming.SyncedMasters

AudioStreamingSessionMessage.StartStreaming.SyncedMasters
  1 optional master: string
  2 optional offset: int64
```

`offset` в `StartStreaming` — **миллисекунды**. `slope` в этом сообщении отсутствует.

## StarPTP UDP/4391

| Тип | Код | Размер | Поля |
|---|---:|---:|---|
| Sync | `0x10` | 11 | `u8 type`, `u16 sequence LE`, `u64 timestamp LE` |
| DelayReq | `0x11` | 3 | `u8 type`, `u16 sequence LE` |
| DelayResp | `0x12` | 11 | `u8 type`, `u16 sequence LE`, `u64 timestamp LE` |

Timestamp — 64 бит, младший байт первый. Это подтверждено и ручной записью `msgPackSync`/`msgPackDelayResp`, и принимающим `msgUnpack` на little-endian ARM. Утверждение о network byte order в раннем черновике было ошибочным.

Каждый участник отправляет Sync и DelayReq, отвечает DelayResp на входящий DelayReq. `StarPtpClock` вычисляет:

- `localMinusRemoteUs = (t_local_sync_rx - t_remote_sync_tx - (t_remote_delay_rx - t_local_delay_tx)) / 2`;
- `pathDelayUs = ((t_local_sync_rx - t_remote_sync_tx) + (t_remote_delay_rx - t_local_delay_tx)) / 2`.

Оценка считается свежей только после минимум трёх коррелированных образцов и не старше двух секунд.

## StartStreaming timing

Штатный sender получает `CLOCK_MONOTONIC_RAW` в миллисекундах. В существующем Ghidra-отчёте оператор сложения включает `iVar21 / 0x30`, то есть целочисленное деление на 48. При `startPlayingDelayMs=400` и `framesPerBlock=512` клиентская цель:

```text
mono_timestamp = now_monotonic_ms + 400 + 512 / 48
               = now_monotonic_ms + 410 ms
```

Штатный sender передаёт собственный source-device ID одновременно как `master_device_id` и `sender_device_id`. serial колонки — идентификатор динамика, но не timing master телефона.

Приёмник использует знаки offset так:

- если master уже найден в локальной PTP-карте колонки: `startTime = mono_timestamp + local_map_offset`;
- если master найден только в `StartStreaming.synced_masters`: `startTime = mono_timestamp - synced_masters.offset`.

Поэтому отправляемая телефоном запись содержит `phone_clock - speaker_clock`, то есть `localMinusRemoteUs / 1000`. Колонка вычитает её из времени телефона и получает своё локальное время.

## Реализация в проекте

`StreamEngine`:

1. Подключается к выбранному динамику по WebSocket/TLS.
2. Запускает `StarPtpPeer` на UDP/4391 с явно выбранным peer.
3. Ждёт свежую оценку до 2.5 секунд; без неё StartStreaming не отправляется.
4. Повторно проверяет свежесть непосредственно перед StartStreaming.
5. Передаёт `clientId` как master/sender, `now + 410 ms` и один измеряемый `synced_masters` record.
6. Закрывает PTP socket при ошибке, отмене, остановке и shutdown.
7. Во время активной сессии останавливает поток после трёх последовательных секундных проверок без свежей PTP-оценки.

`Protocol.StartStreamingTiming` и `Protocol.syncedMaster()` покрыты unit/regression tests, включая отрицательный offset и сохраняемость повторяющегося поля.

## Непроверенное

- Реальный ответ SberBoom на StarPTP и StartStreaming.
- Слышимый звук, задержка и устойчивость к потере UDP/PTP.
- Android использует публичный `System.nanoTime()` (CLOCK_MONOTONIC), а прошивка — CLOCK_MONOTONIC_RAW. Foreground-сервис удерживает partial wake lock, но поведение после suspend требует проверки на телефоне.
- Легальный backend JWT для StarCommand остаётся обязательным внешним условием; PIN-токен его не заменяет.
