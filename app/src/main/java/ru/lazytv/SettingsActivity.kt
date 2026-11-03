package ru.lazytv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * Settings screens: one section per screen, opened from a card on the settings page. The options of the section
 * follow each other, a choice shows its values as rows under it, and a change is saved at once — screens pick it up
 * when shown.
 */
class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val section = intent.getStringExtra("section").orEmpty()
            val screen = SettingsScreen().apply {
                arguments = Bundle().apply { putString("section", section) }
            }
            GuidedStepSupportFragment.addAsRoot(this, screen, android.R.id.content)
        }
    }
}

class SettingsScreen : GuidedStepSupportFragment() {
    /** Value rows of choices: row id → setting key (the description shows the chosen value). */
    private val valueRows = HashMap<Long, String>()

    /** Option rows: row id → (setting key, value); and the reverse map to uncheck the rest of a group. */
    private val optionRows = HashMap<Long, Pair<String, String>>()
    private val groupRows = HashMap<String, MutableList<Long>>()
    private val checkRows = HashMap<Long, String>()
    private val editRows = HashMap<Long, String>()
    private val buttons = HashMap<Long, () -> Unit>()
    private val sectionIds = HashMap<String, Long>()
    private var nextId = 1L

    /** Request codes of the two file dialogs: a backup is written to the picked file, or read from it. */
    private val requestSave = 7001
    private val requestLoad = 7002

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val section = arguments?.getString("section").orEmpty()
        val title = section.ifEmpty { "Настройки" }
        return GuidanceStylist.Guidance(title, "Изменения применяются сразу", if (section.isEmpty()) "LazyTV" else "Настройки", null)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post { focusFirstRow(view) }
    }

    private fun focusFirstRow(view: android.view.View) {
        val list = findList(view) ?: return
        if (list.focusedChild != null) return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.isFocusable) {
                child.requestFocus()
                return
            }
        }
    }

    private fun findList(view: android.view.View): androidx.recyclerview.widget.RecyclerView? {
        if (view is androidx.recyclerview.widget.RecyclerView) return view
        if (view !is android.view.ViewGroup) return null
        for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun newId() = nextId++

    private fun section(actions: MutableList<GuidedAction>, title: String) {
        val id = newId()
        sectionIds[title] = id
        actions.add(GuidedAction.Builder(requireContext()).id(id).title(title.uppercase())
            .infoOnly(true).focusable(false).build())
    }

    private fun check(actions: MutableList<GuidedAction>, title: String, description: String, key: String, default: Boolean = true) {
        val id = newId()
        checkRows[id] = key
        actions.add(GuidedAction.Builder(requireContext()).id(id).title(title).description(description)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
            .checked(Settings.bool(requireContext(), key, default)).build())
    }

    private fun choice(
        actions: MutableList<GuidedAction>, title: String, key: String, default: String, options: List<Pair<String, String>>,
    ) {
        val current = Settings.string(requireContext(), key, default)
        val valueId = newId()
        valueRows[valueId] = key
        actions.add(GuidedAction.Builder(requireContext()).id(valueId).title(title)
            .description(options.firstOrNull { it.first == current }?.second ?: current)
            .infoOnly(true).focusable(false).build())
        options.forEach { (value, label) ->
            val id = newId()
            optionRows[id] = key to value
            groupRows.getOrPut(key) { mutableListOf() }.add(id)
            actions.add(GuidedAction.Builder(requireContext()).id(id).title(label).checked(value == current).build())
        }
    }

    private fun editable(actions: MutableList<GuidedAction>, title: String, key: String, default: String) {
        val id = newId()
        editRows[id] = key
        val value = Settings.string(requireContext(), key, default)
        actions.add(GuidedAction.Builder(requireContext()).id(id).title(title)
            .description(value.ifEmpty { default })
            .editDescription(value)
            .descriptionEditable(true).build())
    }

    private fun info(actions: MutableList<GuidedAction>, title: String, description: String) {
        actions.add(GuidedAction.Builder(requireContext()).id(newId()).title(title).description(description)
            .infoOnly(true).focusable(false).build())
    }

    private fun button(actions: MutableList<GuidedAction>, title: String, description: String = "", action: () -> Unit) {
        val id = newId()
        buttons[id] = action
        actions.add(GuidedAction.Builder(requireContext()).id(id).title(title).description(description).build())
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        nextId = 1L
        valueRows.clear(); optionRows.clear(); groupRows.clear(); checkRows.clear(); editRows.clear(); buttons.clear()
        val ctx = requireContext()

        section(actions, "Списки")
        Tmdb.categories.forEach { category ->
            check(actions, category.title, "Список в левом меню", Tmdb.listKey(category), category.onByDefault)
        }

        section(actions, "Источники видео")
        Sources.all.forEach { source -> check(actions, source.name, "Сайт", Settings.service(source.id)) }
        check(actions, "Торренты", "Раздачи для TorrServe", "source_torrents")
        check(actions, "Кэшировать найденные источники", "Не искать заново один и тот же фильм 6 часов", Settings.CACHE_SOURCES)

        section(actions, "Адреса источников")
        editable(actions, "Конфиг с адресами (URL)", Settings.REMOTE_URL, "")
        info(actions, "Сейчас", RemoteConfig.status(ctx))
        button(actions, "Обновить адреса из конфига", "Скачать JSON и применить сразу") { refreshRemote() }
        button(actions, "Сбросить свои адреса", "Вернуть адреса из конфига или встроенные") {
            RemoteConfig.resetManual(ctx)
            toast("Свои адреса сброшены")
            requireActivity().recreate()
        }
        Sources.all.forEach { source ->
            editable(actions, "${source.name}: свой адрес", Settings.baseKey(source.id), RemoteConfig.base(source.id))
        }

        section(actions, "Воспроизведение")
        choice(actions, "Плеер", Settings.PLAYER, "internal",
            listOf("internal" to "Встроенный", "external" to "Внешний (системный выбор)"))
        choice(actions, "Качество по умолчанию", Settings.QUALITY, "best",
            listOf("ask" to "Спрашивать", "best" to "Лучшее", "1080" to "1080p", "720" to "720p", "480" to "480p"))
        choice(actions, "Озвучка по умолчанию", Settings.VOICE, "any",
            listOf("any" to "Любая", "ru" to "Русская", "original" to "Оригинал с субтитрами"))
        choice(actions, "Что предлагать первым", Settings.SOURCE_MODE, "sites", listOf("sites" to "Сайты", "torrents" to "Торренты"))
        check(actions, "Запоминать позицию", "Продолжать с места остановки", Settings.REMEMBER_POSITION)
        check(actions, "Следующая серия сама", "После окончания включать следующую серию из списка", Settings.AUTO_NEXT)

        section(actions, "Торренты")
        choice(actions, "Открывать раздачи", Settings.TORRENT_CLIENT, "internal",
            listOf("internal" to "Через TorrServe (адрес ниже)", "external" to "Сторонним приложением (системный выбор)"))
        editable(actions, "Адрес TorrServe", Settings.TORRSERVE, "127.0.0.1:8090")
        choice(actions, "Минимум сидов в списке раздач", Settings.TORRENT_MIN_SEEDS, "0",
            Settings.MIN_SEEDS.map { it.toString() to if (it == 0) "Любое количество" else "От $it" })
        check(actions, "Экспериментальный Searchtor", "Обычный запрос без обхода проверки; если сервер пустит — покажет раздачи", Settings.SEARCHTOR, false)
        editable(actions, "Серверы JacRed (пусто — из конфига)", Settings.JACRED_SERVERS, "")
        editable(actions, "Свой Jackett: адрес", Settings.JACKETT_URL, "")
        editable(actions, "Свой Jackett: API-ключ", Settings.JACKETT_KEY, "")
        button(actions, "Проверить Jackett", "Запрос к своему Jackett с этими адресом и ключом") { checkJackett() }
        info(actions, "TorrServe — отдельная программа", "LazyTV только просит её скачать раздачу")
        button(actions, "Скачать TorrServe", "Страница загрузки в браузере") { downloadTorrServe() }

        section(actions, "Каталог")
        choice(actions, "Язык названий и описаний", Settings.LANGUAGE, "ru-RU",
            listOf("ru-RU" to "Русский", "en-US" to "English", "uk-UA" to "Українська"))
        choice(actions, "Размер карточек", Settings.CARD_SIZE, "normal",
            listOf("small" to "Мелкие", "normal" to "Обычные", "large" to "Крупные"))
        check(actions, "Строка «Популярное» на главном экране TV",
            "Рекомендации в ленте Android TV (если лаунчер их показывает)", Settings.RECOMMENDATIONS)

        section(actions, "Сеть")
        check(actions, "Обход блокировок", "Сначала напрямую, затем встроенный обход (ByeDPI)", Settings.AUTO_BYPASS)
        check(actions, "Прокси для сайтов", "Запасной путь, если встроенный обход не помог", Settings.APP_PROXY)
        check(actions, "Прокси для торрентов", "То же для поиска раздач (rutor)", Settings.APP_PROXY_TORRENTS)
        button(actions, "Подобрать приём обхода", "Перебирает приёмы ByeDPI, запоминает рабочий для этой сети") {
            com.lmd.dpi.DpiBypass.retune()
            toast("Подбор запущен, занимает до минуты")
        }

        section(actions, "Данные")
        button(actions, "Сохранить копию в файл", "Закладки, история, отметки, настройки") { exportBackup() }
        button(actions, "Восстановить из файла", "Заменит закладки, историю, отметки и настройки") { importBackup() }
        button(actions, "Очистить кэш картинок") {
            Thread { com.bumptech.glide.Glide.get(ctx.applicationContext).clearDiskCache() }.start()
        }
        button(actions, "Очистить кэш найденных источников") { ctx.getSharedPreferences("cache", 0).edit().clear().apply() }
        button(actions, "Очистить историю просмотров") { History.clear(ctx) }
        button(actions, "Сбросить все настройки") {
            ctx.getSharedPreferences("lazytv", 0).edit().clear().apply()
            requireActivity().finish()
        }

        section(actions, "О приложении")
        info(actions, "Версия", BuildConfig.VERSION_NAME)

        // one section per screen: the cards on the settings page open their own screen, not one long list
        val title = arguments?.getString("section").orEmpty().ifEmpty { "Списки" }
        val start = sectionIds[title] ?: return
        val all = actions.toList()
        val headers = sectionIds.values.toSet()
        val from = all.indexOfFirst { it.id == start }
        if (from < 0) return
        val rest = all.drop(from + 1)
        val end = rest.indexOfFirst { it.id in headers }
        actions.clear()
        actions.addAll(if (end < 0) rest else rest.take(end))
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val id = action.id
        checkRows[id]?.let { key ->
            Settings.setBool(requireContext(), key, action.isChecked)
            onCheck(key, action.isChecked)
            return
        }
        optionRows[id]?.let { (key, value) ->
            Settings.setString(requireContext(), key, value)
            groupRows[key]?.forEach { row ->
                findActionById(row)?.isChecked = row == id
                notifyActionChanged(findActionPositionById(row))
            }
            valueRows.entries.firstOrNull { it.value == key }?.key?.let { valueId ->
                findActionById(valueId)?.description = action.title
                notifyActionChanged(findActionPositionById(valueId))
            }
            return
        }
        buttons[id]?.invoke()
    }

    /** Side effects of the switches that do something at once, not only when a screen reads them. */
    private fun onCheck(key: String, on: Boolean) {
        val ctx = requireContext()
        when (key) {
            Settings.RECOMMENDATIONS -> if (on) Thread { runCatching { Recommendations.refresh(ctx) } }.start()
            Settings.AUTO_BYPASS -> if (on) com.lmd.dpi.DpiBypass.start(ctx.applicationInfo.nativeLibraryDir)
            Settings.APP_PROXY -> com.lmd.dpi.DpiBypass.appProxyAllowed = on
            Settings.APP_PROXY_TORRENTS -> com.lmd.dpi.DpiBypass.appProxyTorrentsAllowed = on
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val key = editRows[action.id] ?: return GuidedAction.ACTION_ID_CURRENT
        val ctx = requireContext()
        val value = action.description.toString().trim()
        when (key) {
            Settings.TORRSERVE -> {
                val address = value.ifEmpty { "127.0.0.1:8090" }
                Settings.setString(ctx, key, address)
                action.description = address
            }
            Settings.JACRED_SERVERS -> {
                Settings.setString(ctx, key, value)
                action.description = value.ifEmpty { "из конфига или встроенные" }
            }
            Settings.JACKETT_URL -> {
                val url = value.trimEnd('/').let { if (it.isEmpty() || it.startsWith("http")) it else "http://$it" }
                Settings.setString(ctx, key, url)
                action.description = url.ifEmpty { "не задан" }
            }
            else -> {
                Settings.setString(ctx, key, value)
                action.description = value.ifEmpty { "не задан" }
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    /** Downloads the addresses file and redraws the screen, so the new "Сейчас" line is visible at once. */
    private fun refreshRemote() {
        val ctx = requireContext()
        if (RemoteConfig.configured(ctx).isEmpty()) {
            toast("Сначала задайте адрес конфига")
            return
        }
        toast("Скачиваем конфиг…")
        Thread {
            val result = runCatching { RemoteConfig.refresh(ctx) }
            activity?.runOnUiThread {
                result.onSuccess { toast(it) }.onFailure { toast("Конфиг не скачался: ${it.message}") }
                activity?.recreate()
            }
        }.start()
    }

    /**
     * A copy of the data in a file the user picks. A TV box may have no file picker at all: then the copy goes to
     * the app's own folder and the path is shown, which is enough to pull it with adb.
     */
    private fun exportBackup() {
        val ctx = requireContext()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, Backup.backupName())
        }
        try {
            startActivityForResult(intent, requestSave)
        } catch (e: android.content.ActivityNotFoundException) {
            runCatching { Backup.writeDefault(ctx, Backup.toJson(ctx)) }
                .onSuccess { toast("Файл выбора не найден, копия: ${it.absolutePath}") }
                .onFailure { toast("Не удалось сохранить: ${it.message}") }
        }
    }

    /** Restores from a picked file; without a file picker the newest copy from the app's folder is taken. */
    private fun importBackup() {
        val ctx = requireContext()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try {
            startActivityForResult(intent, requestLoad)
        } catch (e: android.content.ActivityNotFoundException) {
            val file = Backup.latestDefault(ctx)
            if (file == null) {
                toast("Копий в папке приложения нет")
                return
            }
            runCatching { Backup.restore(ctx, file.readText()) }
                .onSuccess { toast("Восстановлено: $it"); activity?.recreate() }
                .onFailure { toast("Не удалось восстановить: ${it.message}") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = requireContext()
        when (requestCode) {
            requestSave -> runCatching { Backup.write(ctx, uri, Backup.toJson(ctx)) }
                .onSuccess { toast("Копия сохранена") }
                .onFailure { toast("Не удалось сохранить: ${it.message}") }
            requestLoad -> runCatching { Backup.restore(ctx, Backup.read(ctx, uri)) }
                .onSuccess { toast("Восстановлено: $it"); activity?.recreate() }
                .onFailure { toast("Не удалось восстановить: ${it.message}") }
        }
    }

    private fun checkJackett() {
        val ctx = requireContext()
        val url = Settings.string(ctx, Settings.JACKETT_URL, "")
        val key = Settings.string(ctx, Settings.JACKETT_KEY, "")
        if (url.isEmpty() || key.isEmpty()) {
            toast("Сначала задайте адрес и ключ Jackett")
            return
        }
        toast("Проверяем Jackett…")
        Thread {
            val result = runCatching { ExtraTorrents.jackettCount(ctx, "матрица") }
            requireActivity().runOnUiThread {
                result.onSuccess { toast("Jackett отвечает, найдено раздач: $it") }
                    .onFailure { toast("Jackett не ответил: ${it.message}") }
            }
        }.start()
    }

    private fun downloadTorrServe() = Links.open(requireActivity(), TorrServe.DOWNLOAD)

    private fun toast(message: String) = android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
}
