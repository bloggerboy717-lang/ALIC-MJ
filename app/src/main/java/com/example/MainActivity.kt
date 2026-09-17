package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.content.Context
import android.content.SharedPreferences
import android.provider.ContactsContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var audioPlayer: AudioPlayer
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var geminiClient: GeminiLiveClient
    
    private var isConnected by mutableStateOf(false)
    private var assistantState by mutableStateOf("IDLE")

    private lateinit var sharedPreferences: SharedPreferences
    private var storedApiKey by mutableStateOf("")
    private var showApiKeyDialog by mutableStateOf(false)

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
                connectAndStart()
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sharedPreferences = getSharedPreferences("MJ_PREFS", Context.MODE_PRIVATE)
        storedApiKey = sharedPreferences.getString("GEMINI_API_KEY", "") ?: ""
        
        if (storedApiKey.isEmpty()) {
            showApiKeyDialog = true
        }
        
        audioPlayer = AudioPlayer()
        audioRecorder = AudioRecorder { pcmChunk ->
            if (assistantState == "LISTENING" || assistantState == "SPEAKING") {
                geminiClient.sendAudio(pcmChunk)
            }
        }
        
        geminiClient = GeminiLiveClient(
            onAudioReceived = { pcmChunk ->
                audioPlayer.playAudioChunk(pcmChunk)
            },
            onStateChanged = { state ->
                assistantState = state
                if (state == "CONNECTED") isConnected = true
                if (state == "IDLE" || state.startsWith("ERROR")) isConnected = false
                
                if (state == "INTERRUPTED") {
                    audioPlayer.stop() // Clear queue and stop current playback
                }
            },
            onToolCall = { toolCall ->
                handleToolCall(toolCall)
            }
        )

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showApiKeyDialog) {
                        ApiKeyDialog(
                            currentKey = storedApiKey,
                            onKeySubmitted = { key ->
                                sharedPreferences.edit().putString("GEMINI_API_KEY", key).apply()
                                storedApiKey = key
                                showApiKeyDialog = false
                            },
                            onDismiss = {
                                if (storedApiKey.isNotEmpty()) showApiKeyDialog = false
                            }
                        )
                    } else {
                        MJScreen(
                            state = assistantState,
                            onToggle = {
                                if (isConnected) {
                                    disconnectAndStop()
                                } else {
                                    checkPermissionsAndStart()
                                }
                            },
                            onSettingsClick = {
                                showApiKeyDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            connectAndStart()
        } else {
            requestPermissionsLauncher.launch(permissions)
        }
    }

    private fun connectAndStart() {
        val apiKey = storedApiKey
        if (apiKey.isEmpty()) {
            showApiKeyDialog = true
            return
        }
        
        assistantState = "CONNECTING..."
        geminiClient.connect(apiKey)
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            audioRecorder.start()
        }
    }

    private fun disconnectAndStop() {
        geminiClient.disconnect()
        audioRecorder.stop()
        audioPlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAndStop()
        audioPlayer.release()
    }

    private fun handleToolCall(toolCall: JSONObject) {
        try {
            val functionCalls = toolCall.getJSONArray("functionCalls")
            val responses = JSONArray()

            for (i in 0 until functionCalls.length()) {
                val call = functionCalls.getJSONObject(i)
                val id = call.getString("id")
                val name = call.getString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                
                var success = false
                var errorMsg = ""
                
                when (name) {
                    "openWhatsApp" -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                            success = true
                        } else {
                            errorMsg = "WhatsApp is not installed."
                        }
                    }
                    "makeCall" -> {
                        val phone = args.optString("phoneNumber")
                        if (phone.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                            startActivity(intent)
                            success = true
                        } else {
                            errorMsg = "No phone number provided."
                        }
                    }
                    "callContact" -> {
                        val contactName = args.optString("contactName")
                        val number = findContactNumber(contactName)
                        if (number != null) {
                            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                            startActivity(intent)
                            success = true
                        } else {
                            errorMsg = "Contact not found or multiple found."
                        }
                    }
                    "openApp" -> {
                        val appName = args.optString("appName").lowercase()
                        val pkg = when {
                            appName.contains("youtube") -> "com.google.android.youtube"
                            appName.contains("instagram") -> "com.instagram.android"
                            appName.contains("chrome") -> "com.android.chrome"
                            else -> null
                        }
                        if (pkg != null) {
                            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                                success = true
                            } else {
                                errorMsg = "App not installed."
                            }
                        } else {
                            errorMsg = "App not supported."
                        }
                    }
                    else -> {
                        errorMsg = "Unknown tool."
                    }
                }
                
                val responseObj = JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("response", JSONObject().apply {
                        if (success) put("result", "Success")
                        else put("error", errorMsg)
                    })
                }
                responses.put(responseObj)
            }
            
            geminiClient.sendToolResponse(responses)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("Range")
    private fun findContactNumber(name: String): String? {
        if (name.isEmpty()) return null
        val contentResolver = contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        var number: String? = null
        var count = 0
        if (cursor != null) {
            while (cursor.moveToNext()) {
                number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                count++
            }
            cursor.close()
        }
        return if (count == 1) number else null
    }
}

@Composable
fun MJScreen(state: String, onToggle: () -> Unit, onSettingsClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == "LISTENING" || state == "SPEAKING") 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "M.J",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Box(
            modifier = Modifier
                .size(150.dp)
                .scale(scale)
                .background(
                    color = when (state) {
                        "LISTENING" -> Color(0xFF4CAF50)
                        "SPEAKING" -> Color(0xFF2196F3)
                        "ERROR" -> Color(0xFFF44336)
                        "CONNECTING..." -> Color(0xFFFFC107)
                        else -> Color.Gray
                    },
                    shape = CircleShape
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state == "IDLE" || state.startsWith("ERROR")) "TAP TO START" else state,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "State: $state",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun ApiKeyDialog(currentKey: String, onKeySubmitted: (String) -> Unit, onDismiss: () -> Unit) {
    var keyInput by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Gemini API Key") },
        text = {
            Column {
                Text("Please enter your Google Gemini API Key to use M.J. Your key will be saved locally on your device.")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onKeySubmitted(keyInput) },
                enabled = keyInput.isNotBlank()
            ) {
                Text("Save & Continue")
            }
        },
        dismissButton = {
            if (currentKey.isNotEmpty()) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
