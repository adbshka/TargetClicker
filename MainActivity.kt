package ru.nikita.targetclicker

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            textSize = 15f
            text = """
                1. Включи «TargetClicker» в спец. возможностях — появится плавающая кнопка ▶.
                2. Нажми «Запустить захват» и выбери «Весь экран».
                3. Открой игру, убери кнопку ▶ в сторону от квадрата и нажми её.
                Красная ■ — стоп.
            """.trimIndent()
        })

        root.addView(Button(this).apply {
            text = "1. Открыть спец. возможности"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Запустить захват экрана"
            setOnClickListener {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                captureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        })
        root.addView(Button(this).apply {
            text = "Остановить захват"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_STOP))
            }
        })

        addSlider(root, "Пауза между кликами, мс", 5, 200, Config.intervalMs.toInt()) {
            Config.intervalMs = it.toLong()
        }
        addSlider(root, "Верх области поиска, % экрана", 0, 100, Config.topPct) { Config.topPct = it }
        addSlider(root, "Низ области поиска, % экрана", 0, 100, Config.bottomPct) { Config.bottomPct = it }
        addSlider(root, "Упреждение, мс", 0, 150, Config.leadMs) { Config.leadMs = it }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addSlider(root: ViewGroup, title: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val label = TextView(this).apply { textSize = 15f; setPadding(0, 32, 0, 0) }
        fun show(v: Int) { label.text = "$title: $v" }
        show(value)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    show(v); onChange(v); Config.save(this@MainActivity)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(label)
        root.addView(bar)
    }
}
