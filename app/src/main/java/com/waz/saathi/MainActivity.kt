package com.waz.saathi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.waz.saathi.databinding.ActivityMainBinding

/**
 * Setup and status. The assistant itself lives in [VoiceService]; this screen
 * exists to grant permissions, hold the API key, and show what was heard
 * recently when something went wrong.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SILENT = "silent"
    }

    private lateinit var binding: ActivityMainBinding

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Saathi  ·  v${BuildConfig.VERSION_NAME}"

        binding.permissionsButton.setOnClickListener { askCorePermissions() }
        binding.storageButton.setOnClickListener { openAllFilesAccess() }
        binding.overlayButton.setOnClickListener { openOverlaySettings() }
        binding.accessibilityButton.setOnClickListener { openAccessibilitySettings() }

        binding.startButton.setOnClickListener {
            if (!canStart()) return@setOnClickListener
            startForegroundService(
                Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START)
            )
            Toast.makeText(this, "Bubble chalu. Dabakar boliye.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        binding.stopButton.setOnClickListener {
            startService(
                Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP)
            )
            refresh()
        }

        binding.saveKeyButton.setOnClickListener {
            Settings.setGeminiKey(this, binding.geminiKeyInput.text.toString())
            Toast.makeText(this, "Key save ho gayi.", Toast.LENGTH_SHORT).show()
            refresh()
        }

        binding.confirmSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setConfirmActions(this, checked)
        }

        binding.speakSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setSpeakBack(this, checked)
        }

        binding.clearHistoryButton.setOnClickListener {
            History.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // --------------------------------------------------------- permissions

    private fun askCorePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        permissions.launch(wanted.toTypedArray())
    }

    /**
     * All-files access is not an ordinary runtime permission; it is granted on
     * a system screen, and only this one lets the app move files around shared
     * storage the way a file manager does.
     */
    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            askCorePermissions()
            return
        }
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(
            this,
            "List mein Saathi dhoondhkar on karein.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canStart(): Boolean {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Pehle mic ki permission dein.", Toast.LENGTH_LONG).show()
            askCorePermissions()
            return false
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Bubble ke liye \"Display over other apps\" allow karein.",
                Toast.LENGTH_LONG
            ).show()
            openOverlaySettings()
            return false
        }
        return true
    }

    // -------------------------------------------------------------- status

    private fun refresh() {
        binding.geminiKeyInput.setText(Settings.geminiKey(this))
        binding.confirmSwitch.isChecked = Settings.confirmActions(this)
        binding.speakSwitch.isChecked = Settings.speakBack(this)

        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        val phone = granted(Manifest.permission.CALL_PHONE)
        val sms = granted(Manifest.permission.SEND_SMS)
        val overlay = AndroidSettings.canDrawOverlays(this)
        val files = FileOps.hasAllFilesAccess()
        val access = SaathiAccessibilityService.isRunning

        binding.statusText.text = buildString {
            appendLine(line("Mic (sunna)", mic))
            appendLine(line("Contacts (naam se call)", contacts))
            appendLine(line("Phone (call lagana)", phone))
            appendLine(line("SMS", sms))
            appendLine(line("Bubble (display over apps)", overlay))
            appendLine(line("All files access (file ka kaam)", files))
            append(line("Accessibility (kisi bhi app mein likhna)", access))
        }

        binding.engineText.text = if (Settings.geminiKey(this).isEmpty())
            "Samajhne ka tareeka: offline rules — seedhe commands chalenge."
        else
            "Samajhne ka tareeka: Gemini — ghumi-phiri baat bhi samajh lega."

        binding.serviceText.text =
            if (VoiceService.isRunning) "Saathi chal raha hai." else "Saathi band hai."

        val history = History.load(this)
        binding.historyText.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        binding.historyText.text = history.take(12).joinToString("\n\n") { item ->
            val mark = if (item.ok) "✓" else "✗"
            "$mark  \"${item.heard}\"\n     ${item.result}"
        }
    }

    private fun line(label: String, ok: Boolean): String =
        if (ok) "✓  $label" else "✗  $label"
}
