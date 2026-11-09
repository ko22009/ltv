package ru.lazytv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import ru.lazytv.DeviceType
import ru.lazytv.MainActivity
import ru.lazytv.R
import ru.lazytv.databinding.ActivityMobileMainBinding

class MobileMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMobileMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TV devices or boxes with remotes redirect to TV MainActivity unless forced
        val force = intent?.getBooleanExtra("force", false) == true
        val mode = ru.lazytv.Settings.string(this, "interface_mode", "auto")
        if (!force && mode != "mobile" && DeviceType.isTv(this)) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                intent?.extras?.let { putExtras(it) }
                addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
            })
            finish()
            return
        }

        binding = ActivityMobileMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.mobileNavHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        binding.mobileBottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.nav_catalog, R.id.nav_search,
                R.id.nav_torrents, R.id.nav_favorites,
                R.id.nav_settings -> binding.mobileBottomNav.visibility = View.VISIBLE
                else -> binding.mobileBottomNav.visibility = View.GONE
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
