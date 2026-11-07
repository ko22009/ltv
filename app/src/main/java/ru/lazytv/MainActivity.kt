package ru.lazytv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    /** The voice answer of the search page comes to the activity; the menu screen hands it to the page. */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        (supportFragmentManager.findFragmentById(android.R.id.content) as? MainFragment)?.voiceResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // the install script passes this after an update: come back to the screen the user was on (docs/workflow.md)
            val restore = intent?.getBooleanExtra(RESTORE, false) == true
            val menu = MainFragment().apply {
                if (restore) arguments = Bundle().apply { putBoolean(MainFragment.RESTORE, true) }
            }
            supportFragmentManager.beginTransaction().replace(android.R.id.content, menu).commit()
            if (restore) {
                intent?.removeExtra(RESTORE)
                LastScreen.restore(this)
            }
            openRequestedTitle(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTitle(intent)
    }

    private fun openRequestedTitle(source: Intent?) {
        if (source?.getBooleanExtra(OPEN_TITLE, false) != true) return
        val extras = source.extras ?: return
        source.removeExtra(OPEN_TITLE)
        val title = titleFrom(extras)
        if (title.id > 0L) startActivity(Intent(this, DetailsActivity::class.java).putExtras(title.toExtras()))
    }

    companion object {
        /** Extra of the launch intent: open the screen that was open before the update. */
        const val RESTORE = "restore"
        /** Extra of a home-screen recommendation intent: open the title card on top of the main screen. */
        const val OPEN_TITLE = "open_title"
    }
}
