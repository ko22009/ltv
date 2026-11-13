# LazyTV

@AGENTS.md

Выше правила проекта (сборка, git, дизайн-система, пульт). Ниже только то, чего нет в коде и что ломалось раньше.

## Проверка перед «готово»

- Сборка: `.\gradlew.bat assembleDebug` (нужен `JAVA_HOME`, JDK 17). Ошибки: `grep -E "^e:|FAILED"`.
- Вид экрана считать по коду и `docs/design-system.md`. Скриншоты только по просьбе владельца или одним обзорным прогоном в конце задачи, не сериями.
- Правка отступа, цвета, радиуса, шрифта: `Grep` по всем таким же местам, ТВ и телефон, активный и неактивный вид. В ответе перечислить найденное.

## Ловушки

- `adb` нет в PATH: Git Bash `/c/Android/sdk/platform-tools/adb.exe`, PowerShell `C:\Android\sdk\platform-tools\adb.exe`. Адреса устройств в `CLAUDE.local.md`.
- Приставки только по просьбе и на одно названное устройство; не назвал, спросить. Эмуляторы без вопроса.
- `adb shell` в Git Bash портит `/sdcard/...`: писать `//sdcard/...` или `MSYS_NO_PATHCONV=1`. Аргументы с пробелами и кириллицей одной строкой в кавычках.
- `adb push` в `/data/local/tmp` может падать (`fchown`); в приложение: `adb shell "run-as <пакет> sh -c 'cat > файл'" < файл`.
- Закрытые (`exported=false`) activity через `am start` не открываются; после `adb install` открывать клавишами или `--ez restore true`.
- Эмулятор Android TV: Back (`keyevent 4`) закрывает приложение; после клавиши ждать 3-4 с.
- Скриншот: `adb exec-out screencap -p > файл` только из Git Bash, не из PowerShell (портит PNG). Python читает путь `C:/...`.
- Dump фокуса и границ: `adb shell uiautomator dump`, расстояния мерить по ним, а не на глаз.
- В PowerShell `git`/`gradle | Select-Object -First N` даёт код 255: использовать `Select-String` и проверять `$LASTEXITCODE`.
- Ожидание: `run_in_background` или Monitor, не `sleep N; команда`.
- Строки с `\` и `\n` в коде править только через Edit/Write, не `sed` и не строки Python.

## Git

- Коммитить готовое в `main` небольшими шагами без вопроса, `область: что сделано`, без `Co-Authored-By`. Push, удаление, публикация только после явного «да».
