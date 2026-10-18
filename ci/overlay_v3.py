#!/usr/bin/env python3
from pathlib import Path
root=Path("android-ime/app/src/main")
manifest=root/"AndroidManifest.xml"
s=manifest.read_text()
assert '<application' in s and '</application>' in s
s=s.replace('<application', '''<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application''',1)
s=s.replace('</application>', '''    <service android:name=".FloatingDictationService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
    </application>''',1)
manifest.write_text(s)
act=root/"java/com/example/dictationbutton/SetupActivity.kt"
s=act.read_text()
s=s.replace('        render()\n    }\n\n    override fun onResume()', '''        val root = ((findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView).getChildAt(0) as android.view.ViewGroup)
        val launchBubble = Button(this).apply {
            text = "Start floating microphone (keep Samsung Keyboard)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@SetupActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                } else {
                    try {
                        startForegroundService(Intent(this@SetupActivity, FloatingDictationService::class.java))
                        status.text = "Floating microphone started. Keep Samsung Keyboard selected."
                    } catch (t: Throwable) { status.text = "Could not start bubble: ${t.message}" }
                }
            }
        }
        root.addView(launchBubble)
        val stopBubble = Button(this).apply {
            text = "Stop floating microphone"
            setOnClickListener { stopService(Intent(this@SetupActivity, FloatingDictationService::class.java)) }
        }
        root.addView(stopBubble)
        render()
    }

    override fun onResume()''',1)
s=s.replace('        if (::status.isInitialized) render()','''        if (::status.isInitialized) render()''')
act.write_text(s)
print("Floating microphone service registered and launch controls added.")
