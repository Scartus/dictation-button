#!/usr/bin/env python3
"""Fail-closed v2 reliability overrides applied to the archived source during CI."""
from pathlib import Path

root=Path("android-ime/app/src/main/java/com/example/dictationbutton")
def replace(file, old, new):
    path=root/file
    source=path.read_text()
    if old not in source:
        raise SystemExit(f"ERROR: expected source was changed: {file}: {old[:65]!r}")
    path.write_text(source.replace(old,new,1))

replace("WavRecorder.kt",
'''        writeHeader(file, 0, sampleRate)
        audio.startRecording()

        thread = Thread {''',
'''        try {
            writeHeader(file, 0, sampleRate)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        } catch (t: Throwable) {
            running.set(false)
            try { audio.release() } catch (_: Throwable) {}
            recorder = null
            output = null
            file.delete()
            throw t
        }

        thread = Thread {''')

replace("EncryptedAudioStore.kt",
'''        out.outputStream().use { stream ->
            stream.write(cipher.iv)
            javax.crypto.CipherOutputStream(stream, cipher).use { encrypted ->
                plaintext.inputStream().use { it.copyTo(encrypted) }
            }
        }
        plaintext.delete()''',
'''        try {
            out.outputStream().use { stream ->
                stream.write(cipher.iv)
                javax.crypto.CipherOutputStream(stream, cipher).use { encrypted ->
                    plaintext.inputStream().use { it.copyTo(encrypted) }
                }
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        check(plaintext.delete()) { "Encrypted audio was saved but temporary WAV could not be removed" }''')

replace("EncryptedAudioStore.kt",
'''        encrypted.inputStream().use { input ->
            val iv = ByteArray(IV_BYTES)
            require(input.read(iv) == IV_BYTES) { "Saved recording is damaged" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            javax.crypto.CipherInputStream(input, cipher).use { decrypted ->
                tmp.outputStream().use { decrypted.copyTo(it) }
            }
        }
        return tmp''',
'''        try {
            encrypted.inputStream().use { input ->
                val iv = ByteArray(IV_BYTES)
                require(input.read(iv) == IV_BYTES) { "Saved recording is damaged" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                javax.crypto.CipherInputStream(input, cipher).use { decrypted ->
                    tmp.outputStream().use { decrypted.copyTo(it) }
                }
            }
            return tmp
        } catch (t: Throwable) {
            tmp.delete() // Never leave unauthenticated or partial plaintext lying around
            throw t
        }''')

replace("DictationImeService.kt",
'''        if (err != null) {
            preserveFailed(s.file)
            return fail("Recording could not be finalized. It was encrypted and kept for Retry.", delete = false)
        }''',
'''        if (err != null) {
            val saved = preserveFailed(s.file)
            return fail(if (saved) "Recording could not be finalized. Encrypted audio kept for Retry."
                else "Recording failed. Audio could not be saved safely and was deleted.", delete = false)
        }''')

replace("DictationImeService.kt",
'''            preserveFailed(s.file)
            return fail("No internet connection. The recording was encrypted and saved; tap Retry when you're online.", delete = false)''',
'''            val saved = preserveFailed(s.file)
            return fail(if (saved) "Offline. Encrypted recording saved; Retry when online."
                else "Offline. Audio could not be saved securely and was deleted.", delete = false)''')

replace("DictationImeService.kt",
'''        if (!isRetry) preserveFailed(s.file) else s.file.delete()
        state = State.IDLE''',
'''        val audioSaved = if (!isRetry) preserveFailed(s.file) else {
            s.file.delete()
            failedAudio?.exists() == true
        }
        state = State.IDLE''')

replace("DictationImeService.kt",
'''        setStatus(message + requestSuffix, announce = true)
        render()''',
'''        setStatus((if (audioSaved) message else
            "Transcription failed and audio could not be saved securely; temporary audio was deleted.") + requestSuffix, announce = true)
        render()''')

replace("DictationImeService.kt",
'''    private fun preserveFailed(src: File) {
        if (!src.exists()) return
        try {
            failedAudio = EncryptedAudioStore.save(this, src)
        } catch (_: Throwable) {
            src.delete()
            failedAudio = null
            setStatus("Could not safely save the failed recording; temporary audio was deleted.", announce = true)
        }
    }''',
'''    private fun preserveFailed(src: File): Boolean {
        if (!src.exists()) return false
        return try {
            failedAudio = EncryptedAudioStore.save(this, src)
            true
        } catch (_: Throwable) {
            src.delete()
            failedAudio = null
            false
        }
    }''')

replace("DictationImeService.kt",
'''        if (!restarting) generation.incrementAndGet()''',
'''        // A restarted editor may be a different field even if the app reports fieldId=0.
        // Fail closed: never auto-insert a transcript created before any editor transition.
        generation.incrementAndGet()''')

replace("DictationImeService.kt",
'''            f.delete(); session = null; recorder = null; state = State.IDLE''',
'''            try { r.cancel() } catch (_: Throwable) {}
            f.delete(); session = null; recorder = null; state = State.IDLE''')

print("Applied 10 reliability hardening changes to the extracted project.")

# Android platform speech recognition is the default: SIWC plan usage does not
# currently support audio input. Retain the original experimental code for review.
replace("DictationImeService.kt",
'''    private var showManageUsage = false''',
'''    private var showManageUsage = false
    private var androidSpeech: AndroidSpeechBridge? = null
    private var speechGeneration = 0L
    private var speechPackage: String? = null
    private var speechField = 0''')

replace("DictationImeService.kt",
'''                State.IDLE -> beginRecording()
                State.RECORDING -> finishRecording()''',
'''                State.IDLE -> beginSystemSpeech()
                State.RECORDING -> if (androidSpeech != null) finishSystemSpeech() else finishRecording()''')

replace("DictationImeService.kt",
'''    private fun beginRecording() {''',
'''    private fun beginSystemSpeech() {
        if (state != State.IDLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Open Dictation Button and allow microphone access first.")
            return
        }
        val info = currentInputEditorInfo
        if (info == null || isPasswordField(info.inputType)) {
            setStatus("Dictation is disabled for password and PIN fields.")
            return
        }
        speechGeneration = generation.get()
        speechPackage = info.packageName
        speechField = info.fieldId
        state = State.STARTING
        render()
        val bridge = AndroidSpeechBridge(this,
            onResult = { words -> main.post { completeSystemSpeech(words) } },
            onFailure = { message -> main.post { systemSpeechFailed(message) } })
        androidSpeech = bridge
        try {
            bridge.begin()
            state = State.RECORDING
            recordingStartedAt = System.currentTimeMillis()
            main.removeCallbacks(timerTick)
            main.post(timerTick)
            setStatus("Listening using Android speech recognition. Speech may be processed online by your configured provider.")
            render()
        } catch (t: Throwable) {
            bridge.cancel()
            androidSpeech = null
            state = State.IDLE
            setStatus("Android speech recognition unavailable: ${t.message ?: "unknown error"}", announce = true)
            render()
        }
    }

    private fun finishSystemSpeech() {
        if (state != State.RECORDING || androidSpeech == null) return
        state = State.TRANSCRIBING
        main.removeCallbacks(timerTick)
        androidSpeech?.finish()
        render()
        main.postDelayed({
            if (androidSpeech != null && state == State.TRANSCRIBING) {
                systemSpeechFailed("Speech recognizer timed out; try again.")
            }
        }, 15000)
    }

    private fun systemSpeechFailed(message: String) {
        if (androidSpeech == null) return
        androidSpeech?.cancel()
        androidSpeech = null
        state = State.IDLE
        main.removeCallbacks(timerTick)
        setStatus(message, announce = true)
        render()
    }

    private fun completeSystemSpeech(text: String) {
        if (androidSpeech == null || (state != State.RECORDING && state != State.TRANSCRIBING)) return
        androidSpeech?.cancel()
        androidSpeech = null
        main.removeCallbacks(timerTick)
        val info = currentInputEditorInfo
        val sameEditor = generation.get() == speechGeneration &&
            info != null && info.packageName == speechPackage &&
            speechField != 0 && info.fieldId != 0 && info.fieldId == speechField &&
            !isPasswordField(info.inputType)
        pendingTranscript = text
        RecoveryTextStore.save(this, text)
        val inserted = if (sameEditor) currentInputConnection?.commitText(text, 1) == true else false
        state = State.IDLE
        if (inserted) {
            pendingTranscript = null
            RecoveryTextStore.clear(this)
            setStatus("Dictation inserted.", announce = true)
        } else {
            setStatus("Transcript saved here. Tap Insert to put it in the current text field.", announce = true)
        }
        render()
    }

    private fun beginRecording() {''')

replace("DictationImeService.kt",
'''        try { recorder?.cancel() } catch (_: Throwable) {}''',
'''        try { androidSpeech?.cancel() } catch (_: Throwable) {}
        androidSpeech = null
        try { recorder?.cancel() } catch (_: Throwable) {}''')

replace("DictationImeService.kt",
'''        else if (state == State.TRANSCRIBING) setStatus("Transcribing using your ChatGPT plan. No text is inserted until completion.")''',
'''        else if (state == State.TRANSCRIBING) setStatus("Processing speech. No text inserted until recognition completes.")''')

print("Default dictation now uses the Android speech recognizer; ChatGPT-plan audio is disabled.")

manifest=Path("android-ime/app/src/main/AndroidManifest.xml")
m=manifest.read_text()
needle='    <application'
assert needle in m
m=m.replace(needle, '''    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>

    <application''',1)
manifest.write_text(m)
print("Declared Android speech-service package visibility.")

# Remove ChatGPT sign-in from required setup for the system recognizer.
replace("SetupActivity.kt",
'''        testField.visibility = if (connected && mic && imeEnabled) View.VISIBLE else View.GONE''',
'''        testField.visibility = if (mic && imeEnabled) View.VISIBLE else View.GONE''')
replace("SetupActivity.kt",
'''        micPermission.isEnabled = connected && !mic
        enableIme.isEnabled = connected && mic && !imeEnabled
        chooseIme.isEnabled = connected && mic && imeEnabled''',
'''        micPermission.isEnabled = !mic
        enableIme.isEnabled = mic && !imeEnabled
        chooseIme.isEnabled = mic && imeEnabled''')
replace("SetupActivity.kt",
'''            !connected -> "1 of 4 · Connect your ChatGPT plan. No API key or separate API billing setup is required."
            !mic -> "2 of 4 · ChatGPT connected. Allow microphone access."
            !imeEnabled -> "3 of 4 · Enable Dictation Button in Android keyboard settings."
            !imeSelected -> "4 of 4 · Choose Dictation Button as the current keyboard, then tap the test field below."''',
'''            !mic -> "1 of 3 · Allow microphone access. No account or API key is needed for Android speech recognition."
            !imeEnabled -> "2 of 3 · Enable Dictation Button in Android keyboard settings."
            !imeSelected -> "3 of 3 · Choose Dictation Button as your keyboard, then tap the test field below."''')
layout=Path("android-ime/app/src/main/res/layout/activity_setup.xml")
xml=layout.read_text()
xml=xml.replace("Accurate system-wide dictation powered by your ChatGPT plan. Setup is guided once; normal use is just Dictate, speak, Stop.",
"Android speech dictation. No ChatGPT sign-in or API key required. Enable the microphone, choose this keyboard, then Dictate, speak, Stop.")
xml=xml.replace("Privacy: recording begins only after you tap Dictate. Your recording is sent to OpenAI to produce the transcript using your ChatGPT plan. Successful temporary audio is deleted after transcription. Failed audio recovery is encrypted on this phone. ChatGPT sign-in does not give this app access to your ChatGPT conversations.",
"Privacy: tapping Dictate starts Android speech recognition using your configured provider. That provider may process audio online depending on its settings. The app does not send audio to OpenAI through ChatGPT plan usage. You can review or delete locally recovered text.")
xml=xml.replace('android:text="Continue with ChatGPT"','android:text="Experimental ChatGPT sign-in (not required)"')
layout.write_text(xml)
print("Setup no longer blocks dictation behind unsupported ChatGPT-plan audio.")
