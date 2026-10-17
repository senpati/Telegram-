package com.example.telegramotomesaj

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ButtonItem(var text: String = "", var url: String = "")

data class TaskItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var chatId: String = "",
    var intervalMinutes: Int = 60,
    var messageText: String = "",
    var imagePath: String? = null,
    var buttons: List<ButtonItem> = emptyList(),
    var isActive: Boolean = false,
    var lastSentTime: Long = 0L
)

object TaskRepository {
    private const val PREF_NAME = "telegram_tasks_prefs"
    private const val KEY_BOT_TOKEN = "bot_token"
    private const val KEY_TASKS = "tasks_json"
    private val gson = Gson()

    fun getBotToken(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_BOT_TOKEN, "") ?: ""

    fun saveBotToken(context: Context, token: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_BOT_TOKEN, token.trim()).apply()
    }

    fun getTasks(context: Context): MutableList<TaskItem> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_TASKS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<TaskItem>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveTasks(context: Context, tasks: List<TaskItem>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_TASKS, gson.toJson(tasks)).apply()
    }

    fun copyUriToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.filesDir, "img_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

object TelegramSender {
    private val client = OkHttpClient()

    private fun buildReplyMarkup(buttons: List<ButtonItem>): String? {
        val validButtons = buttons.filter { it.text.isNotBlank() && it.url.isNotBlank() }
        if (validButtons.isEmpty()) return null
        val inlineKeyboard = JSONArray()
        for (btn in validButtons) {
            val row = JSONArray()
            row.put(JSONObject().apply {
                put("text", btn.text.trim())
                put("url", btn.url.trim())
            })
            inlineKeyboard.put(row)
        }
        return JSONObject().put("inline_keyboard", inlineKeyboard).toString()
    }

    suspend fun sendTask(botToken: String, task: TaskItem): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || task.chatId.isBlank()) return@withContext false
        val replyMarkup = buildReplyMarkup(task.buttons)
        val hasImage = !task.imagePath.isNullOrBlank() && File(task.imagePath!!).exists()
        val hasText = task.messageText.isNotBlank()
        if (!hasImage && !hasText) return@withContext false

        try {
            val url = if (hasImage) "https://api.telegram.org/bot$botToken/sendPhoto" else "https://api.telegram.org/bot$botToken/sendMessage"
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", task.chatId.trim())
                .addFormDataPart("parse_mode", "HTML")

            if (hasImage) {
                val file = File(task.imagePath!!)
                bodyBuilder.addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                if (hasText) bodyBuilder.addFormDataPart("caption", task.messageText)
            } else {
                bodyBuilder.addFormDataPart("text", task.messageText)
            }
            if (replyMarkup != null) bodyBuilder.addFormDataPart("reply_markup", replyMarkup)

            val request = Request.Builder().url(url).post(bodyBuilder.build()).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

class SchedulerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val channelId = "oto_mesaj_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Oto Mesaj Servisi", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Telegram Oto Görev Aktif")
            .setContentText("Görevler arka planda takip ediliyor...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)

        serviceScope.launch {
            while (isActive) {
                val token = TaskRepository.getBotToken(applicationContext)
                val tasks = TaskRepository.getTasks(applicationContext)
                val now = System.currentTimeMillis()
                var changed = false

                for (task in tasks) {
                    if (task.isActive && now - task.lastSentTime >= task.intervalMinutes * 60_000L) {
                        if (TelegramSender.sendTask(token, task)) {
                            task.lastSentTime = now
                            changed = true
                        }
                    }
                }
                if (changed) TaskRepository.saveTasks(applicationContext, tasks)
                delay(20_000L)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, SchedulerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var botToken by remember { mutableStateOf(TaskRepository.getBotToken(context)) }
    var tasks by remember { mutableStateOf(TaskRepository.getTasks(context).toList()) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Telegram Oto Görev Paneli", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingTask = TaskItem(); isCreatingNew = true }) {
                Icon(Icons.Default.Add, contentDescription = "Yeni Görev")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                value = botToken,
                onValueChange = { botToken = it; TaskRepository.saveBotToken(context, it) },
                label = { Text("Bot Token (@BotFather)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Görev Listesi (${tasks.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tasks, key = { it.id }) { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.title.ifBlank { "İsimsiz Görev" }, fontWeight = FontWeight.Bold)
                                    Text("ID: ${task.chatId} • ${task.intervalMinutes} dk", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = task.isActive, onCheckedChange = { active ->
                                    tasks = tasks.map { if (it.id == task.id) it.copy(isActive = active) else it }
                                    TaskRepository.saveTasks(context, tasks)
                                })
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val ok = TelegramSender.sendTask(botToken, task)
                                        Toast.makeText(context, if (ok) "✅ Gönderildi!" else "❌ Hata!", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Text("Test")
                                }
                                IconButton(onClick = { editingTask = task; isCreatingNew = false }) { Icon(Icons.Default.Edit, null) }
                                IconButton(onClick = {
                                    tasks = tasks.filterNot { it.id == task.id }
                                    TaskRepository.saveTasks(context, tasks)
                                }) { Icon(Icons.Default.Delete, null) }
                            }
                        }
                    }
                }
            }
        }

        if (editingTask != null) {
            TaskEditDialog(
                initialTask = editingTask!!,
                onDismiss = { editingTask = null },
                onSave = { saved ->
                    tasks = if (isCreatingNew) tasks + saved else tasks.map { if (it.id == saved.id) saved else it }
                    TaskRepository.saveTasks(context, tasks)
                    editingTask = null
                }
            )
        }
    }
}

@Composable
fun TaskEditDialog(initialTask: TaskItem, onDismiss: () -> Unit, onSave: (TaskItem) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTask.title) }
    var chatId by remember { mutableStateOf(initialTask.chatId) }
    var intervalText by remember { mutableStateOf(initialTask.intervalMinutes.toString()) }
    var messageText by remember { mutableStateOf(initialTask.messageText) }
    var imagePath by remember { mutableStateOf(initialTask.imagePath) }
    var buttons by remember { mutableStateOf(initialTask.buttons) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) imagePath = TaskRepository.copyUriToInternalStorage(context, uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Görev Ayarları") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Görev Adı") }, singleLine = true)
                OutlinedTextField(value = chatId, onValueChange = { chatId = it }, label = { Text("Grup ID (-100...)") }, singleLine = true)
                OutlinedTextField(
                    value = intervalText, onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                    label = { Text("Dakika") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch("image/*") }) { Text(if (imagePath == null) "🖼 Görsel Seç" else "🖼 Değiştir") }
                    if (imagePath != null) TextButton(onClick = { imagePath = null }) { Text("❌ Sil") }
                }
                OutlinedTextField(value = messageText, onValueChange = { messageText = it }, label = { Text("Yazı (İsteğe Bağlı)") }, minLines = 3)
                buttons.forEachIndexed { i, btn ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = btn.text, onValueChange = { t -> buttons = buttons.toMutableList().also { it[i] = it[i].copy(text = t) } }, label = { Text("Buton") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = btn.url, onValueChange = { u -> buttons = buttons.toMutableList().also { it[i] = it[i].copy(url = u) } }, label = { Text("Link") }, modifier = Modifier.weight(1f), singleLine = true)
                        IconButton(onClick = { buttons = buttons.toMutableList().also { it.removeAt(i) } }) { Icon(Icons.Default.Delete, null) }
                    }
                }
                OutlinedButton(onClick = { buttons = buttons + ButtonItem() }, modifier = Modifier.fillMaxWidth()) { Text("+ Buton Ekle") }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(initialTask.copy(title = title, chatId = chatId, intervalMinutes = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 60, messageText = messageText, imagePath = imagePath, buttons = buttons))
            }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}
