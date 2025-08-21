package io.slopshell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson

/**
 * Git Monitor Fragment - Written in Kotlin
 * This fragment provides an interface for monitoring git repository changes
 * and can connect to the Git Monitor Web Server for real-time updates.
 */
class GitMonitorFragment : Fragment() {
    
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
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_git_monitor, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView(view)
        setupSwipeRefresh(view)
        
        // Start and bind to the service
        val serviceIntent = Intent(requireContext(), GitMonitorService::class.java)
        requireActivity().startService(serviceIntent)
        requireActivity().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun setupServiceCallbacks() {
        gitMonitorService?.apply {
            setStatusCallback { status ->
                updateUIWithGitStatus(status)
            }
            
            setConnectionCallback { connected, message ->
                updateConnectionStatus(connected)
                message?.let { 
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() 
                }
            }
            
            setFileChangeCallback { event ->
                // Don't show toast anymore, just pulse the file in the tree
                requireActivity().runOnUiThread {
                    treeAdapter.pulseFileChange(event.path)
                }
            }
        }
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        // Update parent activity's subtitle if needed
        (activity as? WorkspaceActivity)?.supportActionBar?.subtitle = if (connected) {
            "Connected to ${gitMonitorService?.getCurrentServerUrl()}"
        } else {
            "Not connected"
        }
    }
    
    private fun setupRecyclerView(view: View) {
        recyclerView = view.findViewById(R.id.git_changes_list)
        treeAdapter = GitTreeAdapter(requireContext()) { treeItem ->
            onTreeItemClicked(treeItem)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = treeAdapter
    }
    
    private fun setupSwipeRefresh(view: View) {
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
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
        
        // Long press to show options
        swipeRefresh.setOnLongClickListener {
            showServerOptionsDialog()
            true
        }
    }
    
    private fun showServerOptionsDialog() {
        val options = arrayOf(
            "Connect to Server",
            "Refresh Status",
            "Commit Changes",
            "View Server Info"
        )
        
        AlertDialog.Builder(requireContext())
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
            Toast.makeText(requireContext(), "No staged changes to commit", Toast.LENGTH_SHORT).show()
            return
        }
        
        val input = EditText(requireContext())
        input.hint = "Enter commit message"
        
        AlertDialog.Builder(requireContext())
            .setTitle("Commit $stagedCount staged file(s)")
            .setView(input)
            .setPositiveButton("Commit") { _, _ ->
                val message = input.text.toString()
                if (message.isNotEmpty()) {
                    commitChanges(message)
                } else {
                    Toast.makeText(requireContext(), "Commit message required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun commitChanges(message: String) {
        gitMonitorService?.commitChanges(message) { success, error ->
            if (success) {
                Toast.makeText(requireContext(), "Changes committed successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), error ?: "Commit failed", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    
    private fun showServerInfo() {
        AlertDialog.Builder(requireContext())
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
    
    fun showConnectDialog() {
        connectToRemoteServer()
    }
    
    fun refreshStatus() {
        if (gitMonitorService?.isConnected() == true) {
            gitMonitorService?.refreshStatus()
        } else {
            Toast.makeText(requireContext(), "Not connected to server", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun connectToRemoteServer() {
        val input = EditText(requireContext())
        input.hint = "http://192.168.1.100:9090"
        val lastUrl = gitMonitorService?.getLastServerUrl()
        input.setText(if (lastUrl.isNullOrEmpty()) "http://192.168.0.133:9090" else lastUrl)
        
        AlertDialog.Builder(requireContext())
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
        requireActivity().runOnUiThread {
            android.util.Log.d("GitMonitorFragment", "updateUIWithGitStatus called - branch: ${status.branch}")
            android.util.Log.d("GitMonitorFragment", "Staged files: ${status.staged.size}, Unstaged: ${status.unstaged.size}, Untracked: ${status.untracked.size}")
            
            gitChanges.clear()
            
            try {
                // Add staged files - wrap in try-catch due to potential cast issues
                android.util.Log.d("GitMonitorFragment", "Processing staged files...")
                status.staged.forEach { change ->
                    android.util.Log.d("GitMonitorFragment", "Staged change type: ${change.javaClass.name}")
                    gitChanges.add(GitChange(
                        change.status, // Use full two-character status
                        change.file, 
                        "Staged"
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("GitMonitorFragment", "Error processing staged files", e)
            }
            
            try {
                // Add unstaged files - wrap in try-catch due to potential cast issues
                android.util.Log.d("GitMonitorFragment", "Processing unstaged files...")
                status.unstaged.forEach { change ->
                    android.util.Log.d("GitMonitorFragment", "Unstaged change type: ${change.javaClass.name}")
                    val description = when {
                        change.status.contains("D") -> "Deleted"
                        change.status.contains("M") -> "Modified"  
                        change.status.contains("A") -> "Added"
                        else -> "Changed"
                    }
                    gitChanges.add(GitChange(
                        change.status, // Use full two-character status
                        change.file, 
                        description
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("GitMonitorFragment", "Error processing unstaged files", e)
            }
            
            // Add untracked files - these are just strings so should work
            android.util.Log.d("GitMonitorFragment", "Processing untracked files...")
            status.untracked.forEach { file ->
                gitChanges.add(GitChange(
                    "??", 
                    file, 
                    "Untracked file"
                ))
            }
            
            // Update tree adapter - convert to GitMonitorActivity.GitChange type
            android.util.Log.d("GitMonitorFragment", "Updating tree adapter with ${gitChanges.size} total changes")
            val activityChanges = gitChanges.map { 
                GitMonitorActivity.GitChange(it.status, it.fileName, it.description) 
            }
            treeAdapter.updateItems(activityChanges)
            
            // Stop the refresh indicator
            android.util.Log.d("GitMonitorFragment", "About to stop refresh indicator")
            swipeRefresh.isRefreshing = false
            android.util.Log.d("GitMonitorFragment", "Refresh indicator stopped")
            
            // Show/hide empty view
            val emptyView = view?.findViewById<TextView>(R.id.empty_view)
            if (gitChanges.isEmpty()) {
                emptyView?.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView?.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
            
            // Update toolbar with branch name
            (activity as? WorkspaceActivity)?.supportActionBar?.subtitle = "Branch: ${status.branch}"
        }
    }
    
    private fun onTreeItemClicked(item: TreeItem) {
        when (item) {
            is TreeItem.DirectoryItem -> {
                // Show options to stage/unstage all files in directory
                onDirectoryClicked(item)
            }
            is TreeItem.FileItem -> {
                // Convert TreeItem.FileItem back to GitChange for the existing dialog
                val change = GitChange(item.status, item.path, item.description)
                onChangeClicked(change)
            }
        }
    }
    
    private fun onDirectoryClicked(directory: TreeItem.DirectoryItem) {
        // Collect all files under this directory from the current gitChanges
        val filesInDirectory = gitChanges.filter { change ->
            change.fileName.startsWith(directory.path + "/")
        }
        
        if (filesInDirectory.isEmpty()) {
            // Just expand/collapse if no files
            return
        }
        
        // Determine available actions based on file statuses
        val hasUnstaged = filesInDirectory.any { !it.status.startsWith("A") && !it.status.startsWith("M") && !it.status.startsWith("D") }
        val hasStaged = filesInDirectory.any { it.status[0] != ' ' && it.status[0] != '?' }
        val hasUntracked = filesInDirectory.any { it.status == "??" }
        
        val options = mutableListOf<String>()
        if (hasUnstaged || hasUntracked) options.add("Stage all in ${directory.name}")
        if (hasStaged) options.add("Unstage all in ${directory.name}")
        options.add("Expand/Collapse")
        
        AlertDialog.Builder(requireContext())
            .setTitle(directory.path)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Stage all in ${directory.name}" -> {
                        val filesToStage = filesInDirectory.map { it.fileName }
                        stageFiles(filesToStage)
                    }
                    "Unstage all in ${directory.name}" -> {
                        val filesToUnstage = filesInDirectory.filter { it.status[0] != ' ' && it.status[0] != '?' }
                            .map { it.fileName }
                        unstageFiles(filesToUnstage)
                    }
                    "Expand/Collapse" -> {
                        // Let the adapter handle expand/collapse
                        treeAdapter.toggleDirectory(directory.path)
                    }
                }
            }
            .show()
    }
    
    private fun onChangeClicked(change: GitChange) {
        // Check if file is staged (first character is not space)
        val isStaged = change.status.length >= 2 && change.status[0] != ' ' && change.status[0] != '?'
        val isUntracked = change.status == "??"
        val hasUnstagedChanges = change.status.length >= 2 && change.status[1] != ' '
        
        val options = when {
            isStaged && hasUnstagedChanges -> arrayOf("Unstage", "Stage unstaged changes", "View Diff")
            isStaged -> arrayOf("Unstage", "View Diff")
            isUntracked -> arrayOf("Stage", "View File")
            else -> arrayOf("Stage", "View Diff", "Discard Changes")
        }
        
        AlertDialog.Builder(requireContext())
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
                Toast.makeText(requireContext(), "Files staged", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), error ?: "Failed to stage files", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun unstageFiles(files: List<String>) {
        gitMonitorService?.unstageFiles(files) { success, error ->
            if (success) {
                Toast.makeText(requireContext(), "Files unstaged", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), error ?: "Failed to unstage files", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun viewDiff(fileName: String) {
        // TODO: Add diff endpoint to service
        Toast.makeText(requireContext(), "View diff not yet implemented in service", Toast.LENGTH_SHORT).show()
    }
    
    private fun viewFile(fileName: String) {
        Toast.makeText(requireContext(), "View file: $fileName", Toast.LENGTH_SHORT).show()
    }
    
    private fun discardChanges(fileName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Discard Changes?")
            .setMessage("Are you sure you want to discard changes to $fileName?")
            .setPositiveButton("Discard") { _, _ ->
                // TODO: Implement discard via server API
                Toast.makeText(requireContext(), "Discard not yet implemented", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        if (serviceBound) {
            requireActivity().unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    // Data class for git changes
    data class GitChange(
        val status: String,
        val fileName: String,
        val description: String
    )
}