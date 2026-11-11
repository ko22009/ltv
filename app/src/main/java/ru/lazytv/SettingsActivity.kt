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
import android.widget.EditText
import android.widget.Switch
import androidx.fragment.app.Fragment

/**
 * Settings screens: one section per screen, opened from a card on the settings page. Choices open as compact
 * dropdowns, and the Help section uses a full-width card page so multi-line answers fit without clipping.
 */
class SettingsActivity : FragmentActivity() {
    private val scale get() = uiScale()
    private fun px(value: Int) = (value * scale).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the settings pages lie on calmBackground: the bars of the phone take its two ends
        if (isMobileUi()) barsLikeCalmBackground()
        val section = intent.getStringExtra("section").orEmpty()
        if (section == "Помощь") {
            buildHelpScreen()
            return
        }
        if (savedInstanceState == null) {
            val args = Bundle().apply { putString("section", section) }
            supportFragmentManager.beginTransaction()
                .add(android.R.id.content, SettingsScreen().apply { arguments = args })
                .commit()
        }
    }

    private fun buildHelpScreen() {
        val root = FrameLayout(this).apply { background = calmBackground() }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isMobileUi()) setPadding(px(16), px(16), px(16), px(12)) else setPadding(px(64), px(36), px(64), px(24))
        }
        val back = backButton(this, scale) { finish() }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(16) })
            addView(TextView(this@SettingsActivity).apply {
                text = "Помощь"
                setTextColor(Color.WHITE)
                // the same size as the titles of the other phone pages
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (isMobileUi()) 24f else 34f) * scale)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
        }
        page.addView(header)

        // no action buttons here: "Подобрать приём обхода" is in the "Сеть" section, and for a wrong clock the error box offers the settings itself
        val topics = listOf(
            "Сайт не открывается" to
                "Включите обход блокировок в разделе «Сеть» и нажмите там «Подобрать приём обхода». Если показ закрыт для вашего региона, используйте торренты.",
            "Видео не запускается или нет звука" to
                "Если встроенный плеер не поддерживает кодек раздачи, выберите внешний плеер в разделе «Воспроизведение» или удерживайте OK на серии для выбора другого качества.",
            "Торренты и TorrServe" to
                // the step after the installation stands on its own line in bold, with an empty line round it (as in the "TorrServe не установлен" window)
                android.text.SpannableStringBuilder().apply {
                    append("LazyTV не скачивает торренты сам: нужна программа TorrServe на приставке или другом устройстве в домашней сети. Скачайте и поставьте её (кнопка «Скачать TorrServe» в разделе «Торренты и адреса»).\n\n")
                    val start = length
                    append("После установки запустите TorrServe и нажмите в нём кнопку «Установить»: без этого сервер не заработает.")
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("\n\nВыбирайте раздачи с большим числом сидов (▲).")
                },
            "Управление плеером, звук и субтитры" to
                "При скрытой панели стрелки влево и вправо перематывают ролик на 10 секунд. Стрелки вверх, вниз или кнопка OK открывают нижнюю панель с выбором звуковой дорожки, субтитров, качества и скорости.",
            "Дата и время на приставке" to
                "Если после отключения питания на приставке сбились часы, защищённые сайты (HTTPS) перестают открываться. Проверьте дату и время в системных настройках приставки.",
            "Старые приставки (Android 6 и ниже)" to
                "На старых прошивках истекли системные сертификаты безопасности: часть HTTPS-сайтов не открывается без обновления сертификатов системы.",
        )

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }
        val cardsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cards = mutableListOf<View>()
        val mobile = isMobileUi()
        topics.forEachIndexed { index, (topicTitle, body) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // on a phone the answers are plain text: a plate without a press looked like a button
                if (mobile) {
                    setPadding(px(4), px(12), px(4), px(12))
                } else {
                    setPadding(px(24), px(16), px(24), px(16))
                    background = textTilePlate(scale)
                    isFocusable = true
                }
                addView(TextView(this@SettingsActivity).apply {
                    text = topicTitle
                    isDuplicateParentStateEnabled = true
                    setTextColor(cursorColors(this@SettingsActivity, Color.WHITE))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = body
                    // the caption colour of the cursor while the card is focused: the grey is hard to read on the focus colour
                    isDuplicateParentStateEnabled = true
                    setTextColor(cursorColors(this@SettingsActivity, TEXT_SECONDARY))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale)
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, px(8), 0, 0)
                })
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (index == 0) {
                                scroll.smoothScrollTo(0, 0)
                                back.requestFocus()
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
            cardsList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) })
        }
        scroll.addView(cardsList)
        // air between the back button row and the first card
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(16) })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        back.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> cards.firstOrNull()?.requestFocus() ?: true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        setContentView(root)
        // the back button first, as on the other nested screens: the cards are one step down
        back.requestFocus()
    }
}

/** What a line of a settings screen is. */
enum class RowKind { SECTION, INFO, CHECK, CHOICE, EDIT, BUTTON }

/** One line of a settings screen: the rows, their values and what they do are the same on the TV and on the phone. */
class SettingRow(
    val id: Long,
    val kind: RowKind,
    val title: String,
    var description: String = "",
    val key: String = "",
    var checked: Boolean = false,
    /** CHOICE: value to label; the current value is in [value]. */
    val options: List<Pair<String, String>> = emptyList(),
    /** CHOICE: the stored value, EDIT: the stored text. */
    var value: String = "",
    val action: (() -> Unit)? = null,
) {
    var descView: TextView? = null
    var toggle: Switch? = null
}

/**
 * A settings section drawn as plain views (the TV and the phone share it): a back button, the title and a scrolling list
 * of rows. Switches flip at once, a choice opens a list, a text row opens an input box, a button does its action.
 */
open class SettingsScreen : Fragment() {
    private val rows = mutableListOf<SettingRow>()
    private val sectionIds = HashMap<String, Long>()
    private var nextId = 1L
    private lateinit var list: LinearLayout

    /** Request codes of the two file dialogs: a backup is written to the picked file, or read from it. */
    private val requestSave = 7001
    private val requestLoad = 7002

    private val scale get() = requireContext().uiScale()
    private fun px(value: Int) = (value * scale).toInt()

    private fun newId() = nextId++

    private fun section(title: String) {
        val id = newId()
        sectionIds[title] = id
        rows.add(SettingRow(id, RowKind.SECTION, title.uppercase()))
    }

    private fun check(title: String, description: String, key: String, default: Boolean = true) {
        rows.add(SettingRow(newId(), RowKind.CHECK, title, description, key, checked = Settings.bool(requireContext(), key, default)))
    }

    private fun choice(title: String, key: String, default: String, options: List<Pair<String, String>>) {
        val current = Settings.string(requireContext(), key, default)
        rows.add(SettingRow(newId(), RowKind.CHOICE, title, options.firstOrNull { it.first == current }?.second ?: current, key,
            options = options, value = current))
    }

    private fun editable(title: String, key: String, default: String) {
        val value = Settings.string(requireContext(), key, default)
        rows.add(SettingRow(newId(), RowKind.EDIT, title, value.ifEmpty { default }, key, value = value))
    }

    private fun info(title: String, description: String) {
        rows.add(SettingRow(newId(), RowKind.INFO, title, description))
    }

    private fun button(title: String, description: String = "", action: () -> Unit) {
        rows.add(SettingRow(newId(), RowKind.BUTTON, title, description, action = action))
    }

    private fun label(value: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val mobile = requireContext().isMobileUi()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = calmBackground()
            if (mobile) setPadding(px(16), px(16), px(16), px(8)) else setPadding(px(64), px(36), px(64), px(24))
        }
        val back = backButton(ctx, scale) { requireActivity().finish() }
        val sectionTitle = arguments?.getString("section").orEmpty()
        val header = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(px(if (mobile) 48 else 56), px(if (mobile) 48 else 56)).apply { rightMargin = px(if (mobile) 14 else 20) })
            addView(label(sectionTitle.ifEmpty { "Настройки" }, if (mobile) 22f else 34f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
        }
        root.addView(header)
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, px(if (mobile) 12 else 20), 0, px(16))
        }
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        back.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus() ?: true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        buildRows()
        render()
        // the back button first, as on the other nested screens: the rows are one step down
        back.requestFocus()
        return root
    }

    private fun rowView(row: SettingRow): View {
        val ctx = requireContext()
        val mobile = requireContext().isMobileUi()
        val focusable = row.kind != RowKind.SECTION && row.kind != RowKind.INFO
        // a caption block is not a button: no plate, so the rows one can press stand out
        val plain = row.kind == RowKind.INFO
        if (row.kind == RowKind.SECTION) {
            return label(row.title, if (mobile) 12f else 14f, TEXT_MUTED, bold = true).apply {
                setPadding(px(4), px(16), 0, px(8))
            }
        }
        val titleView = label(row.title, if (mobile) 15f else 18f, Color.WHITE, bold = true).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(cursorColors(ctx, Color.WHITE))
        }
        val desc = label(row.description, if (mobile) 13f else 15f, TEXT_MUTED).apply {
            setPadding(0, px(4), 0, 0)
            visibility = if (row.description.isEmpty()) View.GONE else View.VISIBLE
            // the caption colour of the cursor while the row is focused: the grey is hard to read on the focus colour
            isDuplicateParentStateEnabled = true
            setTextColor(cursorColors(ctx, TEXT_MUTED))
        }
        row.descView = desc
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // the row's focus reaches the captions through this block
            isDuplicateParentStateEnabled = true
            addView(titleView)
            addView(desc)
        }
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            val side = px(if (mobile) 14 else 22)
            setPadding(
                side, px(if (plain) 8 else if (mobile) 12 else 14),
                side, px(if (plain) 8 else if (mobile) 12 else 14),
            )
            if (!plain) background = textTilePlate(scale)
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (row.kind == RowKind.CHECK) {
                val toggle = Switch(ctx).apply {
                    isChecked = row.checked
                    isClickable = false
                    isFocusable = false
                    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                    // neutral greys: the switch does not take the blue of the page
                    trackTintList = android.content.res.ColorStateList(states, intArrayOf(SWITCH_TRACK_ON, SWITCH_TRACK_OFF))
                    thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, SWITCH_THUMB_OFF))
                }
                row.toggle = toggle
                addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(12) })
            }
            if (focusable) {
                isFocusable = true
                isClickable = true
                setOnClickListener { clicked(row) }
                // the left and right arrows do nothing on a row: the focus must not jump to a view that is not beside it
                setOnKeyListener { _, code, event ->
                    event.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
        }
    }

    /** Draws the rows of the section; each row keeps its views, so a change redraws only its own texts. */
    private fun render() {
        list.removeAllViews()
        for (row in rows) list.addView(rowView(row), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
    }

    private fun show(row: SettingRow) {
        row.descView?.apply {
            text = row.description
            visibility = if (row.description.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun clicked(row: SettingRow) {
        val ctx = requireContext()
        when (row.kind) {
            RowKind.CHECK -> {
                row.checked = !row.checked
                row.toggle?.isChecked = row.checked
                Settings.setBool(ctx, row.key, row.checked)
                onCheck(row.key, row.checked)
            }
            RowKind.CHOICE -> showChoice(requireActivity(), row.title, row.options.map { it.second }, row.options.indexOfFirst { it.first == row.value }) { index ->
                val (value, labelText) = row.options[index]
                Settings.setString(ctx, row.key, value)
                row.value = value
                row.description = labelText
                show(row)
            }
            RowKind.EDIT -> askText(row)
            RowKind.BUTTON -> row.action?.invoke()
            else -> Unit
        }
    }

    /** A value typed by hand (an address, a key): the box starts with the stored text. */
    private fun askText(row: SettingRow) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(row.value)
            setSingleLine()
            setSelection(text.length)
            setTextColor(Color.WHITE)
        }
        val box = FrameLayout(ctx).apply {
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        showMessage(requireActivity(), row.title, content = box, initialFocus = input, buttons = listOf(
            DialogButton("Сохранить") {
                saveText(row, input.text.toString().trim())
                show(row)
            },
            DialogButton("Отмена"),
        ))
    }

    private fun saveText(row: SettingRow, typed: String) {
        val ctx = requireContext()
        when (row.key) {
            Settings.TORRSERVE -> {
                val address = typed.ifEmpty { "127.0.0.1:8090" }
                Settings.setString(ctx, row.key, address)
                row.value = address
                row.description = address
            }
            Settings.JACRED_SERVERS -> {
                Settings.setString(ctx, row.key, typed)
                row.value = typed
                row.description = typed.ifEmpty { "из конфига или встроенные" }
            }
            Settings.JACKETT_URL -> {
                val url = typed.trimEnd('/').let { if (it.isEmpty() || it.startsWith("http")) it else "http://$it" }
                Settings.setString(ctx, row.key, url)
                row.value = url
                row.description = url.ifEmpty { "не задан" }
            }
            else -> {
                Settings.setString(ctx, row.key, typed)
                row.value = typed
                row.description = typed.ifEmpty { "не задан" }
            }
        }
    }

    private fun buildRows() {
        nextId = 1L
        rows.clear()
        sectionIds.clear()
        val ctx = requireContext()

        section("Источники видео")
        Sources.all.forEach { source -> check(source.name, if (RemoteConfig.isDown(source.id)) "Сайт сейчас не отвечает" else "Сайт", Settings.service(source.id)) }
        button("Проверить источники", "Время ответа и путь к каждому сайту: напрямую, обход или прокси") { checkSources() }
        check("Торренты", "Раздачи для TorrServe", "source_torrents")
        check("Кэшировать найденные источники", "Не искать заново один и тот же фильм 6 часов", Settings.CACHE_SOURCES)

        section("Адреса сайтов")
        editable("Конфиг с адресами (URL)", Settings.REMOTE_URL, "")
        button("Обновить адреса из конфига", "Скачать JSON и применить сразу") { refreshRemote() }
        button("Сбросить свои адреса", "Вернуть адреса из конфига или встроенные") {
            RemoteConfig.resetManual(ctx)
            toast("Свои адреса сброшены")
            requireActivity().recreate()
        }
        Sources.all.forEach { source ->
            editable("${source.name}: свой адрес", Settings.baseKey(source.id), RemoteConfig.base(source.id))
        }

        section("Воспроизведение")
        choice("Плеер", Settings.PLAYER, "internal",
            listOf("internal" to "Встроенный", "external" to "Внешний (системный выбор)"))
        choice("Качество по умолчанию", Settings.QUALITY, "best",
            listOf("ask" to "Спрашивать", "best" to "Лучшее", "1080" to "1080p", "720" to "720p", "480" to "480p"))
        choice("Озвучка по умолчанию", Settings.VOICE, "any",
            listOf("any" to "Любая", "ru" to "Русская", "original" to "Оригинал с субтитрами"))
        choice("Что предлагать первым", Settings.SOURCE_MODE, "sites", listOf("sites" to "Сайты", "torrents" to "Торренты"))
        check("Запоминать позицию", "Продолжать с места остановки", Settings.REMEMBER_POSITION)
        check("Следующая серия сама", "После окончания включать следующую серию из списка", Settings.AUTO_NEXT)

        section("Торренты и адреса")
        choice("Открывать раздачи", Settings.TORRENT_CLIENT, "internal",
            listOf("internal" to "Через TorrServe (адрес ниже)", "external" to "Сторонним приложением (системный выбор)"))
        editable("Адрес TorrServe", Settings.TORRSERVE, "127.0.0.1:8090")
        choice("Минимум сидов в списке раздач", Settings.TORRENT_MIN_SEEDS, "0",
            Settings.MIN_SEEDS.map { it.toString() to if (it == 0) "Любое количество" else "От $it" })
        editable("Серверы JacRed (пусто: из конфига)", Settings.JACRED_SERVERS, "")
        editable("Свой Jackett: адрес", Settings.JACKETT_URL, "")
        editable("Свой Jackett: API-ключ", Settings.JACKETT_KEY, "")
        button("Проверить Jackett", "Запрос к своему Jackett с этими адресом и ключом") { checkJackett() }
        button("Скачать TorrServe", "Страница загрузки в браузере") { downloadTorrServe() }

        section("Каталог")
        choice("Язык названий и описаний", Settings.LANGUAGE, "ru-RU",
            listOf("ru-RU" to "Русский", "en-US" to "English", "uk-UA" to "Українська"))
        check("Строка «Популярное» на главном экране TV",
            "Рекомендации в ленте Android TV (если лаунчер их показывает)", Settings.RECOMMENDATIONS)

        section("Сеть")
        if (ClockCheck.skewed) {
            info("Часы на устройстве сбились", ClockCheck.MESSAGE)
            button("Открыть настройки даты и времени", "Системные часы устройства") { Errors.openDateSettings(ctx) }
        }
        check("Обход блокировок", "Сначала напрямую, затем встроенный обход (ByeDPI)", Settings.AUTO_BYPASS)
        check("Прокси для сайтов", "Запасной путь, если встроенный обход не помог", Settings.APP_PROXY)
        check("Прокси для торрентов", "То же для поиска раздач (rutor)", Settings.APP_PROXY_TORRENTS)
        button("Подобрать приём обхода", "Перебирает приёмы ByeDPI, запоминает рабочий для этой сети") {
            com.lmd.dpi.DpiBypass.retune()
            toast("Подбор запущен, занимает до минуты")
        }

        section("Данные")
        // the "not made yet" line only reminded; the row stays useful once a copy exists
        if (Backup.exists(ctx)) info("Резервная копия", Backup.status(ctx))
        button("Сохранить копию в файл", "Закладки, история, отметки, настройки") { exportBackup() }
        button("Восстановить из файла", "Заменит закладки, историю, отметки и настройки") { importBackup() }
        button("Очистить кэш картинок") {
            clearFailedPosters()
            com.bumptech.glide.Glide.get(ctx.applicationContext).clearMemory()
            Thread { com.bumptech.glide.Glide.get(ctx.applicationContext).clearDiskCache() }.start()
            toast("Кэш картинок очищен")
        }
        button("Очистить кэш найденных источников") {
            ctx.getSharedPreferences("cache", 0).edit().clear().apply()
            toast("Кэш источников очищен")
        }
        button("Очистить историю просмотров") {
            // "Отмена" first: it takes the focus, so a stray press of OK on the remote deletes nothing
            showMessage(requireActivity(), "Очистить историю просмотров?",
                message = "Список недавно открытых фильмов и сериалов будет удалён.", buttons = listOf(
                    DialogButton("Отмена"),
                    DialogButton("Очистить") {
                        History.clear(ctx)
                        toast("История просмотров очищена")
                    },
                ))
        }
        button("Сбросить все настройки") {
            showMessage(requireActivity(), "Сбросить все настройки?",
                message = "Закладки, история и отметки просмотра сохранятся.", buttons = listOf(
                    DialogButton("Отмена"),
                    DialogButton("Сбросить") {
                        Settings.resetSettingsOnly(ctx)
                        com.lmd.dpi.DpiBypass.enabled = true
                        com.lmd.dpi.DpiBypass.appProxyAllowed = true
                        com.lmd.dpi.DpiBypass.appProxyTorrentsAllowed = true
                        com.lmd.dpi.DpiBypass.clearCache()
                        com.lmd.dpi.DpiBypass.start(ctx.applicationInfo.nativeLibraryDir)
                        toast("Настройки сброшены")
                        activity?.finish()
                    },
                ))
        }

        section("Синхронизация")
        info("Состояние", Sync.status(ctx))
        val syncReady = Sync.configured(ctx)
        val syncLinked = syncReady && Sync.linked(ctx)
        if (!syncReady) {
            info("Нужен ключ Google", "Синхронизация выключена в этой сборке: нет ключа приложения Google")
        } else if (syncLinked) {
            button("Синхронизировать сейчас", "Закладки, история, отметки просмотра, поиск и часть настроек") { syncNow() }
            check("Проверять при запуске", "Тихо в фоне, не чаще раза в 10 минут", Sync.AUTO)
            button("Отключить Google", "Данные на Диске останутся, устройство перестанет синхронизироваться") { unlinkGoogle() }
        }
        info("Что переносится", "Закладки, история просмотров, отметки просмотра, история поиска и настройки каталога. " +
            "Адреса TorrServe, JacRed и Jackett, прокси и код родителя остаются на своём устройстве")
        // the sign-in stands after the explanation: first what it brings, then the button
        if (syncReady && !syncLinked) {
            button("Подключить Google", "Приложение покажет код, его нужно ввести на телефоне") { connectGoogle() }
        }

        section("О приложении")
        info("Версия", BuildConfig.VERSION_NAME)
        info("Как работает приложение",
            "Поисковая система по открытым источникам сети. Приложение не хранит видеофайлы и не имеет собственных серверов")
        info("Условия использования",
            "Предоставляется «как есть» (AS IS). Доступность сайтов и раздач зависит от вашей сети и самих источников")


        // one section per screen: the cards on the settings page open their own screen, not one long list
        val title = arguments?.getString("section").orEmpty().ifEmpty { "Списки" }
        val start = sectionIds[title] ?: return
        val all = rows.toList()
        val headers = sectionIds.values.toSet()
        val from = all.indexOfFirst { it.id == start }
        if (from < 0) return
        val after = all.drop(from + 1)
        val end = after.indexOfFirst { it.id in headers }
        rows.clear()
        rows.addAll(if (end < 0) after else after.take(end))
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

    /** Google's code flow: the code is shown on this device, the person types it on a phone. */
    private fun connectGoogle() {
        val ctx = requireContext()
        val act = requireActivity()
        toast("Запрашиваем код у Google…")
        var dialog: android.app.Dialog? = null
        Sync.link(ctx, onCode = { code ->
            if (!isAdded || act.isFinishing) return@link
            val buttons = mutableListOf<DialogButton>()
            if (ctx.isMobileUi()) {
                // the page takes the code in the address, so nothing has to be typed on the phone
                buttons.add(DialogButton("Открыть страницу Google") { Links.open(act, googlePage(code)) })
                buttons.add(DialogButton("Скопировать код", closes = false) {
                    val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Код Google", code))
                    toast("Код скопирован")
                })
            }
            buttons.add(DialogButton("Отмена") { Sync.cancel() })
            dialog = showMessage(act, "Вход в Google", content = googleCode(ctx, code), buttons = buttons)
        }, onDone = { problem ->
            dialog?.dismiss()
            if (isAdded && !act.isFinishing) {
                if (problem == null) {
                    toast("Google подключён")
                    act.recreate()
                } else {
                    toast(problem)
                }
            }
        })
    }

    private fun googlePage(code: String) = "https://www.google.com/device?user_code=" + code.replace("-", "")

    /** The code in big letters and the steps around it: no addresses to retype, the page already carries the code. */
    private fun googleCode(ctx: android.content.Context, code: String): View {
        val mobile = ctx.isMobileUi()
        val steps = if (mobile) {
            "1. Нажмите «Открыть страницу Google»\n2. Войдите в тот же аккаунт, что и здесь\n" +
                "3. Нажмите «Продолжить» и «Разрешить»"
        } else {
            "1. На телефоне в браузере откройте google.com/device\n2. Войдите в тот же аккаунт Google\n" +
                "3. Введите этот код и нажмите «Продолжить»"
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(steps, 15f, TEXT_SECONDARY))
            addView(label(code, 34f, Color.WHITE, bold = true).apply {
                letterSpacing = 0.12f
                setTextIsSelectable(true)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, px(16), 0, px(8))
            })
            addView(label("Код действует 30 минут. Это окно закроется само, когда вход пройдёт.", 13f, TEXT_MUTED))
        }
    }

    private fun syncNow() {
        val ctx = requireContext()
        val act = requireActivity()
        toast("Синхронизируем…")
        Thread {
            val result = runCatching { Sync.syncNow(ctx.applicationContext) }
            act.runOnUiThread {
                if (!isAdded || act.isFinishing) return@runOnUiThread
                result.onSuccess { toast(it) }.onFailure { toast("Не вышло: ${Errors.explain(it)}") }
                act.recreate()
            }
        }.start()
    }

    private fun unlinkGoogle() {
        showMessage(requireActivity(), "Отключить Google?",
            message = "Данные на Диске останутся. Это устройство перестанет синхронизироваться.",
            buttons = listOf(DialogButton("Отмена"), DialogButton("Отключить") {
                Sync.unlink(requireContext())
                activity?.recreate()
            }))
    }

    private fun toast(message: String) = android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
}
