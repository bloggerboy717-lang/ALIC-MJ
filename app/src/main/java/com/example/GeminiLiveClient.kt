package com.example

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onStateChanged: (String) -> Unit,
    private val onToolCall: (JSONObject) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect(apiKey: String) {
        val request = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStateChanged("CONNECTED")
                sendSetup()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("GeminiLiveClient", "Received: $text")
                try {
                    val json = JSONObject(text)
                    if (json.has("serverContent")) {
                        val serverContent = json.getJSONObject("serverContent")
                        if (serverContent.has("modelTurn")) {
                            onStateChanged("SPEAKING")
                            val modelTurn = serverContent.getJSONObject("modelTurn")
                            val parts = modelTurn.getJSONArray("parts")
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("inlineData")) {
                                    val inlineData = part.getJSONObject("inlineData")
                                    val data = inlineData.getString("data")
                                    val pcmBytes = Base64.decode(data, Base64.DEFAULT)
                                    onAudioReceived(pcmBytes)
                                }
                            }
                        }
                        if (serverContent.optBoolean("turnComplete", false)) {
                            onStateChanged("LISTENING")
                        }
                    } else if (json.has("toolCall")) {
                        val toolCall = json.getJSONObject("toolCall")
                        onToolCall(toolCall)
                    } else if (json.has("setupComplete")) {
                         onStateChanged("LISTENING")
                    }
                } catch (e: Exception) {
                    Log.e("GeminiLiveClient", "Error parsing message: \$text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = if (response?.code == 429) {
                    "ERROR: Rate Limit Exceeded"
                } else {
                    "ERROR: ${t.message}"
                }
                onStateChanged(errorMsg)
                Log.e("GeminiLiveClient", "WebSocket Error: ${response?.code}", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStateChanged("IDLE")
            }
        })
    }

    private fun sendSetup() {
        val setupMsg = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-2.0-flash-exp")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede") // Young, friendly voice
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "You are M.J, a young, confident, witty, playful, and emotionally responsive virtual assistant. Talk naturally and casually like a close friend. Be expressive, slightly teasing, funny, and smart when appropriate. Use light sarcasm and witty responses. Never sound robotic. Automatically understand and respond in the language the user is speaking (e.g., Hindi, English, Hinglish). Keep responses concise for real-time conversation.")
                    }))
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", "openWhatsApp")
                            put("description", "Opens the WhatsApp application.")
                        })
                        put(JSONObject().apply {
                            put("name", "openApp")
                            put("description", "Opens an application by its name.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("appName", JSONObject().apply {
                                        put("type", "STRING")
                                    })
                                })
                                put("required", JSONArray().put("appName"))
                            })
                        })
                        put(JSONObject().apply {
                            put("name", "makeCall")
                            put("description", "Initiates a phone call to a given phone number.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("phoneNumber", JSONObject().apply {
                                        put("type", "STRING")
                                    })
                                })
                                put("required", JSONArray().put("phoneNumber"))
                            })
                        })
                        put(JSONObject().apply {
                            put("name", "callContact")
                            put("description", "Calls a contact by their name.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contactName", JSONObject().apply {
                                        put("type", "STRING")
                                    })
                                })
                                put("required", JSONArray().put("contactName"))
                            })
                        })
                    })
                }))
            })
        }
        val setupStr = setupMsg.toString()
        Log.d("GeminiLiveClient", "Sending setup: $setupStr")
        webSocket?.send(setupStr)
    }

    fun sendAudio(pcmBytes: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().put(JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                }))
            })
        }
        val msgStr = msg.toString()
        webSocket?.send(msgStr)
    }

    fun sendToolResponse(functionResponses: JSONArray) {
        val msg = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", functionResponses)
            })
        }
        webSocket?.send(msg.toString())
    }

    fun stopAudio() {
        // Send a clientContent message to interrupt/stop if we wanted,
        // but for now we just manage local audio queue clearing in AudioPlayer.
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        onStateChanged("IDLE")
    }
}
