# Python-клиент SberBoom

Эталонная реализация протокола без внешних зависимостей. Используется для
проверки Kotlin-кода и для ручной диагностики динамика.

## Файлы

| Файл | Назначение |
|---|---|
| `sberboom_proto.py` | protobuf-wire, WebSocket/TLS, PIN, StarCommand, UDP, Ogg/Opus |
| `sberboom_test_client.py` | CLI: сопряжение, метаданные, громкость, StartStreaming |
| `loopback_receiver.py` | локальный UDP-приёмник, проверка `seq`/`timestamp` |
| `test_protocol.py` | 11 юнит- и интеграционных тестов |

## Тесты

```powershell
cd C:\work\sberboom\testclient
python -X utf8 test_protocol.py
```

Ожидаемый результат: `Ran 11 tests ... OK`.

## Диагностика динамика

```powershell
python -X utf8 sberboom_test_client.py --host 192.168.1.50 --metadata
python -X utf8 sberboom_test_client.py --host 192.168.1.50 --pair --metadata
python -X utf8 sberboom_test_client.py --host 192.168.1.50 --volume 30
python -X utf8 sberboom_test_client.py --host 192.168.1.50 --star-test --duration 5
```

`--star-test` отправляет `StarCommand(160)` и синтетические UDP-пакеты.
Без backend-JWT динамик отклонит команду; клиент покажет отсутствие ответа.

## Локальная проверка формата

Терминал 1:

```powershell
python -X utf8 loopback_receiver.py --port 12346 --duration 10
```

Терминал 2:

```powershell
python -X utf8 -c "import sberboom_proto as p, time; s=p.UdpAudioSender('127.0.0.1',12346); [ (s.send(bytes([31<<3])+b'\x00'*40), time.sleep(0.02)) for _ in range(100) ]; s.close()"
```

Приёмник проверит монотонность `seq` и приращение `timestamp` на 960
сэмплов (20 мс Opus).

## Ограничения

* PIN-токен не даёт права на `StarCommand` — нужен backend-JWT.
* Скрипт не обходит аутентификацию и не содержит чужих токенов.
* Проверка на физическом динамике не выполнялась.
