package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.bumptech.glide.Glide

/** Actor page: photo, facts, biography and the films and series the person is known for. */
class PersonActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.background = calmBackground()
        val list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        val loading = LoadingView(this, scale, "Загружаем страницу актёра…")
        root.addView(list, FrameLayout.LayoutParams(-1, -1))
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val id = intent.getLongExtra("id", 0)
        val fallbackName = intent.getStringExtra("name").orEmpty()
        Thread {
            val result = runCatching { Tmdb.person(applicationContext, id) }
            onUi {
                loading.visibility = View.GONE
                result.onSuccess { person ->
                    list.addHeaderView(header(person), null, false)
                    list.adapter = object : ArrayAdapter<Tmdb.Credit>(this, 0, person.credits) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup) = row(getItem(position)!!)
                    }
                    list.setOnItemClickListener { _, _, position, _ ->
                        // the header is position 0, the credits start after it
                        val credit = person.credits.getOrNull(position - 1) ?: return@setOnItemClickListener
                        startActivity(Intent(this, DetailsActivity::class.java).putExtras(credit.title.toExtras()))
                    }
                    list.requestFocus()
                }.onFailure {
                    list.addHeaderView(simpleHeader(fallbackName, "Не удалось загрузить страницу: ${it.message}"), null, false)
                }
            }
        }.start()
    }

    private fun header(person: Tmdb.PersonPage): View {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(px(64), px(36), px(64), px(24))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        // initials on a calm green plate sit under the photo: they show while it loads and for a person who has none
        val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        val photo = android.widget.FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2F5A44.toInt(), 0xFF1B3327.toInt()))
            addView(label(initials, 64f, 0xFFB9F6CA.toInt()).apply { gravity = Gravity.CENTER },
                android.widget.FrameLayout.LayoutParams(-1, -1))
            val image = ImageView(this@PersonActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
            if (!person.photo.isNullOrEmpty()) Glide.with(this@PersonActivity).load("https://image.tmdb.org/t/p/w342${person.photo}").into(image)
        }
        top.addView(photo, LinearLayout.LayoutParams(px(220), px(300)).apply { rightMargin = px(28) })
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label(person.name, 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val facts = listOf(person.birthday, person.place).filter { it.isNotEmpty() }.joinToString(" • ")
        if (facts.isNotEmpty()) texts.addView(label(facts, 16f, 0xFF8FB39D.toInt()).apply { setPadding(0, px(8), 0, 0) })
        if (person.biography.isNotEmpty()) texts.addView(label(person.biography, 15f, 0xFFDCE8E0.toInt()).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, px(18), 0, 0)
            maxLines = 9
        })
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        block.addView(top)
        block.addView(label("ФИЛЬМОГРАФИЯ", 18f).apply { setPadding(0, px(26), 0, px(8)) })
        return block
    }

    private fun simpleHeader(name: String, message: String): View {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(px(64), px(40), px(64), px(20))
        block.addView(label(name, 34f))
        block.addView(label(message, 16f, 0xFFAFC4B6.toInt()).apply { setPadding(0, px(10), 0, 0) })
        return block
    }

    private fun row(credit: Tmdb.Credit): View {
        val card = LinearLayout(this)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(px(20), px(10), px(20), px(10))
        card.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                setColor(0xFF2F7D57.toInt()); cornerRadius = px(3).toFloat()
            })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x33000000); cornerRadius = px(3).toFloat() })
        }
        val poster = ImageView(this)
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.setBackgroundColor(0xFF12201A.toInt())
        val spinner = Spinner(this, px(26))
        poster.showPoster(credit.title.poster, spinner, "w185")
        card.addView(android.widget.FrameLayout(this).apply {
            addView(poster, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(spinner, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, LinearLayout.LayoutParams(px(64), px(94)).apply { rightMargin = px(18) })
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER_VERTICAL
        texts.addView(label(credit.title.name, 19f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(label(listOf(credit.title.year, credit.role).filter { it.isNotEmpty() }.joinToString(" • "),
            14f, 0xFFAFC4B6.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val holder = object : FrameLayout(this) {
            override fun setSelected(selected: Boolean) { super.setSelected(selected); card.isSelected = selected }
        }
        holder.setPadding(0, px(3), 0, px(3))
        holder.addView(card)
        return holder
    }
}
