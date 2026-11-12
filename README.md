# LazyTV

Приложение для Android TV на Kotlin (Views + ExoPlayer). Каталог фильмов и сериалов из TMDB, закладки и
списки, видеосервисы, поиск, торренты через TorrServe, встроенный плеер. Ряды «Популярное», «Закладки» и
«Смотреть далее» выводятся на главный экран телевизора.

- Скачать: [релизы на GitHub](https://github.com/ko22009/ltv/releases/latest) (файл APK), сайт [tv.sdkcache.ru](https://tv.sdkcache.ru)
- Поддержать проект: [tv.sdkcache.ru/donate](https://tv.sdkcache.ru/donate) или [Boosty](https://boosty.to/ko22009)
- Политика конфиденциальности: [tv.sdkcache.ru/policy](https://tv.sdkcache.ru/policy)

Приложение распространяется готовым APK через релизы на GitHub, в Google Play не публикуется.
Подробности: [docs/release.md](docs/release.md). `minSdk` 21, `targetSdk` 36, версия 1.0.0.

## Что нужно

- JDK 17 и Gradle Wrapper из проекта (Gradle 8.13).
- Android SDK (платформа 36, platform-tools).
- Ключ TMDB. Его читает `app/build.gradle.kts` из `local.properties` в корне проекта:

```properties
sdk.dir=C\:\\Android\\sdk
TMDB_KEY=ваш_ключ_tmdb
```

`local.properties` в git не попадает.

## Сборка и запуск

```powershell
$env:JAVA_HOME = '<папка JDK 17>'
.\gradlew.bat assembleDebug          # разработка, DebugSeed
.\tools\release.ps1 -Version 1.0.0   # релиз в dist
```

APK для разработки: `app/build/outputs/apk/debug/app-debug.apk`. Релизные файлы:
`dist/LazyTV-1.0.0.apk` и `dist/SHA256SUMS.txt`.

Установка и запуск (адрес устройства смотреть в `adb devices`):

```bash
adb -s <устройство> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <устройство> shell am start -n ru.lazytv/.MainActivity --ez restore true
```

Ключ `restore` возвращает на экран, который был открыт до обновления.

## Поддержка проекта

Страница для донатов: [tv.sdkcache.ru/donate](https://tv.sdkcache.ru/donate) (разовый донат через ЮMoney) и
[Boosty](https://boosty.to/ko22009). В настройках приложения пункт «Поддержать проект» открывает страницу на
телефоне, а на ТВ показывает QR-код и ссылку. Адрес задан в `SUPPORT_URL` в `MainFragment.kt`.

## Устройство проекта

Весь код лежит в `app/src/main/java/ru/lazytv`.

| Область | Файлы |
|---|---|
| Главный экран, каталог, фильтры | `MainFragment.kt`, `CatalogPage.kt`, `CatalogFilter.kt`, `FiltersActivity.kt` |
| Карточка фильма, серии | `DetailsActivity.kt`, `EpisodesActivity.kt`, `PersonActivity.kt` |
| Плеер | `PlayerActivity.kt`, `Playback.kt` |
| Данные TMDB | `Tmdb.kt`, `Cinema.kt` |
| Видеосервисы и источники | `Sources.kt`, `Rezka.kt`, `Kinovibe.kt`, `RemoteConfig.kt` |
| Торренты | `Torrents*.kt`, `TorrentSources.kt`, `TorrentPrefetch.kt`, `FavoriteTorrents.kt` |
| Закладки, история, отметки | `Favorites.kt`, `Watched.kt`, `EpisodeState.kt`, `Backup.kt` |
| Ряды главного экрана системы | `Recommendations.kt`, `RecommendationsReceiver.kt` |
| Общие детали интерфейса | `Cards.kt`, `MenuIcons.kt`, `Popup.kt`, `Loading.kt` |
| Настройки | `Settings.kt`, `SettingsActivity.kt` |
| Релиз (версия, файл, сумма) | `app/build.gradle.kts`, `tools/release.ps1`, `dist/release-notes.md` |

## Документы

- [docs/workflow.md](docs/workflow.md): git, сборка, пробные данные `DebugSeed`.
- [docs/release.md](docs/release.md): версия, имя файла, выпуск через релизы GitHub, подпись, сайт.
- [docs/design-system.md](docs/design-system.md): карточки, плитки, кнопки, иконки, сетки. Новый экран начинать отсюда.
- [docs/remote-config.md](docs/remote-config.md): адреса источников из JSON-конфига вместо зашитых в код.
- [docs/fixit.md](docs/fixit.md): известные проблемы.
- [AGENTS.md](AGENTS.md): памятка для людей и агентов (правила коммитов, интерфейса, работы в Windows).

## Правила в двух строках

- Один законченный коммит на изменение, сначала сборка. Заголовок `область: что сделано`.
- Интерфейс только из дизайн-системы; управление пультом проверять в эмуляторе.
