package ru.lazytv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist

/**
 * Settings screens: one section per screen, opened from a card on the settings page. Choices open as compact
 * dropdowns, and the Help section uses a full-width card page so multi-line answers fit without clipping.
 */
class SettingsActivity : FragmentActivity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra("section").orEmpty()
        if (section == "Помощь") {
            buildHelpScreen()
            return
        }
        if (savedInstanceState == null) {
            val screen = SettingsScreen().apply {
                arguments = Bundle().apply { putString("section", section) }
            }
            GuidedStepSupportFragment.addAsRoot(this, screen, android.R.id.content)
        }
    }

    private fun buildHelpScreen() {
        val root = FrameLayout(this).apply { background = calmBackground() }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(64), px(36), px(64), px(24))
        }
        val back = backButton(this, scale) { finish() }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
            addView(TextView(this@SettingsActivity).apply {
                text = "Помощь"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f * scale)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
        }
        page.addView(header)

        val retuneBtn = chipButton(this, scale, "Подобрать приём обхода").apply {
            setOnClickListener {
                com.lmd.dpi.DpiBypass.retune()
                android.widget.Toast.makeText(this@SettingsActivity, "Подбор запущен, занимает до минуты", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val dateBtn = chipButton(this, scale, "Настройки даты и времени").apply {
            setOnClickListener { Errors.openDateSettings(this@SettingsActivity) }
        }
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(retuneBtn, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { rightMargin = px(12) })
            addView(dateBtn, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)))
        }
        page.addView(actionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16); bottomMargin = px(14) })

        val topics = listOf(
            "Сайт не открывается" to
                "Включите обход блокировок в разделе «Сеть» или нажмите «Подобрать приём обхода» выше. Если показ закрыт для вашего региона, используйте торренты.",
            "Видео не запускается или нет звука" to
                "Если встроенный плеер не поддерживает кодек раздачи, выберите внешний плеер в разделе «Воспроизведение» или удерживайте OK на серии для выбора другого качества.",
            "Торренты и TorrServe" to
                "LazyTV не скачивает торренты сам: нужна программа TorrServe на приставке или другом устройстве в домашней сети. Выбирайте раздачи с большим числом сидов (▲).",
            "Управление плеером, звук и субтитры" to
                "При скрытой панели стрелки влево и вправо перематывают ролик на 10 секунд. Стрелки вверх, вниз или кнопка OK открывают нижнюю панель с выбором звуковой дорожки, субтитров, качества и скорости.",
            "Дата и время на приставке" to
                "Если после отключения питания на приставке сбились часы, защищённые сайты (HTTPS) перестают открываться. Проверьте системные часы кнопкой «Настройки даты и времени» выше.",
            "Старые приставки (Android 6 и ниже)" to
                "На старых прошивках истекли системные сертификаты безопасности: часть HTTPS-сайтов не открывается без обновления сертификатов системы.",
        )

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        val cardsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cards = mutableListOf<View>()
        topics.forEachIndexed { index, (topicTitle, body) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(22), px(14), px(22), px(14))
                background = focusShape(scale, 0x33000000, radius = 6f)
                isFocusable = true
                addView(TextView(this@SettingsActivity).apply {
                    text = topicTitle
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = body
                    setTextColor(0xFFCBD5E1.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale)
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, px(6), 0, 0)
                })
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (index == 0) {
                                scroll.smoothScrollTo(0, 0)
                                retuneBtn.requestFocus()
                            } else cards[index - 1].requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            cards.getOrNull(index + 1)?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                        else -> false
                    }
                }
            }
            cards.add(card)
            cardsList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
        }
        scroll.addView(cardsList)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        back.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> retuneBtn.requestFocus()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        val topButtons = listOf(retuneBtn, dateBtn)
        topButtons.forEachIndexed { i, btn ->
            btn.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val step = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        topButtons.getOrNull(i + step)?.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> back.requestFocus()
                    KeyEvent.KEYCODE_DPAD_DOWN -> cards.firstOrNull()?.requestFocus() ?: true
                    else -> false
                }
            }
        }
        setContentView(root)
        // the back button first, as on the other nested screens: the cards are one step down
        back.requestFocus()
    }
}

class SettingsScreen : GuidedStepSupportFragment() {
    /** Sub-option rows inside dropdown choices: sub-action id → (parent row id, setting key, value). */
    private val subOptionRows = HashMap<Long, Triple<Long, String, String>>()
    private val checkRows = HashMap<Long, String>()
    private val editRows = HashMap<Long, String>()
    private val buttons = HashMap<Long, () -> Unit>()
    private val sectionIds = HashMap<String, Long>()
    private var nextId = 1L
    private var nextCheckSetId = 10

    /** Request codes of the two file dialogs: a backup is written to the picked file, or read from it. */
    private val requestSave = 7001
    private val requestLoad = 7002

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val section = arguments?.getString("section").orEmpty()
        val title = section.ifEmpty { "Настройки" }
        val subtitle = if (title == "О приложении") "Сведения о программе" else "Изменения применяются сразу"
        return GuidanceStylist.Guidance(title, subtitle, if (section.isEmpty()) "LazyTV" else "Настройки", null)
    }

    override fun onCreateActionsStylist(): GuidedActionsStylist = object : GuidedActionsStylist() {
        override fun onBindViewHolder(vh: ViewHolder, action: GuidedAction) {
            super.onBindViewHolder(vh, action)
            vh.descriptionView?.apply {
                maxLines = 10
                isVerticalScrollBarEnabled = false
            }
        }
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
        val ctx = requireContext()
        val current = Settings.string(ctx, key, default)
        val parentId = newId()
        val setId = nextCheckSetId++
        val subs = options.map { (value, label) ->
            val subId = newId()
            subOptionRows[subId] = Triple(parentId, key, value)
            GuidedAction.Builder(ctx).id(subId).title(label).checkSetId(setId).checked(value == current).build()
        }
        actions.add(
            GuidedAction.Builder(ctx).id(parentId).title(title)
                .description(options.firstOrNull { it.first == current }?.second ?: current)
                .subActions(subs)
                .build()
        )
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
        nextCheckSetId = 10
        subOptionRows.clear(); checkRows.clear(); editRows.clear(); buttons.clear()
        val ctx = requireContext()

        section(actions, "Источники видео")
        Sources.all.forEach { source -> check(actions, source.name, if (RemoteConfig.isDown(source.id)) "Сайт сейчас не отвечает" else "Сайт", Settings.service(source.id)) }
        button(actions, "Проверить источники", "Время ответа и путь к каждому сайту: напрямую, обход или прокси") { checkSources() }
        check(actions, "Торренты", "Раздачи для TorrServe", "source_torrents")
        check(actions, "Кэшировать найденные источники", "Не искать заново один и тот же фильм 6 часов", Settings.CACHE_SOURCES)

        section(actions, "Адреса сайтов")
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

        section(actions, "Торренты и адреса")
        choice(actions, "Открывать раздачи", Settings.TORRENT_CLIENT, "internal",
            listOf("internal" to "Через TorrServe (адрес ниже)", "external" to "Сторонним приложением (системный выбор)"))
        editable(actions, "Адрес TorrServe", Settings.TORRSERVE, "127.0.0.1:8090")
        choice(actions, "Минимум сидов в списке раздач", Settings.TORRENT_MIN_SEEDS, "0",
            Settings.MIN_SEEDS.map { it.toString() to if (it == 0) "Любое количество" else "От $it" })
        editable(actions, "Серверы JacRed (пусто: из конфига)", Settings.JACRED_SERVERS, "")
        editable(actions, "Свой Jackett: адрес", Settings.JACKETT_URL, "")
        editable(actions, "Свой Jackett: API-ключ", Settings.JACKETT_KEY, "")
        button(actions, "Проверить Jackett", "Запрос к своему Jackett с этими адресом и ключом") { checkJackett() }
        info(actions, "TorrServe (отдельная программа)", "LazyTV только просит её скачать раздачу")
        button(actions, "Скачать TorrServe", "Страница загрузки в браузере") { downloadTorrServe() }

        section(actions, "Каталог")
        choice(actions, "Язык названий и описаний", Settings.LANGUAGE, "ru-RU",
            listOf("ru-RU" to "Русский", "en-US" to "English", "uk-UA" to "Українська"))
        check(actions, "Строка «Популярное» на главном экране TV",
            "Рекомендации в ленте Android TV (если лаунчер их показывает)", Settings.RECOMMENDATIONS)

        section(actions, "Сеть")
        if (ClockCheck.skewed) {
            info(actions, "Часы на устройстве сбились", ClockCheck.MESSAGE)
            button(actions, "Открыть настройки даты и времени", "Системные часы устройства") { Errors.openDateSettings(ctx) }
        }
        check(actions, "Обход блокировок", "Сначала напрямую, затем встроенный обход (ByeDPI)", Settings.AUTO_BYPASS)
        check(actions, "Прокси для сайтов", "Запасной путь, если встроенный обход не помог", Settings.APP_PROXY)
        check(actions, "Прокси для торрентов", "То же для поиска раздач (rutor)", Settings.APP_PROXY_TORRENTS)
        button(actions, "Подобрать приём обхода", "Перебирает приёмы ByeDPI, запоминает рабочий для этой сети") {
            com.lmd.dpi.DpiBypass.retune()
            toast("Подбор запущен, занимает до минуты")
        }

        section(actions, "Данные")
        info(actions, "Резервная копия", Backup.status(ctx))
        button(actions, "Сохранить копию в файл", "Закладки, история, отметки, настройки") { exportBackup() }
        button(actions, "Восстановить из файла", "Заменит закладки, историю, отметки и настройки") { importBackup() }
        button(actions, "Очистить кэш картинок") {
            clearFailedPosters()
            com.bumptech.glide.Glide.get(ctx.applicationContext).clearMemory()
            Thread { com.bumptech.glide.Glide.get(ctx.applicationContext).clearDiskCache() }.start()
            toast("Кэш картинок очищен")
        }
        button(actions, "Очистить кэш найденных источников") {
            ctx.getSharedPreferences("cache", 0).edit().clear().apply()
            toast("Кэш источников очищен")
        }
        button(actions, "Очистить историю просмотров") {
            android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Очистить историю просмотров?")
                .setMessage("Список недавно открытых фильмов и сериалов будет удалён.")
                .setPositiveButton("Очистить") { _, _ ->
                    History.clear(ctx)
                    toast("История просмотров очищена")
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        button(actions, "Сбросить все настройки") {
            android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Сбросить все настройки?")
                .setMessage("Закладки, история и отметки просмотра сохранятся.")
                .setPositiveButton("Сбросить") { _, _ ->
                    Settings.resetSettingsOnly(ctx)
                    com.lmd.dpi.DpiBypass.enabled = true
                    com.lmd.dpi.DpiBypass.appProxyAllowed = true
                    com.lmd.dpi.DpiBypass.appProxyTorrentsAllowed = true
                    com.lmd.dpi.DpiBypass.clearCache()
                    com.lmd.dpi.DpiBypass.start(ctx.applicationInfo.nativeLibraryDir)
                    toast("Настройки сброшены")
                    activity?.finish()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        section(actions, "О приложении")
        info(actions, "Версия", BuildConfig.VERSION_NAME)
        info(actions, "Как работает приложение",
            "Поисковая система по открытым источникам сети. Приложение не хранит видеофайлы и не имеет собственных серверов")
        info(actions, "Условия использования",
            "Предоставляется «как есть» (AS IS). Доступность сайтов и раздач зависит от вашей сети и самих источников")

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
        buttons[id]?.invoke()
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val (parentId, key, value) = subOptionRows[action.id] ?: return true
        Settings.setString(requireContext(), key, value)
        findActionById(parentId)?.let { parent ->
            parent.description = action.title
            parent.subActions?.forEach { sub -> sub.isChecked = sub.id == action.id }
            val pos = findActionPositionById(parentId)
            if (pos >= 0) notifyActionChanged(pos)
        }
        return true
    }

    /** Side effects of the switches that do something at once, not only when a screen reads them. */
    private fun onCheck(key: String, on: Boolean) {
        val app = requireContext().applicationContext
        when (key) {
            Settings.RECOMMENDATIONS -> Thread {
                runCatching { if (on) Recommendations.refresh(app) else Recommendations.clear(app) }
            }.start()
            Settings.AUTO_BYPASS -> {
                com.lmd.dpi.DpiBypass.enabled = on
                com.lmd.dpi.DpiBypass.clearCache()
                clearFailedPosters()
                if (on) com.lmd.dpi.DpiBypass.start(app.applicationInfo.nativeLibraryDir)
            }
            Settings.APP_PROXY -> {
                com.lmd.dpi.DpiBypass.appProxyAllowed = on
                com.lmd.dpi.DpiBypass.clearCache()
                clearFailedPosters()
            }
            Settings.APP_PROXY_TORRENTS -> {
                com.lmd.dpi.DpiBypass.appProxyTorrentsAllowed = on
                com.lmd.dpi.DpiBypass.clearCache()
            }
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
                result.onSuccess { toast(it) }.onFailure { toast("Конфиг не скачался: ${Errors.explain(it)}") }
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
                .onSuccess { toast("Файл выбора не найден, копия: ${it.absolutePath}"); activity?.recreate() }
                .onFailure { toast("Не удалось сохранить: ${Errors.explain(it)}") }
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
                .onFailure { toast("Не удалось восстановить: ${Errors.explain(it)}") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = requireContext()
        when (requestCode) {
            requestSave -> runCatching { Backup.write(ctx, uri, Backup.toJson(ctx)) }
                .onSuccess { toast("Копия сохранена"); activity?.recreate() }
                .onFailure { toast("Не удалось сохранить: ${Errors.explain(it)}") }
            requestLoad -> runCatching { Backup.restore(ctx, Backup.read(ctx, uri)) }
                .onSuccess { toast("Восстановлено: $it"); activity?.recreate() }
                .onFailure { toast("Не удалось восстановить: ${Errors.explain(it)}") }
        }
    }

    /** One line per switched-on site: how fast it answered, through which way, or why it did not. Old "silent" marks are cleared first. */
    private fun checkSources() {
        val ctx = requireContext()
        val act = requireActivity()
        toast("Проверяем источники…")
        android.util.Log.i("LazyTV", "check sources: start")
        Thread {
            val ids = Sources.all.filter { Settings.bool(ctx, Settings.service(it.id)) }.map { it.id }
            val lines = Sources.parallel(ids) { id ->
                RemoteConfig.forget(id)
                RemoteConfig.bases(id).forEach { if (it.startsWith("https://")) com.lmd.dpi.DpiBypass.forget(it.substringAfter("://").substringBefore('/')) }
                val started = System.currentTimeMillis()
                val result = runCatching { RemoteConfig.attempt(id) { base -> Sources.get(base, quick = true) } }
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                val name = Sources.byId(id)?.name ?: id
                result.fold({ fetched ->
                    val host = fetched.base.substringAfter("://").substringBefore('/')
                    val way = if (fetched.base.startsWith("https://")) com.lmd.dpi.DpiBypass.route(host) else "напрямую"
                    String.format(java.util.Locale("ru"), "%s: %.1f с • %s • %s", name, seconds, way, host)
                }, { error -> name + ": не отвечает (" + Errors.explain(error) + ")" })
            }.map { (id, line) -> line ?: ((Sources.byId(id)?.name ?: id) + ": проверка не удалась") }
            android.util.Log.i("LazyTV", "check sources: " + lines.joinToString(" | "))
            act.runOnUiThread {
                android.util.Log.i("LazyTV", "check sources: ui, added=" + isAdded + " finishing=" + act.isFinishing)
                if (isAdded) runCatching { showChoice(act, "Состояние источников", lines, -1) { } }
                    .onFailure { android.util.Log.w("LazyTV", "check sources dialog failed", it) }
            }
        }.start()
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
        val app = ctx.applicationContext
        Thread {
            val result = runCatching { ExtraTorrents.jackettCount(app, "матрица") }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                result.onSuccess { toast("Jackett отвечает, найдено раздач: $it") }
                    .onFailure { toast("Jackett не ответил: ${Errors.explain(it)}") }
            }
        }.start()
    }

    private fun downloadTorrServe() = Links.open(requireActivity(), TorrServe.DOWNLOAD)

    private fun toast(message: String) = android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
}
