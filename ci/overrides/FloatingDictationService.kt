package com.example.dictationbutton

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.speech.*
import android.view.*
import android.widget.*

/** Optional persistent microphone bubble: Samsung Keyboard stays selected. */
class FloatingDictationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: LinearLayout? = null
    private var mic: Button? = null
    private var status: TextView? = null
    private var copy: Button? = null
    private var recognizer: SpeechRecognizer? = null
    private var recording = false
    private var requestNumber = 0
    private var watchdog: Runnable? = null
    private var latest = ""

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "floating_dictation"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Floating dictation", NotificationManager.IMPORTANCE_LOW))
        val goHome = PendingIntent.getActivity(this, 0,
            Intent(this, SetupActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Floating Dictation Button")
            .setContentText("Tap the bubble to dictate. Open the app to switch it off.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(goHome).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(77, notification)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        showBubble()
    }

    private fun showBubble() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE9223448.toInt())
            setPadding(8, 8, 8, 8)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mic = Button(this).apply {
            text = "🎙 Dictate"
            contentDescription = "Start speech recognition"
            setOnClickListener { if (recording) stopRecording() else startRecording() }
        }
        val dismiss = Button(this).apply {
            text = "×"
            contentDescription = "Close floating dictation"
            setOnClickListener { stopSelf() }
        }
        row.addView(mic)
        row.addView(dismiss)
        status = TextView(this).apply { text = "Ready"; setTextColor(-1); maxLines = 3 }
        copy = Button(this).apply {
            text = "Copy transcript"
            visibility = View.GONE
            setOnClickListener {
                if (latest.isNotBlank()) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Dictation", latest))
                    status?.text = "Copied. Long-press your text box and tap Paste."
                }
            }
        }
        box.addView(row)
        box.addView(status)
        box.addView(copy)
        bubble = box
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 6; y = 250
        }
        wm.addView(box, params)
    }

    private fun cancelTimeout() { watchdog?.let(handler::removeCallbacks); watchdog = null }
    private fun timeout(number: Int, millis: Long, message: String) {
        cancelTimeout()
        val action = Runnable { if (requestNumber == number) finishWithError(message) }
        watchdog = action; handler.postDelayed(action, millis)
    }
    private fun startRecording() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status?.text = "No Android speech recognizer is installed or enabled."; return
        }
        val number = ++requestNumber
        recording = true
        mic?.text = "■ Stop"
        copy?.visibility = View.GONE
        status?.text = "Listening…"
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status?.text = "Processing speech…" }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (number == requestNumber) finishWithError("Speech recognizer error $error. Try again.")
            }
            override fun onResults(results: Bundle?) {
                if (number != requestNumber) return
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (transcript.isEmpty()) finishWithError("No words recognized.")
                else {
                    endRecognition()
                    latest = transcript
                    status?.text = transcript.take(160)
                    copy?.visibility = View.VISIBLE
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Dictation", transcript))
                    status?.text = "Copied automatically: " + transcript.take(110)
                }
            }
        })
        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            timeout(number, 75000, "No response from speech service. Tap Dictate to retry.")
        } catch(t: Throwable) { finishWithError(t.message ?: "Could not start speech recognition.") }
    }
    private fun stopRecording() {
        if (!recording) return
        recording = false
        mic?.text = "Processing…"
        mic?.isEnabled = false
        status?.text = "Transcribing (up to 12 seconds)…"
        try { recognizer?.stopListening() }
        catch(t: Throwable) { finishWithError(t.message ?: "Could not finish recording."); return }
        timeout(requestNumber, 12000, "Speech provider did not respond after Stop. Try again.")
    }
    private fun finishWithError(message: String) { endRecognition(); status?.text = message }
    private fun endRecognition() {
        ++requestNumber
        cancelTimeout()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        recording = false
        mic?.text = "🎙 Dictate"
        mic?.isEnabled = true
    }
    override fun onDestroy() {
        endRecognition()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }
}
