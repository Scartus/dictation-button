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
    private var overlayParams: WindowManager.LayoutParams? = null
    private var mic: Button? = null
    private var status: TextView? = null
    private var copy: Button? = null
    private var recognizer: SpeechRecognizer? = null
    private var recording = false
    private var requestNumber = 0
    private var watchdog: Runnable? = null
    private var latest = ""
    private var partial = ""
    private var ready = false
    private var heardSpeech = false

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
        status = TextView(this).apply {
            text = "Drag here to move"; setTextColor(-1); maxLines = 3
            setPadding(12, 12, 12, 12)
            var downX = 0f
            var downY = 0f
            var originalX = 0
            var originalY = 0
            setOnTouchListener { _, event ->
                val params = overlayParams ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        originalX = params.x; originalY = params.y
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = (originalX - (event.rawX - downX)).toInt().coerceAtLeast(0)
                        params.y = (originalY + (event.rawY - downY)).toInt().coerceAtLeast(0)
                        bubble?.let { wm.updateViewLayout(it, params) }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> true
                    else -> false
                }
            }
        }
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
        overlayParams = params
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
        ready = false
        heardSpeech = false
        partial = ""
        mic?.text = "Preparing…"
        mic?.isEnabled = false
        copy?.visibility = View.GONE
        status?.text = "Listening…"
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (number == requestNumber) {
                    ready = true
                    mic?.isEnabled = true
                    mic?.text = "■ Stop"
                    status?.text = "Listening — speak now"
                }
            }
            override fun onBeginningOfSpeech() {
                if (number == requestNumber) {
                    heardSpeech = true
                    status?.text = "Hearing speech…"
                }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (number != requestNumber) return
                recording = false
                mic?.text = "Processing…"
                mic?.isEnabled = false
                status?.text = "Processing speech…"
                timeout(number, 12000, "Speech service did not return a result.")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (number != requestNumber) return
                val candidate = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (candidate.isNotBlank()) {
                    partial = candidate
                    heardSpeech = true
                    status?.text = "Hearing: " + candidate.take(100)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (number != requestNumber) return
                if (partial.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    finishWithTranscript(partial, "Partial text (please check)")
                    return
                }
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        if (!heardSpeech) "No speech detected (7). Wait until Listening appears, then speak."
                        else "Speech heard but words not recognised (7). Try a shorter phrase."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech received (6). Try again after Listening appears."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy (8). Retry in a moment."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech provider network problem ($error). Check internet."
                    else -> "Speech recognition error $error. Try again."
                }
                finishWithError(message)
            }
            override fun onResults(results: Bundle?) {
                if (number != requestNumber) return
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (transcript.isEmpty() && partial.isEmpty()) finishWithError("No words recognised.")
                else finishWithTranscript(transcript.ifBlank { partial },
                    if (transcript.isBlank()) "Partial text (please check)" else "Transcript ready")
            }
        })
        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
            timeout(number, 75000, "No response from speech service. Tap Dictate to retry.")
        } catch(t: Throwable) { finishWithError(t.message ?: "Could not start speech recognition.") }
    }
    private fun stopRecording() {
        if (!recording) return
        if (!ready) { status?.text = "Microphone still preparing…"; return }
        recording = false
        mic?.text = "Processing…"
        mic?.isEnabled = false
        status?.text = "Transcribing (up to 12 seconds)…"
        try { recognizer?.stopListening() }
        catch(t: Throwable) { finishWithError(t.message ?: "Could not finish recording."); return }
        timeout(requestNumber, 12000, "Speech provider did not respond after Stop. Try again.")
    }
    private fun finishWithTranscript(text: String, prefix: String) {
        endRecognition()
        latest = text
        copy?.visibility = View.VISIBLE
        status?.text = "$prefix: " + text.take(110)
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
