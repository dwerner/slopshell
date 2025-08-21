package io.slopshell.gitmonitor

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.html.*
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

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

data class CommitInfo(
    val hash: String,
    val author: String,
    val date: String,
    val message: String
)

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class StageRequest(val files: List<String>)
data class CommitRequest(val message: String)

class GitMonitorServer : CliktCommand() {
    private val port by option("--port", "-p", help = "Server port").int().default(9090)
    private val repoPath by option("--repo", "-r", help = "Git repository path").default(".")
    private val host by option("--host", "-h", help = "Host to bind to").default("0.0.0.0")
    
    private val fileWatchChannel = Channel<FileWatchEvent>(Channel.BUFFERED)
    private val webSocketConnections = ConcurrentHashMap<String, WebSocketSession>()
    private var directoryWatcher: DirectoryWatcher? = null
    
    data class FileWatchEvent(
        val type: String,
        val path: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    override fun run() {
        println("🔴 Git Monitor Server")
        println("Repository: $repoPath")
        println("Starting server on http://$host:$port")
        
        // Verify git repository
        if (!File(repoPath, ".git").exists()) {
            println("⚠️  Warning: $repoPath is not a git repository")
        }
        
        embeddedServer(Netty, port = port, host = host) {
            configureServer()
            configureRouting()
        }.start(wait = true)
    }
    
    private fun Application.configureServer() {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }
        
        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            anyHost() // For development - restrict in production
        }
        
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        
        // Start file watcher
        startFileWatcher()
    }
    
    private fun Application.configureRouting() {
        routing {
            get("/") {
                call.respondHtml {
                    head {
                        title("Git Monitor Server")
                        style {
                            unsafe {
                                raw("""
                                    body { 
                                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                        margin: 40px;
                                        background: #1e1e1e;
                                        color: #d4d4d4;
                                    }
                                    h1 { color: #ff5252; }
                                    .status-card {
                                        background: #2d2d2d;
                                        border-radius: 8px;
                                        padding: 20px;
                                        margin: 20px 0;
                                    }
                                    .endpoint {
                                        background: #2d2d2d;
                                        border-left: 3px solid #ff5252;
                                        padding: 10px;
                                        margin: 10px 0;
                                    }
                                    .method {
                                        display: inline-block;
                                        padding: 2px 8px;
                                        border-radius: 3px;
                                        font-weight: bold;
                                        margin-right: 10px;
                                    }
                                    .get { background: #4CAF50; }
                                    .post { background: #2196F3; }
                                    .ws { background: #FF9800; }
                                    code { 
                                        background: #1e1e1e; 
                                        padding: 2px 6px; 
                                        border-radius: 3px;
                                    }
                                    pre {
                                        background: #1e1e1e;
                                        padding: 15px;
                                        border-radius: 5px;
                                        overflow-x: auto;
                                    }
                                """.trimIndent())
                            }
                        }
                    }
                    body {
                        h1 { +"🔴 Git Monitor Server" }
                        
                        div("status-card") {
                            h2 { +"Server Status" }
                            p { +"Repository: "; code { +repoPath } }
                            p { +"Port: "; code { +port.toString() } }
                            p { +"Active WebSocket connections: "; code { +webSocketConnections.size.toString() } }
                            div {
                                id = "git-status"
                                h3 { +"Git Status" }
                                pre {
                                    id = "status-content"
                                    +"Loading..."
                                }
                            }
                        }
                        
                        div("status-card") {
                            h2 { +"API Endpoints" }
                            
                            div("endpoint") {
                                span("method get") { +"GET" }
                                code { +"/api/status" }
                                +" - Get current git status"
                            }
                            
                            div("endpoint") {
                                span("method get") { +"GET" }
                                code { +"/api/diff" }
                                +" - Get unstaged changes"
                            }
                            
                            div("endpoint") {
                                span("method get") { +"GET" }
                                code { +"/api/diff/staged" }
                                +" - Get staged changes"
                            }
                            
                            div("endpoint") {
                                span("method get") { +"GET" }
                                code { +"/api/log?limit=20" }
                                +" - Get commit history"
                            }
                            
                            div("endpoint") {
                                span("method post") { +"POST" }
                                code { +"/api/stage" }
                                +" - Stage files"
                            }
                            
                            div("endpoint") {
                                span("method post") { +"POST" }
                                code { +"/api/commit" }
                                +" - Create commit"
                            }
                            
                            div("endpoint") {
                                span("method ws") { +"WS" }
                                code { +"/ws" }
                                +" - WebSocket for real-time updates"
                            }
                        }
                        
                        script {
                            unsafe {
                                raw("""
                                    async function updateStatus() {
                                        try {
                                            const response = await fetch('/api/status');
                                            const data = await response.json();
                                            document.getElementById('status-content').textContent = 
                                                JSON.stringify(data.data, null, 2);
                                        } catch (e) {
                                            document.getElementById('status-content').textContent = 
                                                'Error: ' + e.message;
                                        }
                                    }
                                    
                                    updateStatus();
                                    setInterval(updateStatus, 5000);
                                    
                                    // Connect to WebSocket
                                    const ws = new WebSocket('ws://' + window.location.host + '/ws');
                                    ws.onmessage = (event) => {
                                        console.log('File change:', event.data);
                                        updateStatus();
                                    };
                                """.trimIndent())
                            }
                        }
                    }
                }
            }
            
            // API Routes
            route("/api") {
                get("/status") {
                    val status = getGitStatus()
                    call.respond(ApiResponse(true, status))
                }
                
                get("/diff") {
                    val diff = executeGitCommand("diff")
                    call.respond(ApiResponse(true, mapOf("diff" to diff)))
                }
                
                get("/diff/staged") {
                    val diff = executeGitCommand("diff", "--staged")
                    call.respond(ApiResponse(true, mapOf("diff" to diff)))
                }
                
                get("/log") {
                    val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
                    val commits = getGitLog(limit)
                    call.respond(ApiResponse(true, commits))
                }
                
                get("/branches") {
                    val branches = executeGitCommand("branch", "-a")
                        .lines()
                        .filter { it.isNotEmpty() }
                        .map { it.trim() }
                    call.respond(ApiResponse(true, branches))
                }
                
                post("/stage") {
                    val request = call.receive<StageRequest>()
                    request.files.forEach { file ->
                        executeGitCommand("add", file)
                    }
                    call.respond(ApiResponse(true, "Files staged"))
                }
                
                post("/unstage") {
                    val request = call.receive<StageRequest>()
                    request.files.forEach { file ->
                        executeGitCommand("reset", "HEAD", file)
                    }
                    call.respond(ApiResponse(true, "Files unstaged"))
                }
                
                post("/commit") {
                    val request = call.receive<CommitRequest>()
                    val result = executeGitCommand("commit", "-m", request.message)
                    call.respond(ApiResponse(true, result))
                }
            }
            
            // WebSocket endpoint
            webSocket("/ws") {
                val sessionId = System.currentTimeMillis().toString()
                webSocketConnections[sessionId] = this
                
                try {
                    send("Connected to Git Monitor WebSocket")
                    
                    // Send file watch events
                    val fileWatchJob = launch {
                        for (event in fileWatchChannel) {
                            try {
                                send(com.google.gson.Gson().toJson(event))
                            } catch (e: Exception) {
                                println("Error sending file event to $sessionId: ${e.message}")
                            }
                        }
                    }
                    
                    // Heartbeat sender - send ping every 20 seconds
                    val heartbeatJob = launch {
                        while (isActive) {
                            delay(20000) // 20 seconds
                            try {
                                send("ping")
                            } catch (e: Exception) {
                                println("Heartbeat failed for $sessionId: ${e.message}")
                                cancel() // Stop heartbeat if send fails
                            }
                        }
                    }
                    
                    // Handle incoming messages
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                when (text) {
                                    "pong" -> {
                                        // Client responded to ping
                                        println("Received pong from $sessionId")
                                    }
                                    "ping" -> {
                                        // Client sent ping, respond with pong
                                        send("pong")
                                    }
                                    else -> {
                                        println("Received message from $sessionId: $text")
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                    
                    // Cancel jobs when done
                    fileWatchJob.cancel()
                    heartbeatJob.cancel()
                } catch (e: Exception) {
                    println("WebSocket error for $sessionId: ${e.message}")
                } finally {
                    webSocketConnections.remove(sessionId)
                    println("WebSocket connection closed: $sessionId")
                }
            }
        }
    }
    
    private fun getGitStatus(): GitStatus {
        val branch = executeGitCommand("branch", "--show-current").trim()
        val statusOutput = executeGitCommand("status", "--porcelain", "-b")
        
        val staged = mutableListOf<FileChange>()
        val unstaged = mutableListOf<FileChange>()
        val untracked = mutableListOf<String>()
        
        statusOutput.lines().forEach { line ->
            if (line.isNotEmpty() && !line.startsWith("##")) {
                val status = if (line.length >= 2) line.substring(0, 2) else ""
                val file = if (line.length > 3) line.substring(3) else ""
                
                when {
                    status == "??" -> untracked.add(file)
                    status.getOrNull(0) != ' ' && status.getOrNull(0) != '?' -> {
                        // File has staged changes - send full status
                        staged.add(FileChange(status, file))
                    }
                    status.getOrNull(1) != ' ' && status.getOrNull(1) != '?' -> {
                        // File has unstaged changes - send full status
                        unstaged.add(FileChange(status, file))
                    }
                }
            }
        }
        
        // Get ahead/behind info
        val trackingInfo = executeGitCommand("status", "-sb").lines().firstOrNull() ?: ""
        val ahead = Regex("""ahead (\d+)""").find(trackingInfo)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = Regex("""behind (\d+)""").find(trackingInfo)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        return GitStatus(branch, staged, unstaged, untracked, ahead, behind)
    }
    
    private fun getGitLog(limit: Int): List<CommitInfo> {
        val logOutput = executeGitCommand(
            "log", "--oneline", 
            "--pretty=format:%H|%an|%ad|%s",
            "--date=short", 
            "-$limit"
        )
        
        return logOutput.lines()
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split("|")
                if (parts.size >= 4) {
                    CommitInfo(parts[0], parts[1], parts[2], parts[3])
                } else {
                    CommitInfo("", "", "", line)
                }
            }
    }
    
    private fun executeGitCommand(vararg args: String): String {
        return try {
            val processBuilder = ProcessBuilder("git", *args)
            processBuilder.directory(File(repoPath))
            val process = processBuilder.start()
            
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            
            process.waitFor()
            
            if (process.exitValue() != 0 && error.isNotEmpty()) {
                error
            } else {
                output
            }
        } catch (e: Exception) {
            "Error executing git command: ${e.message}"
        }
    }
    
    private fun startFileWatcher() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val path = Paths.get(repoPath)
                directoryWatcher = DirectoryWatcher.builder()
                    .path(path)
                    .listener { event ->
                        val eventType = event.eventType().toString()
                        val eventPath = event.path().toString()
                        
                        // Ignore .git directory changes
                        if (!eventPath.contains(".git")) {
                            GlobalScope.launch {
                                fileWatchChannel.send(
                                    FileWatchEvent(eventType, eventPath)
                                )
                            }
                        }
                    }
                    .build()
                
                directoryWatcher?.watch()
            } catch (e: Exception) {
                println("Failed to start file watcher: ${e.message}")
            }
        }
    }
}

fun main(args: Array<String>) = GitMonitorServer().main(args)