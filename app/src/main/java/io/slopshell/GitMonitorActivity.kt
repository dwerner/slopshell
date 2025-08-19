package io.slopshell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Button
import android.app.AlertDialog
import android.widget.EditText
import android.widget.CheckBox
import com.google.gson.Gson

/**
 * Git Monitor Activity - Written in Kotlin
 * This activity provides a interface for monitoring git repository changes
 * and can connect to the Git Monitor Web Server for real-time updates.
 */
class GitMonitorActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var treeAdapter: GitTreeAdapter
    private val gitChanges = mutableListOf<GitChange>()
    private var gitMonitorService: GitMonitorService? = null
    private var serviceBound = false
    private val gson = Gson()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GitMonitorService.LocalBinder
            gitMonitorService = binder.getService()
            serviceBound = true
            setupServiceCallbacks()
            
            // If already connected, refresh status
            if (gitMonitorService?.isConnected() == true) {
                gitMonitorService?.refreshStatus()
                updateConnectionStatus(true)
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            gitMonitorService = null
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_git_monitor)
        
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        
        // Start and bind to the service
        val serviceIntent = Intent(this, GitMonitorService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun setupServiceCallbacks() {
        gitMonitorService?.apply {
            setStatusCallback { status ->
                updateUIWithGitStatus(status)
            }
            
            setConnectionCallback { connected, message ->
                updateConnectionStatus(connected)
                message?.let { Toast.makeText(this@GitMonitorActivity, it, Toast.LENGTH_SHORT).show() }
            }
            
            setFileChangeCallback { event ->
                // Don't show toast anymore, just pulse the file in the tree
                runOnUiThread {
                    treeAdapter.pulseFileChange(event.path)
                }
            }
        }
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        supportActionBar?.subtitle = if (connected) {
            "Connected to ${gitMonitorService?.getCurrentServerUrl()}"
        } else {
            "Not connected"
        }
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
        treeAdapter = GitTreeAdapter(this) { treeItem ->
            onTreeItemClicked(treeItem)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = treeAdapter
    }
    
    private fun setupSwipeRefresh() {
        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )
        
        swipeRefresh.setOnRefreshListener {
            // If not connected, show connection dialog
            if (gitMonitorService?.isConnected() != true) {
                connectToRemoteServer()
                swipeRefresh.isRefreshing = false
            } else {
                // Refresh the status
                gitMonitorService?.refreshStatus()
                // The refresh indicator will be turned off when we receive the status update
            }
        }
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
            "Commit Changes",
            "View Server Info"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Git Monitor Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> connectToRemoteServer()
                    1 -> refreshStatus()
                    2 -> showCommitDialog()
                    3 -> showServerInfo()
                }
            }
            .show()
    }
    
    private fun showCommitDialog() {
        // Check if there are staged changes
        val stagedCount = gitChanges.count { it.status.startsWith("S:") }
        if (stagedCount == 0) {
            Toast.makeText(this, "No staged changes to commit", Toast.LENGTH_SHORT).show()
            return
        }
        
        val input = EditText(this)
        input.hint = "Enter commit message"
        
        AlertDialog.Builder(this)
            .setTitle("Commit $stagedCount staged file(s)")
            .setView(input)
            .setPositiveButton("Commit") { _, _ ->
                val message = input.text.toString()
                if (message.isNotEmpty()) {
                    commitChanges(message)
                } else {
                    Toast.makeText(this, "Commit message required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun commitChanges(message: String) {
        gitMonitorService?.commitChanges(message) { success, error ->
            if (success) {
                Toast.makeText(this, "Changes committed successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, error ?: "Commit failed", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun refreshStatus() {
        if (gitMonitorService?.isConnected() == true) {
            gitMonitorService?.refreshStatus()
        } else {
            Toast.makeText(this, "Not connected to server", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showServerInfo() {
        AlertDialog.Builder(this)
            .setTitle("Server Info")
            .setMessage("""
                Current Server: ${gitMonitorService?.getCurrentServerUrl() ?: "Not connected"}
                
                To start the server on your workstation:
                1. cd to your git repository
                2. Run: ./start-git-monitor.sh
                3. Server runs on port 9090
                
                The server provides REST APIs and WebSocket
                for real-time git repository monitoring.
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun connectToRemoteServer() {
        val input = EditText(this)
        input.hint = "http://192.168.1.100:9090"
        val lastUrl = gitMonitorService?.getLastServerUrl()
        input.setText(if (lastUrl.isNullOrEmpty()) "http://192.168.0.133:9090" else lastUrl)
        
        AlertDialog.Builder(this)
            .setTitle("Enter Server URL")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val url = input.text.toString()
                if (url.isNotEmpty()) {
                    gitMonitorService?.connectToServer(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    
    private fun updateUIWithGitStatus(status: GitMonitorService.GitStatus) {
        runOnUiThread {
            android.util.Log.d("GitMonitorActivity", "updateUIWithGitStatus called - branch: ${status.branch}")
            android.util.Log.d("GitMonitorActivity", "Staged files: ${status.staged.size}, Unstaged: ${status.unstaged.size}, Untracked: ${status.untracked.size}")
            
            gitChanges.clear()
            
            try {
                // Add staged files - wrap in try-catch due to potential cast issues
                android.util.Log.d("GitMonitorActivity", "Processing staged files...")
                status.staged.forEach { change ->
                    android.util.Log.d("GitMonitorActivity", "Staged change type: ${change.javaClass.name}")
                    gitChanges.add(GitChange(
                        "S:${change.status}", 
                        change.file, 
                        "Staged for commit"
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("GitMonitorActivity", "Error processing staged files", e)
            }
            
            try {
                // Add unstaged files - wrap in try-catch due to potential cast issues
                android.util.Log.d("GitMonitorActivity", "Processing unstaged files...")
                status.unstaged.forEach { change ->
                    android.util.Log.d("GitMonitorActivity", "Unstaged change type: ${change.javaClass.name}")
                    gitChanges.add(GitChange(
                        "M:${change.status}", 
                        change.file, 
                        "Modified"
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("GitMonitorActivity", "Error processing unstaged files", e)
            }
            
            // Add untracked files - these are just strings so should work
            android.util.Log.d("GitMonitorActivity", "Processing untracked files...")
            status.untracked.forEach { file ->
                gitChanges.add(GitChange(
                    "??", 
                    file, 
                    "Untracked file"
                ))
            }
            
            // Update tree adapter
            android.util.Log.d("GitMonitorActivity", "Updating tree adapter with ${gitChanges.size} total changes")
            treeAdapter.updateItems(gitChanges)
            
            // Stop the refresh indicator
            android.util.Log.d("GitMonitorActivity", "About to stop refresh indicator")
            swipeRefresh.isRefreshing = false
            android.util.Log.d("GitMonitorActivity", "Refresh indicator stopped")
            
            // Show/hide empty view
            val emptyView = findViewById<TextView>(R.id.empty_view)
            if (gitChanges.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
            
            // Update toolbar with branch name
            supportActionBar?.subtitle = "Branch: ${status.branch}"
        }
    }
    
    
    
    private fun onTreeItemClicked(item: TreeItem) {
        when (item) {
            is TreeItem.DirectoryItem -> {
                // Directory clicks are handled by the adapter for expand/collapse
            }
            is TreeItem.FileItem -> {
                // Convert TreeItem.FileItem back to GitChange for the existing dialog
                val change = GitChange(item.status, item.path, item.description)
                onChangeClicked(change)
            }
        }
    }
    
    private fun onChangeClicked(change: GitChange) {
        val options = when {
            change.status.startsWith("S:") -> arrayOf("Unstage", "View Diff")
            change.status == "??" -> arrayOf("Stage", "View File")
            else -> arrayOf("Stage", "View Diff", "Discard Changes")
        }
        
        AlertDialog.Builder(this)
            .setTitle(change.fileName)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Stage" -> stageFiles(listOf(change.fileName))
                    "Unstage" -> unstageFiles(listOf(change.fileName))
                    "View Diff" -> viewDiff(change.fileName)
                    "View File" -> viewFile(change.fileName)
                    "Discard Changes" -> discardChanges(change.fileName)
                }
            }
            .show()
    }
    
    private fun stageFiles(files: List<String>) {
        gitMonitorService?.stageFiles(files) { success, error ->
            if (success) {
                Toast.makeText(this, "Files staged", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, error ?: "Failed to stage files", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun unstageFiles(files: List<String>) {
        gitMonitorService?.unstageFiles(files) { success, error ->
            if (success) {
                Toast.makeText(this, "Files unstaged", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, error ?: "Failed to unstage files", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun viewDiff(fileName: String) {
        // TODO: Add diff endpoint to service
        Toast.makeText(this, "View diff not yet implemented in service", Toast.LENGTH_SHORT).show()
    }
    
    private fun viewFile(fileName: String) {
        Toast.makeText(this, "View file: $fileName", Toast.LENGTH_SHORT).show()
    }
    
    private fun discardChanges(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("Discard Changes?")
            .setMessage("Are you sure you want to discard changes to $fileName?")
            .setPositiveButton("Discard") { _, _ ->
                // TODO: Implement discard via server API
                Toast.makeText(this, "Discard not yet implemented", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    
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