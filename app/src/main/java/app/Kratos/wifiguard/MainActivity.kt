package app.kratos.wifiguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "Kratos, the Wi-Fi guard"
            textSize = 24f
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
        }
        col.addView(status)

        col.addView(button("1. Allow floating widget (overlay)") { openOverlaySettings() })
        col.addView(button("2. Start Kratos") { startKratos() })
        col.addView(button("Stop Kratos") { stopKratos() })

        setContentView(col)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun refresh() {
        val overlay = if (Settings.canDrawOverlays(this)) "allowed" else "NOT allowed yet"
        val running = if (OverlayService.running) "ON" else "OFF"
        status.text = "Floating widget permission: $overlay\nKratos widget: $running"
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startKratos() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow the floating widget first (button 1).", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        status.postDelayed({ refresh() }, 600)
    }

    private fun stopKratos() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        status.postDelayed({ refresh() }, 600)
    }
}
