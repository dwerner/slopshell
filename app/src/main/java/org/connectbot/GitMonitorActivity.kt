package org.connectbot

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Button
import android.app.AlertDialog
import android.widget.EditText
import kotlinx.coroutines.*
import java.net.URL
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Git Monitor Activity - Written in Kotlin
 * This activity provides a interface for monitoring git repository changes
 * and can connect to the Git Monitor Web Server for real-time updates.
 */
class GitMonitorActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GitChangeAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val gitChanges = mutableListOf<GitChange>()
    private var serverUrl = "http://192.168.1.100:8080"
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_git_monitor)
        
        setupToolbar()
        setupRecyclerView()
        setupFab()
        loadGitStatus()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Git Monitor"
        }
    }
    
    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.git_changes_list)
        adapter = GitChangeAdapter(gitChanges) { change ->
            onChangeClicked(change)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
    
    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fab_refresh).setOnClickListener {
            showServerOptionsDialog()
        }
    }
    
    private fun showServerOptionsDialog() {
        val options = arrayOf(
            "Connect to Server",
            "Refresh Status",
            "View Server Info"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Git Monitor Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> connectToRemoteServer()
                    1 -> refreshStatus()
                    2 -> showServerInfo()
                }
            }
            .show()
    }
    
    private fun refreshStatus() {
        if (serverUrl.isNotEmpty()) {
            loadGitStatusFromServer(serverUrl)
        } else {
            Toast.makeText(this, "No server configured", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showServerInfo() {
        AlertDialog.Builder(this)
            .setTitle("Server Info")
            .setMessage("""
                Current Server: $serverUrl
                
                To start the server on your workstation:
                1. cd to your git repository
                2. Run: ./start-git-monitor.sh
                3. Access web UI at http://localhost:8080
                
                The server provides REST APIs and WebSocket
                for real-time git repository monitoring.
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun connectToRemoteServer() {
        val input = EditText(this)
        input.hint = "http://192.168.1.100:8080"
        
        AlertDialog.Builder(this)
            .setTitle("Enter Server URL")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val url = input.text.toString()
                if (url.isNotEmpty()) {
                    serverUrl = url
                    loadGitStatusFromServer(serverUrl)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun loadGitStatusFromServer(serverUrl: String) {
        coroutineScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    val url = URL("$serverUrl/api/status")
                    val connection = url.openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    
                    val response = connection.getInputStream().bufferedReader().use { 
                        it.readText() 
                    }
                    
                    // Parse the JSON response
                    val apiResponse = gson.fromJson(response, ApiResponse::class.java)
                    if (apiResponse.success && apiResponse.data != null) {
                        // Convert the data to GitStatus
                        val gitStatus = gson.fromJson(
                            gson.toJson(apiResponse.data), 
                            GitStatus::class.java
                        )
                        gitStatus
                    } else {
                        null
                    }
                }
                
                if (status != null) {
                    updateUIWithGitStatus(status)
                } else {
                    Toast.makeText(this@GitMonitorActivity, 
                        "Failed to get status from server", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@GitMonitorActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun updateUIWithGitStatus(status: GitStatus) {
        gitChanges.clear()
        
        // Add staged files
        status.staged.forEach { change ->
            gitChanges.add(GitChange(
                "S:${change.status}", 
                change.file, 
                "Staged for commit"
            ))
        }
        
        // Add unstaged files
        status.unstaged.forEach { change ->
            gitChanges.add(GitChange(
                "M:${change.status}", 
                change.file, 
                "Modified"
            ))
        }
        
        // Add untracked files
        status.untracked.forEach { file ->
            gitChanges.add(GitChange(
                "??", 
                file, 
                "Untracked file"
            ))
        }
        
        adapter.notifyDataSetChanged()
        
        // Update toolbar with branch name
        supportActionBar?.subtitle = "Branch: ${status.branch}"
    }
    
    private fun loadGitStatus() {
        executor.execute {
            try {
                // This is a placeholder - in a real implementation,
                // you'd run git commands or connect to a git monitoring service
                val dummyChanges = listOf(
                    GitChange("M", "README.md", "Modified readme with Claude integration ideas"),
                    GitChange("M", "app/src/main/res/values/colors.xml", "Changed theme colors to red"),
                    GitChange("A", "app/src/main/java/org/connectbot/GitMonitorActivity.kt", "Added new Kotlin activity"),
                    GitChange("M", "app/build.gradle.kts", "Added Kotlin support")
                )
                
                runOnUiThread {
                    gitChanges.clear()
                    gitChanges.addAll(dummyChanges)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error loading git status: ${e.message}", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun onChangeClicked(change: GitChange) {
        Toast.makeText(this, "Viewing: ${change.fileName}", Toast.LENGTH_SHORT).show()
        // TODO: Open diff viewer or staging interface
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        coroutineScope.cancel()
    }
    
    // Data classes matching the server's response format
    data class ApiResponse(
        val success: Boolean,
        val data: Any? = null,
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
    
    // Data class for git changes
    data class GitChange(
        val status: String,
        val fileName: String,
        val description: String
    )
    
    // RecyclerView adapter for git changes
    class GitChangeAdapter(
        private val changes: List<GitChange>,
        private val onItemClick: (GitChange) -> Unit
    ) : RecyclerView.Adapter<GitChangeAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val statusText: TextView = view.findViewById(R.id.status_text)
            val fileNameText: TextView = view.findViewById(R.id.file_name_text)
            val descriptionText: TextView = view.findViewById(R.id.description_text)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_git_change, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val change = changes[position]
            holder.statusText.text = change.status
            holder.fileNameText.text = change.fileName
            holder.descriptionText.text = change.description
            
            // Set status color
            val statusColor = when (change.status) {
                "M" -> android.R.color.holo_orange_light  // Modified
                "A" -> android.R.color.holo_green_light    // Added
                "D" -> android.R.color.holo_red_light      // Deleted
                else -> android.R.color.darker_gray
            }
            holder.statusText.setTextColor(holder.itemView.context.getColor(statusColor))
            
            holder.itemView.setOnClickListener { onItemClick(change) }
        }
        
        override fun getItemCount() = changes.size
    }
}