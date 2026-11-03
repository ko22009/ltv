package ru.lazytv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    /** The voice answer of the search page comes to the activity; the menu screen hands it to the page. */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        (supportFragmentManager.findFragmentById(android.R.id.content) as? MainFragment)?.voiceResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, MainFragment()).commit()
            // the install script passes this after an update: come back to the screen the user was on (docs/workflow.md)
            if (intent?.getBooleanExtra(RESTORE, false) == true) {
                intent?.removeExtra(RESTORE)
                LastScreen.restore(this)
            }
        }
    }

    private companion object {
        /** Extra of the launch intent: open the screen that was open before the update. */
        const val RESTORE = "restore"
    }
}
