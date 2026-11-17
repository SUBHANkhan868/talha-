package com.subhan.sarah

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

private val http = OkHttpClient()
private val bg = Color(0xFF10111B)
private val panel = Color(0xFF1B1D2B)
private val violet = Color(0xFF9C8CFF)
private val prefsName = "sarah_private"

data class ChatLine(val user: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SarahApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SarahApp() {
    val context = LocalContext.current
    val prefs = remember { securePrefs(context) }
    val scope = rememberCoroutineScope()
    var googleKey by remember { mutableStateOf(prefs.getString("google", "") ?: "") }
    var elevenKey by remember { mutableStateOf(prefs.getString("eleven", "") ?: "") }
    var voiceId by remember { mutableStateOf(prefs.getString("voiceId", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var memory by remember { mutableStateOf(prefs.getString("memory", "Mera naam Subhan hai. Assistant ka naam Sarah hai. Subhan ne Sarah banayi hai.") ?: "") }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    var page by remember { mutableStateOf(if (googleKey.isBlank()) "setup" else "chat") }
    var lines by remember { mutableStateOf(loadChat(prefs)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(listOf<String>()) }
    var showWhatsApp by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var waMessage by remember { mutableStateOf("") }
    val colors = if (dark) darkColorScheme(primary = violet, background = bg, surface = panel, onBackground = Color.White, onSurface = Color.White)
                 else lightColorScheme(primary = Color(0xFF6254D9), background = Color(0xFFF5F4FB), surface = Color.White)

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) input = spoken
        }
    }
    val speechPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) try { speechLauncher.launch(dictationIntent()) } catch (_: Exception) { status = "Is device par voice recognition available nahi." }
        else Toast.makeText(context, "Microphone permission zaroori hai.", Toast.LENGTH_SHORT).show()
    }
    fun startVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { speechLauncher.launch(dictationIntent()) } catch (_: Exception) { status = "Is device par voice recognition available nahi." }
        } else speechPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun saveSettings() {
        prefs.edit().putString("google", googleKey.trim()).putString("eleven", elevenKey.trim())
            .putString("voiceId", voiceId.trim()).putString("model", model.trim()).putString("memory", memory)
            .putBoolean("dark", dark).apply()
    }
    fun refreshModels() {
        if (googleKey.isBlank()) { status = "Pehle Google AI Studio API key likhein."; return }
        busy = true; status = "Google ke available text models check ho rahe hain…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchModels(googleKey.trim()) }
            busy = false
            if (result.first.isNotEmpty()) {
                models = result.first
                if (model !in models) model = models.firstOrNull { it.contains("flash", true) } ?: models.first()
                status = "Models load ho gaye. Apna model select kar lein."
            } else status = result.second
        }
    }
    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || busy) return
        if (googleKey.isBlank() || model.isBlank()) { page = "setup"; status = "Setup mein Google API key aur model select karein."; return }
        input = ""; status = "Sarah soch rahi hai…"
        val updated = lines + ChatLine(true, clean)
        lines = updated; saveChat(prefs, updated); busy = true
        val lowered = clean.lowercase(Locale.ROOT)
        if (lowered.contains("whatsapp") && listOf("message", "msg", "bhej", "send").any { lowered.contains(it) }) {
            showWhatsApp = true
            waMessage = clean
        }
        val localResult = simpleCommand(context, clean)
        if (localResult != null) {
            val done = updated + ChatLine(false, localResult)
            lines = done; saveChat(prefs, done); busy = false; status = ""
            speakAnswer(context, localResult, elevenKey, voiceId)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { askGemini(googleKey.trim(), model, memory, updated.takeLast(10)) }
            busy = false
            val answer = result.first ?: "Maazrat, jawab hasil nahi hua. ${result.second}"
            val done = lines + ChatLine(false, answer)
            lines = done; saveChat(prefs, done); status = if (result.first == null) "API error" else ""
            speakAnswer(context, answer, elevenKey, voiceId)
        }
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background, topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(violet, CircleShape), contentAlignment = Alignment.Center) { Text("S", color = Color.White, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp)); Column { Text("Sarah", fontWeight = FontWeight.Bold); Text("Subhan ki personal assistant", fontSize = 11.sp, color = Color.Gray) }
                } },
                actions = {
                    IconButton(onClick = { page = if (page == "settings") "chat" else "settings" }) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = { dark = !dark; saveSettings() }) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (page) {
                    "setup" -> SetupScreen(googleKey, { googleKey = it }, model, { model = it }, models, { models = it }, elevenKey, { elevenKey = it }, voiceId, { voiceId = it }, memory, { memory = it }, status, busy, ::refreshModels, {
                        saveSettings()
                        if (googleKey.isNotBlank() && model.isNotBlank()) { page = "chat"; status = "Sarah tayyar hai, Subhan." }
                    })
                    "settings" -> SettingsScreen(googleKey, { googleKey = it }, model, { model = it }, models, elevenKey, { elevenKey = it }, voiceId, { voiceId = it }, memory, { memory = it }, dark, { dark = it }, status, busy, ::refreshModels, { saveSettings(); Toast.makeText(context, "Settings save ho gayi.", Toast.LENGTH_SHORT).show() })
                    else -> {
                        if (lines.isEmpty()) WelcomeCard()
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(lines) { line -> MessageBubble(line) }
                        }
                        if (status.isNotBlank()) Text(status, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { startVoice() }, modifier = Modifier.background(panel, CircleShape)) { Icon(Icons.Default.Mic, "Bol kar likhein", tint = violet) }
                            Spacer(Modifier.width(8.dp))
                            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Sarah se baat karein…") }, shape = RoundedCornerShape(24.dp), singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { sendMessage(input) }, enabled = !busy, modifier = Modifier.background(violet, CircleShape)) { Icon(Icons.Default.Send, "Send", tint = Color.White) }
                        }
                    }
                }
            }
        }
        if (showWhatsApp) AlertDialog(onDismissRequest = { showWhatsApp = false }, title = { Text("WhatsApp par bhejein") },
            text = { Column {
                Text("Sarah message WhatsApp mein tayyar karegi; bhejne se pehle aap khud confirm karenge.", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone number, country code ke sath") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(waMessage, { waMessage = it }, label = { Text("Message") })
            } }, confirmButton = { TextButton(onClick = {
                val digits = phone.filter { it.isDigit() }
                if (digits.isNotBlank()) try {
                    val url = "https://wa.me/$digits?text=" + URLEncoder.encode(waMessage, "UTF-8")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    showWhatsApp = false
                } catch (_: Exception) { status = "WhatsApp open nahi ho saka." }
            }) { Text("WhatsApp kholen") } }, dismissButton = { TextButton(onClick = { showWhatsApp = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SetupScreen(google: String, onGoogle: (String)->Unit, model: String, onModel: (String)->Unit, models: List<String>, setModels: (List<String>)->Unit,
                        eleven: String, onEleven: (String)->Unit, voice: String, onVoice: (String)->Unit, memory: String, onMemory: (String)->Unit,
                        status: String, busy: Boolean, refresh: ()->Unit, finish: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sarah ko setup karein", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Subhan, pehli dafa Google AI key add karein; phir Sarah available models se compatible model select karegi.", color = Color.Gray)
        OutlinedTextField(google, onGoogle, modifier = Modifier.fillMaxWidth(), label = { Text("Google Gemini API key *") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = refresh, enabled = !busy && google.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Check ho raha hai…" else "Google models check karein") }
        ModelPicker(model, onModel, models)
        OutlinedTextField(eleven, onEleven, modifier = Modifier.fillMaxWidth(), label = { Text("ElevenLabs API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(voice, onVoice, modifier = Modifier.fillMaxWidth(), label = { Text("ElevenLabs voice ID (optional)") }, singleLine = true)
        Text("ElevenLabs na ho to phone ki built-in voice use hogi. API keys sirf is phone ki app storage mein rakhi jati hain.", color = Color.Gray, fontSize = 12.sp)
        OutlinedTextField(memory, onMemory, modifier = Modifier.fillMaxWidth(), label = { Text("Sarah ki yaad / personal notes") }, minLines = 2)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        Button(onClick = finish, enabled = google.isNotBlank() && model.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Setup mukammal karein") }
        Text("Created by Subhan • Roman Urdu • Privacy-first", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(google: String, onGoogle: (String)->Unit, model: String, onModel: (String)->Unit, models: List<String>, eleven: String, onEleven: (String)->Unit, voice: String, onVoice: (String)->Unit,
                          memory: String, onMemory: (String)->Unit, dark: Boolean, onDark: (Boolean)->Unit, status: String, busy: Boolean, refresh: ()->Unit, save: ()->Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(google, onGoogle, modifier = Modifier.fillMaxWidth(), label = { Text("Google Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { Button(onClick = refresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Available models refresh karein") } }
        item { ModelPicker(model, onModel, models) }
        item { OutlinedTextField(eleven, onEleven, modifier = Modifier.fillMaxWidth(), label = { Text("ElevenLabs API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { OutlinedTextField(voice, onVoice, modifier = Modifier.fillMaxWidth(), label = { Text("ElevenLabs voice ID") }, singleLine = true) }
        item { OutlinedTextField(memory, onMemory, modifier = Modifier.fillMaxWidth(), label = { Text("Sarah ki yaad / personal notes") }, minLines = 3) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Dark mode", modifier = Modifier.weight(1f)); Switch(dark, onDark) } }
        item { Text("Wake word, notification reading, screen viewing aur accessibility ko Android alag user-consent permissions se control karta hai. Is starter version mein always-on wake word/background screen access shamil nahi.", color = Color.Gray, fontSize = 13.sp) }
        item { if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary) }
        item { Button(onClick = save, modifier = Modifier.fillMaxWidth()) { Text("Settings save karein") } }
        item { Text("About Sarah\nCreated by Subhan. Sarah Roman Urdu mein jawab dene ke liye set ki gayi hai.", color = Color.Gray) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(model: String, onModel: (String)->Unit, models: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    if (models.isNotEmpty()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(value = model, onValueChange = {}, readOnly = true, label = { Text("Gemini model") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { onModel(item); expanded = false }) }
            }
        }
    } else {
        OutlinedTextField(value = model, onValueChange = {}, readOnly = true, label = { Text("Gemini model") }, supportingText = { Text("Mismatch se bachne ke liye pehle available models refresh karein.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}

@Composable
private fun WelcomeCard() {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).background(panel, RoundedCornerShape(24.dp)).padding(20.dp)) {
        Text("Assalam-o-alaikum Subhan!", color = violet, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp)); Text("Main Sarah hoon — aapki Roman Urdu assistant. Aap type karein ya mic se bol kar baat shuru karein.")
        Spacer(Modifier.height(12.dp)); Text("Misal: ‘YouTube par Coke Studio search karo’ ya ‘WhatsApp par message likho…’", color = Color.Gray, fontSize = 13.sp)
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 310.dp).background(if (line.user) Color(0xFF5146A8) else panel, RoundedCornerShape(18.dp)).padding(13.dp)) {
            Text(if (line.user) "Subhan" else "Sarah", fontSize = 10.sp, color = Color.LightGray, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp)); Text(line.text, color = Color.White)
        }
    }
}

private fun dictationIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Sarah ko bol kar batayein")
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
}
private fun launchDictation(activity: Activity) {
    try { activity.startActivityForResult(dictationIntent(), 0x51A) } catch (_: Exception) { Toast.makeText(activity, "Voice recognition open nahi hui.", Toast.LENGTH_SHORT).show() }
}

private fun fetchModels(key: String): Pair<List<String>, String> {
    return try {
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${Uri.encode(key)}"
        val response = http.newCall(Request.Builder().url(url).get().build()).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) Pair(emptyList(), apiError(body, response.code)) else {
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val found = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) {
                    val name = item.optString("name").removePrefix("models/")
                    if (name.isNotBlank()) found.add(name)
                }
            }
            Pair(found.sorted(), if (found.isEmpty()) "Is key ke liye generateContent models nahi mile." else "")
        }
    } catch (e: Exception) {
        Pair(emptyList(), "Model list nahi aa saki: ${e.message ?: "network error"}")
    }
}

private fun askGemini(key: String, model: String, memory: String, history: List<ChatLine>): Pair<String?, String> {
    return try {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${Uri.encode(model)}:generateContent?key=${Uri.encode(key)}"
        val contents = JSONArray()
        val system = "Tum Sarah ho, Subhan ki personal AI assistant. Subhan ne tumhein banaya hai. Roman Urdu mein fitri, mukhtasar aur narm lehje mein jawab do. User ka naam Subhan hai. User ki saved yaad: $memory. Har jawab aam tor par 1-3 jumle rakho. Phone par koi irreversible action ya message bhejne ka dawa mat karo."
        history.takeLast(10).forEach { line -> contents.put(JSONObject().put("role", if (line.user) "user" else "model").put("parts", JSONArray().put(JSONObject().put("text", line.text)))) }
        val payload = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents).put("generationConfig", JSONObject().put("temperature", 0.65).put("maxOutputTokens", 350))
        val request = Request.Builder().url(url).post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val response = http.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) Pair(null, apiError(body, response.code)) else {
            val root = JSONObject(body)
            val text = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            if (text.isNullOrBlank()) Pair(null, "Model ne text jawab nahi diya.") else Pair(text, "")
        }
    } catch (e: Exception) {
        Pair(null, "Network error: ${e.message ?: "connection check karein"}")
    }
}

private fun apiError(body: String, code: Int): String {
    val message = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
    return when (code) {
        400 -> "API request/key ya model ghalat hai. Model list dobara refresh karein. ${message.orEmpty()}"
        401, 403 -> "API key invalid hai ya API access/billing enabled nahi. Google AI Studio key check karein."
        429 -> "Google API quota/rate limit mukammal hai. Thori dair baad koshish karein."
        else -> "Google API error ($code). ${message.orEmpty()}"
    }
}

private fun simpleCommand(context: android.content.Context, text: String): String? {
    val q = text.lowercase(Locale.ROOT)
    if (q.contains("home screen") || q.contains("home par") || q.contains("home screen par")) {
        try { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Aapko home screen par le aayi hoon." } catch (_: Exception) { return "Home screen open nahi ho saki." }
    }
    if ((q.contains("youtube") || q.contains("you tube")) && (q.contains("play") || q.contains("search") || q.contains("song") || q.contains("gaana"))) {
        val query = text.replace(Regex("(?i)youtube|you tube|par|play|search|song|gaana|chala do|laga do|karo"), " ").trim().ifBlank { "music" }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}")))
            return "YouTube par ‘$query’ search khol di hai. Video ya song aap select kar lein."
        } catch (_: Exception) { return "YouTube link open nahi ho saka." }
    }
    if (q.contains("whatsapp") && (q.contains("message") || q.contains("msg") || q.contains("bhej") || q.contains("send"))) {
        Toast.makeText(context, "WhatsApp message bhejne ke liye recipient aur text chat mein confirm karein.", Toast.LENGTH_LONG).show()
        return "WhatsApp message yahan se khud se send nahi hota. Filhal Settings ke baad chat se recipient aur message tayyar karke WhatsApp mein aapke final send confirmation ka intezar karein."
    }
    if (q.contains("unlock") || q.contains("phone khol")) return "Main secure lock screen ko unlock nahi kar sakti — Android is ki ijazat nahi deta. Aap PIN, fingerprint ya face unlock use karein."
    if (q.contains("lock phone") || q.contains("phone lock")) return "Phone lock karne ke liye Android mein alag Device Admin consent chahiye; is starter build mein ye action shamil nahi."
    return null
}

private fun speakAnswer(context: android.content.Context, text: String, apiKey: String, voiceId: String) {
    if (apiKey.isNotBlank() && voiceId.isNotBlank()) {
        Thread {
            try {
                val json = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").put("voice_settings", JSONObject().put("stability", 0.55).put("similarity_boost", 0.78))
                val request = Request.Builder().url("https://api.elevenlabs.io/v1/text-to-speech/${Uri.encode(voiceId)}")
                    .addHeader("xi-api-key", apiKey).addHeader("Accept", "audio/mpeg")
                    .post(json.toString().toRequestBody("application/json".toMediaType())).build()
                val response = http.newCall(request).execute()
                if (response.isSuccessful) {
                    val file = java.io.File(context.cacheDir, "sarah_voice.mp3")
                    response.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try { android.media.MediaPlayer().apply { setDataSource(file.absolutePath); setOnPreparedListener { it.start() }; prepareAsync() } }
                        catch (_: Exception) { fallbackSpeak(context, text) }
                    }
                } else fallbackSpeak(context, text)
            } catch (_: Exception) { fallbackSpeak(context, text) }
        }.start()
    } else fallbackSpeak(context, text)
}
private fun fallbackSpeak(context: android.content.Context, text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        val tts = TextToSpeech(context) { result -> if (result == TextToSpeech.SUCCESS) {
            // TTS lifecycle is kept local to this utterance; Roman Urdu pronunciation varies by device engine.
            val engine = ttsHolder
            engine?.language = Locale("ur", "PK")
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sarah-${System.currentTimeMillis()}")
        } }
        ttsHolder = tts
    }
}
@Volatile private var ttsHolder: TextToSpeech? = null

private fun securePrefs(context: android.content.Context): android.content.SharedPreferences = try {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(
        context, prefsName, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
} catch (_: Exception) {
    // Keep the app usable on devices whose crypto provider cannot initialize.
    context.getSharedPreferences(prefsName, 0)
}

private fun saveChat(prefs: android.content.SharedPreferences, lines: List<ChatLine>) {
    val arr = JSONArray(); lines.takeLast(60).forEach { arr.put(JSONObject().put("user", it.user).put("text", it.text)) }
    prefs.edit().putString("chat", arr.toString()).apply()
}
private fun loadChat(prefs: android.content.SharedPreferences): List<ChatLine> = try {
    val arr = JSONArray(prefs.getString("chat", "[]"))
    (0 until arr.length()).map { val obj = arr.getJSONObject(it); ChatLine(obj.optBoolean("user"), obj.optString("text")) }
} catch (_: Exception) { emptyList() }
