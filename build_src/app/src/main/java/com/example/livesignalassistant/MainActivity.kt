package com.example.livesignalassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private val exportWorker = Executors.newSingleThreadExecutor()

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            })
        }
    }
    private lateinit var statusView: TextView
    private lateinit var pendingBox: LinearLayout
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) exportWorker.execute {
            val msg = try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                ExperienceStore(applicationContext).importText(text)
            } catch (t: Throwable) { "Import failed: ${t.message ?: t.javaClass.simpleName}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); refreshStatus() }
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36,60,36,36) }
        root.addView(TextView(this).apply {
            text = "Live Signal Assistant 72.0.4\n\nVision repair · Experience V3\nEV is evidence, not a probability.\nتجربه‌های WIN/LOSS مستقل از حافظه تست نگهداری می‌شوند."
            textSize = 20f
        })
        root.addView(Button(this).apply {
            text = "۱) اجازه نمایش روی برنامه‌های دیگر"
            setOnClickListener { if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        })
        root.addView(Button(this).apply {
            text = "۲) شروع مشاهده زنده صفحه"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return@setOnClickListener }
                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        })
        root.addView(Button(this).apply {
            text = "۳) EXPORT TEST MEMORY (ZIP)"
            setOnClickListener { button ->
                button.isEnabled=false; Toast.makeText(this@MainActivity,"Export + ZIP verification started…",Toast.LENGTH_SHORT).show()
                exportWorker.execute {
                    try {
                        val result=MemoryExporter.exportAll(applicationContext)
                        runOnUiThread { button.isEnabled=true; showPostExportDialog(result) }
                    } catch(t:Throwable) {
                        runOnUiThread { button.isEnabled=true; Toast.makeText(this@MainActivity,"Export failed: ${t.message ?: t.javaClass.simpleName}",Toast.LENGTH_LONG).show() }
                    }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "۴) CLEAR TEST MEMORY"
            setOnClickListener { confirmClear(false) }
        })
        root.addView(Button(this).apply {
            text = "۵) EXPORT EXPERIENCE (JSON backup)"
            setOnClickListener {
                exportWorker.execute {
                    val msg = try { "Saved: " + MemoryExporter.exportExperience(applicationContext) } catch (t: Throwable) { "Export failed: ${t.message ?: t.javaClass.simpleName}" }
                    runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "۶) IMPORT EXPERIENCE (restore backup)"
            setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        })
        statusView = TextView(this).apply { textSize = 13f; setPadding(0, 24, 0, 0) }
        root.addView(statusView)
        pendingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 0) }
        root.addView(pendingBox)
        root.addView(TextView(this).apply {
            text="\nبعد از Export موفق و Verify شده می‌توانی حافظه داخلی تست را پاک کنی. ZIP ذخیره‌شده در Downloads حذف نمی‌شود.\n\nپاک‌سازی Session هرگز Experience Core را حذف نمی‌کند. Experience فقط از WIN/LOSS یاد می‌گیرد؛ VOID آموزش نمی‌دهد."
            textSize=15f
        })
        setContentView(root)
    }

    private fun refreshStatus() {
        exportWorker.execute {
            val t = try { ExperienceStore(applicationContext).driftSummary() } catch (e: Throwable) { "experience unavailable" }
            runOnUiThread { statusView.text = "Experience: $t\n(STRENGTH is not a win probability)" }
        }
    }

    override fun onResume() { super.onResume(); refreshStatus(); refreshPending() }

    private fun pendingStore() = PendingTradeStore(File(filesDir, "permanent_experience"))

    private fun refreshPending() {
        if (!::pendingBox.isInitialized) return
        pendingBox.removeAllViews()
        val items = pendingStore().all()
        if (items.isEmpty()) return
        pendingBox.addView(TextView(this).apply {
            text = "Unlabeled executed signals (${items.size}). The first recorded WIN, LOSS, or VOID is kept."
            textSize = 14f
        })
        for (p in items) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = "${p.direction} · ${p.state}\n"
                textSize = 13f
            })
            for (label in listOf("WIN", "LOSS", "VOID")) {
                row.addView(Button(this).apply {
                    text = label
                    textSize = 11f
                    setOnClickListener { settlePending(p, label) }
                })
            }
            pendingBox.addView(row)
        }
    }

    private fun settlePending(p: PendingTradeStore.Pending, result: String) {
        exportWorker.execute {
            val store = ExperienceStore(applicationContext)
            if (result == "WIN" || result == "LOSS") store.learn(p.direction, p.state, p.regime, p.band, result, p.signalId, p.fields)
            else store.recordUntrained(p.signalId, result, p.direction, p.state, p.regime, p.band, p.fields)
            pendingStore().remove(p.signalId)
            runOnUiThread { refreshPending(); refreshStatus(); Toast.makeText(this, "$result recorded", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showPostExportDialog(result: MemoryExporter.ExportResult) {
        val mb=result.bytes/1024.0/1024.0
        AlertDialog.Builder(this)
            .setTitle("EXPORT VERIFIED ✓")
            .setMessage("${result.sessionCount} session exported\n${result.path}\nZIP: %.1f MB\n\nحافظه داخلی تست پاک شود؟".format(mb))
            .setNegativeButton("KEEP MEMORY", null)
            .setPositiveButton("DELETE EXPORTED MEMORY") { _,_ -> confirmClear(true) }
            .show()
    }

    private fun confirmClear(afterExport:Boolean) {
        val bytes=MemoryExporter.memoryBytes(applicationContext)
        val mb=bytes/1024.0/1024.0
        AlertDialog.Builder(this)
            .setTitle(if(afterExport) "DELETE EXPORTED MEMORY?" else "CLEAR TEST MEMORY?")
            .setMessage("%.1f MB از حافظه داخلی برنامه پاک می‌شود.\nفایل ZIP داخل Downloads دست‌نخورده می‌ماند.\nبرای جلوگیری از نوشتن هم‌زمان، مشاهده زنده متوقف می‌شود.".format(mb))
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("DELETE") { _,_ ->
                stopService(Intent(this, CaptureService::class.java))
                exportWorker.execute {
                    try { val freed=MemoryExporter.clearAll(applicationContext); runOnUiThread { Toast.makeText(this,"Memory cleared: %.1f MB freed".format(freed/1024.0/1024.0),Toast.LENGTH_LONG).show() } }
                    catch(t:Throwable){runOnUiThread{Toast.makeText(this,"Clear failed: ${t.message}",Toast.LENGTH_LONG).show()}}
                }
            }.show()
    }

    override fun onDestroy(){ exportWorker.shutdownNow(); super.onDestroy() }
}
