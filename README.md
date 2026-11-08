# LazyTV

Приложение для Android TV на Kotlin (Leanback + ExoPlayer). Каталог фильмов и сериалов из TMDB, закладки и
списки, видеосервисы, поиск, торренты через TorrServe, встроенный плеер. Ряды «Популярное», «Закладки» и
«Смотреть далее» выводятся на главный экран телевизора.

`applicationId`: `ru.lazytv`, `minSdk` 21, `targetSdk` 34.

## Что нужно

- JDK 17 и Gradle 8.7 (подойдёт и Gradle wrapper, если добавите его).
- Android SDK (платформа 34, platform-tools).
- Ключ TMDB. Его читает `app/build.gradle.kts` из `local.properties` в корне проекта:

```properties
sdk.dir=C\:\\Android\\sdk
TMDB_KEY=ваш_ключ_tmdb
```

`local.properties` в git не попадает.

## Сборка и запуск

```powershell
$env:JAVA_HOME='<JDK 17>'
& '<Android SDK>\gradle-8.7\bin\gradle.bat' -p <проект> assembleDebug     # разработка, DebugSeed
& '<Android SDK>\gradle-8.7\bin\gradle.bat' -p <проект> assembleRelease   # приставка, R8, без отладки
```

APK: `app/build/outputs/apk/debug/app-debug.apk` и `app/build/outputs/apk/release/app-release.apk`.

Установка и запуск (эмулятор `emulator-5554`, для приставки подставить её адрес):

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n ru.lazytv/.MainActivity --ez restore true
```

Ключ `restore` возвращает на экран, который был открыт до обновления.

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

## Документы

- [docs/workflow.md](docs/workflow.md): git, сборка, пробные данные `DebugSeed`.
- [docs/design-system.md](docs/design-system.md): карточки, плитки, кнопки, иконки, сетки. Новый экран начинать отсюда.
- [docs/remote-config.md](docs/remote-config.md): адреса источников из JSON-конфига вместо зашитых в код.
- [docs/fixit.md](docs/fixit.md): известные проблемы.
- [AGENTS.md](AGENTS.md): памятка для людей и агентов (правила коммитов, интерфейса, работы в Windows).

## Правила в двух строках

- Один законченный коммит на изменение, сначала сборка. Заголовок `область: что сделано`.
- Интерфейс только из дизайн-системы; управление пультом проверять в эмуляторе.
