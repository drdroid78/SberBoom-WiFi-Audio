# SberBoom Wi-Fi Audio — сводная матрица состояния на 28.11.2026

Автор и владелец проекта: **DrDroid78**. Целевое устройство — большой SberBoom, эталон SBDV-00090.

Эта таблица отделяет статические доказательства, локальные проверки кода и аппаратную приёмку. Успешная компиляция или unit-тест не означает, что колонка воспроизвела звук.

| Компонент | Что доказано / реализовано | Локальная проверка | Физическая приёмка | Текущий предел |
|---|---|---|---|---|
| Существующий проект | Android/Kotlin-проект продолжен в ветке `android-rc4`; оригиналы сохранены | Рабочее дерево и Git history доступны | Не требуется | Не выпускать как готовый release |
| Discovery | mDNS и ручной IP | Код и диагностика существуют | Не проверено на iQOO Neo 10 + SberBoom | Требуется проверка мультикаста и повторного поиска |
| TLS | Системная проверка или SHA-256 pin конкретной колонки | Код pinning и endpoint-bound AAD существует | Сертификат конкретной колонки не принимался | Изменение endpoint/pin требует нового сопряжения |
| PIN handshake | Из embedded descriptor Salute TV восстановлены точные request/response поля и status enum; RC4 имел неполный flow | Golden/regression tests добавлены; предыдущая ошибка импорта `assertArrayEquals` исправлена, новый CI pending | Не проверено на SberBoom | `PinConnect.Response.field 2` — session ID, не токен; токен выдаёт `ConfirmPinConnect.Response.field 2` |
| Обычные Cast-запросы | WebSocket/TLS, protobuf-wire envelopes, корреляция по `msg_id`, metadata/volume/media | Ранее JVM/Python tests; после изменений нужно повторить | Ответ GetMetaData без валидного PIN не подтверждён | Ответы SberBoom требуют проверки на устройстве |
| Backend JWT | Валидатор прошивки требует RS256 и актуальность; PIN-токен не заменяет JWT | Локально проверяются формат/срок, но не подпись/права | Нет | Легальный источник JWT для стороннего приложения не установлен |
| Opus/UDP | 48 kHz stereo, 20 ms, LE sequence + LE timestamp + Opus, FEC отключён | Golden vectors, длительность Opus, wrap и short packet tests | Нет подтверждённого приёма/звука | Сетевая отправка не доказывает декодирование колонкой |
| StarPTP | UDP/4391, Sync/DelayReq/DelayResp, offset/delay, loopback peer; интегрирован в StreamEngine | Pure JVM clock tests и два loopback socket; новый Android CI pending | Нет | estimate обязателен перед StartStreaming; CLOCK_MONOTONIC_RAW против публичного Android clock требует аппаратной проверки |
| StartStreaming | `settings=1`, `monoTimestamp=2`, повторяемый `syncedMasters=3`; master/sender = clientId; `now + 410 ms`; измеряемый offset | Golden vector и tests знакового offset/unit conversion; новый CI pending | Нет | StartStreaming не отправляется без свежего PTP estimate; ответ и звук колонки не подтверждены |
| Audio lifecycle | SessionGate, ACK/stop/keepalive protections, foreground service; PTP cleanup и liveness monitor | JVM policy tests; Android runtime не проверен | Нет | Пауза/возобновление локального UDP и reconnect требуют доработки |
| Стереопара | Найден штатный Salyut flow через backend virtual device, directive и GetState polling | Статические доказательства | Нет двух принятых устройств | Полный авторизованный cloud contract и teardown не установлены |
| Мультирум | Metadata/group fields и `MEDIA_START_MULTIROOM` | Parsing/wire tests | Нет | Кнопка команды не является реализацией группы |
| APK/CI | Существует debug APK RC4 и Actions reports | Предыдущие JVM/Python отчёты | APK не установлен | Требуется новая CI-сборка после исправлений; подписанного release APK нет |
| Аппаратная приёмка | Целевой сценарий сформулирован | Чек-лист будет в инструкции | Не выполнена | ADB/SDK и подключённое устройство в этой среде недоступны |

## Сохранённые материалы

- Проект: `drdroid78/SberBoom-WiFi-Audio`, ветки `main`, `android-rc4`, `diagnostics/runner-preflight`, `ptp-peer-prototype`.
- Debug APK RC4: `artifacts/rc4/apk/SberBoom-WiFi-Audio-rc4-debug.apk`, SHA-256 `b9d9128d984f02e869b06275475e7e782bd380616ee40de24063ddf58efb4a79`.
- Ghidra reports: server, audio, DeviceGroup и UDP; сохранены без изменения Ghidra 12.2/cache.
- Salute TV `libstardroid.so`, SHA-256 `991c2a802bfaa7157df50f979009d43c6f175e3150f1705511c271e3553e6440`. Это не дамп SberBoom.
- Существующий `base.zip` и Salyut APK/DEX найдены по прежним путям; повторно не загружались и не распаковывались.
- REA-Core: доступна актуальная локальная ревизия `e5910df5`; полный checkout `morluto/rea` отсутствует.

## Не найдено / недоступно

- `SberBoom_CHECKPOINT_2026-11-28.zip` и SberBoom-specific `CHECKPOINT.md` не найдены.
- Подписанный release APK не найден; доступен только debug APK.
- Полного локального checkout `Ghidra-Cloud-private`, `morluto/rea` и чистого JADX dump нет.
- Закрытые/удалённые GitHub-объекты не проверялись.
- Физический SberBoom и ADB peer-устройство в текущей среде недоступны.

## Критерий завершения

Проект можно назвать завершённым Wi-Fi-аудиосборкой только после реального приёма и слышимого воспроизведения на целевом SberBoom с разрешённой авторизацией, а также проверки stop/pause/resume, длительной работы, потери Wi-Fi, стереопары и мультирума. До этого APK является проверяемой диагностической/разработческой сборкой, а не доказательством рабочего звука.
