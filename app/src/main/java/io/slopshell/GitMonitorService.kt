package io.slopshell

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GitMonitorService : Service() {
    
    companion object {
        const val CHANNEL_ID = "GitMonitorChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "org.connectbot.action.CONNECT"
        const val ACTION_DISCONNECT = "org.connectbot.action.DISCONNECT"
        const val EXTRA_SERVER_URL = "server_url"
        const val PREFS_NAME = "GitMonitorPrefs"
        const val PREF_SERVER_URL = "server_url"
        const val PREF_AUTO_CONNECT = "auto_connect"
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RECONNECT_DELAY_MS = 5000L
    }
    
    private val binder = LocalBinder()
    private var webSocket: WebSocket? = null
    private var serverUrl: String? = null
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: SharedPreferences
    
    // Reconnection state
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private var reconnectJob: Job? = null
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Keep connection alive with WebSocket ping frames
        .retryOnConnectionFailure(true)
        .build()
    
    // Callbacks for activity
    private var statusCallback: ((GitStatus) -> Unit)? = null
    private var connectionCallback: ((Boolean, String?) -> Unit)? = null
    private var fileChangeCallback: ((FileWatchEvent) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): GitMonitorService = this@GitMonitorService
    }
    
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        
        // Auto-connect if we have a saved URL and auto-connect is enabled
        if (prefs.getBoolean(PREF_AUTO_CONNECT, false)) {
            prefs.getString(PREF_SERVER_URL, null)?.let { url ->
                connectToServer(url)
            }
        }
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val url = intent.getStringExtra(EXTRA_SERVER_URL)
                if (url != null) {
                    connectToServer(url)
                }
            }
            ACTION_DISCONNECT -> {
                disconnectFromServer()
            }
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Git Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection to Git Monitor server"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, GitMonitorActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Git Monitor")
            .setContentText(if (webSocket != null) "Connected to $serverUrl" else "Not connected")
            .setSmallIcon(R.drawable.notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    fun connectToServer(url: String) {
        serverUrl = url
        shouldReconnect = true
        reconnectAttempts = 0
        reconnectJob?.cancel()
        disconnectFromServer(false) // Close existing connection if any, but don't stop reconnecting
        
        // Save URL to preferences
        prefs.edit().putString(PREF_SERVER_URL, url).apply()
        
        serviceScope.launch {
            try {
                // First try to get status via HTTP
                fetchGitStatus(url)
                
                // Then connect WebSocket
                connectWebSocket(url)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionCallback?.invoke(false, "Connection failed: ${e.message}")
                }
                // Try to reconnect
                scheduleReconnect()
            }
        }
    }
    
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            serviceScope.launch(Dispatchers.Main) {
                connectionCallback?.invoke(false, "Max reconnection attempts reached")
            }
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectAttempts++
            
            serverUrl?.let { url ->
                withContext(Dispatchers.Main) {
                    connectionCallback?.invoke(false, "Reconnecting... (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
                }
                
                try {
                    fetchGitStatus(url)
                    connectWebSocket(url)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        connectionCallback?.invoke(false, "Reconnection failed: ${e.message}")
                    }
                    scheduleReconnect()
                }
            }
        }
    }
    
    fun getLastServerUrl(): String? {
        return prefs.getString(PREF_SERVER_URL, "http://192.168.0.133:9090")
    }
    
    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_CONNECT, enabled).apply()
    }
    
    fun isAutoConnectEnabled(): Boolean {
        return prefs.getBoolean(PREF_AUTO_CONNECT, false)
    }
    
    private suspend fun fetchGitStatus(baseUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/status")
                    .build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = response.body?.string() ?: ""
                        Log.d("GitMonitorService", "API Response: $json")
                        
                        // Parse the JSON manually to handle the nested structure properly
                        val jsonObject = com.google.gson.JsonParser.parseString(json).asJsonObject
                        
                        if (jsonObject.get("success").asBoolean) {
                            val dataObject = jsonObject.getAsJsonObject("data")
                            
                            val branch = dataObject.get("branch").asString
                            
                            // Parse staged array
                            val stagedArray = dataObject.getAsJsonArray("staged")
                            val staged = stagedArray.map { element ->
                                val obj = element.asJsonObject
                                FileChange(
                                    status = obj.get("status").asString,
                                    file = obj.get("file").asString,
                                    additions = obj.get("additions")?.asInt ?: 0,
                                    deletions = obj.get("deletions")?.asInt ?: 0
                                )
                            }
                            
                            // Parse unstaged array
                            val unstagedArray = dataObject.getAsJsonArray("unstaged")
                            val unstaged = unstagedArray.map { element ->
                                val obj = element.asJsonObject
                                FileChange(
                                    status = obj.get("status").asString,
                                    file = obj.get("file").asString,
                                    additions = obj.get("additions")?.asInt ?: 0,
                                    deletions = obj.get("deletions")?.asInt ?: 0
                                )
                            }
                            
                            // Parse untracked array (these are just strings)
                            val untrackedArray = dataObject.getAsJsonArray("untracked")
                            val untracked = untrackedArray.map { it.asString }
                            
                            val ahead = dataObject.get("ahead")?.asInt ?: 0
                            val behind = dataObject.get("behind")?.asInt ?: 0
                            
                            val gitStatus = GitStatus(
                                branch = branch,
                                staged = staged,
                                unstaged = unstaged,
                                untracked = untracked,
                                ahead = ahead,
                                behind = behind
                            )
                            
                            Log.d("GitMonitorService", "Parsed GitStatus - branch: $branch, staged: ${staged.size}, unstaged: ${unstaged.size}, untracked: ${untracked.size}")
                            
                            withContext(Dispatchers.Main) {
                                statusCallback?.invoke(gitStatus)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GitMonitorService", "Error fetching git status", e)
                e.printStackTrace()
            }
        }
    }
    
    private fun connectWebSocket(baseUrl: String) {
        val wsUrl = baseUrl.replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws"
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Reset reconnection counter on successful connection
                reconnectAttempts = 0
                
                serviceScope.launch(Dispatchers.Main) {
                    connectionCallback?.invoke(true, "Connected to Git Monitor")
                    updateNotification()
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        // Check for heartbeat messages
                        if (text == "ping") {
                            webSocket.send("pong")
                            return@launch
                        }
                        
                        // Try to parse as FileWatchEvent - temporarily disabled due to JNI issues
                        // TODO: Fix FileWatchEvent deserialization
                        // val event = gson.fromJson(text, FileWatchEvent::class.java)
                        // fileChangeCallback?.invoke(event)
                        
                        // Auto-refresh status when files change
                        serviceScope.launch(Dispatchers.IO) {
                            serverUrl?.let { fetchGitStatus(it) }
                        }
                    } catch (e: Exception) {
                        // Not a file event, might be a status message
                        println("WebSocket message: $text")
                    }
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                serviceScope.launch(Dispatchers.Main) {
                    connectionCallback?.invoke(false, "Connection closed: $reason")
                    updateNotification()
                }
                
                // Schedule reconnection if needed
                if (shouldReconnect && code != 1000) { // 1000 = normal closure
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                serviceScope.launch(Dispatchers.Main) {
                    connectionCallback?.invoke(false, "Connection failed: ${t.message}")
                    updateNotification()
                }
                
                // Schedule reconnection on failure
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }
    
    fun disconnectFromServer(stopReconnecting: Boolean = true) {
        if (stopReconnecting) {
            shouldReconnect = false
            reconnectJob?.cancel()
        }
        webSocket?.close(1000, "User requested disconnect")
        webSocket = null
        if (stopReconnecting) {
            serverUrl = null
        }
        updateNotification()
    }
    
    fun isConnected(): Boolean = webSocket != null
    
    fun getCurrentServerUrl(): String? = serverUrl
    
    // API methods for git operations
    fun stageFiles(files: List<String>, callback: (Boolean, String?) -> Unit) {
        val url = serverUrl ?: return callback(false, "Not connected")
        
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$url/api/stage")
                        .post(
                            gson.toJson(mapOf("files" to files)).toRequestBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                callback(true, "Files staged")
                                fetchGitStatus(url) // Refresh status
                            } else {
                                callback(false, "Failed to stage files")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, e.message)
                }
            }
        }
    }
    
    fun unstageFiles(files: List<String>, callback: (Boolean, String?) -> Unit) {
        val url = serverUrl ?: return callback(false, "Not connected")
        
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$url/api/unstage")
                        .post(
                            gson.toJson(mapOf("files" to files)).toRequestBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                callback(true, "Files unstaged")
                                fetchGitStatus(url) // Refresh status
                            } else {
                                callback(false, "Failed to unstage files")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, e.message)
                }
            }
        }
    }
    
    fun commitChanges(message: String, callback: (Boolean, String?) -> Unit) {
        val url = serverUrl ?: return callback(false, "Not connected")
        
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$url/api/commit")
                        .post(
                            gson.toJson(mapOf("message" to message)).toRequestBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                callback(true, "Changes committed")
                                fetchGitStatus(url) // Refresh status
                            } else {
                                callback(false, "Failed to commit")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, e.message)
                }
            }
        }
    }
    
    fun refreshStatus() {
        serverUrl?.let { 
            serviceScope.launch {
                fetchGitStatus(it)
            }
        }
    }
    
    // Callback setters
    fun setStatusCallback(callback: ((GitStatus) -> Unit)?) {
        statusCallback = callback
    }
    
    fun setConnectionCallback(callback: ((Boolean, String?) -> Unit)?) {
        connectionCallback = callback
    }
    
    fun setFileChangeCallback(callback: ((FileWatchEvent) -> Unit)?) {
        fileChangeCallback = callback
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnectFromServer()
        serviceScope.cancel()
    }
    
    // Data classes
    data class ApiResponse(
        val success: Boolean,
        val data: GitStatus? = null,
        val error: String? = null,
        val timestamp: Long = 0
    )
    
    data class GitStatus(
        val branch: String,
        val staged: List<FileChange>,
        val unstaged: List<FileChange>,
        val untracked: List<String>,
        val ahead: Int = 0,
        val behind: Int = 0
    )
    
    data class FileChange(
        val status: String,
        val file: String,
        val additions: Int = 0,
        val deletions: Int = 0
    )
    
    data class FileWatchEvent(
        val type: String,
        val path: String,
        val timestamp: Long = 0
    )
}