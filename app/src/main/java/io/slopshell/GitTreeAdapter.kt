package io.slopshell

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class GitTreeAdapter(
    private val context: Context,
    private val onItemClick: (TreeItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private var items = listOf<TreeItem>()
    private val expandedDirs = mutableSetOf<String>()
    private val recentChanges = mutableMapOf<String, Long>() // Track recent changes for pulsing
    
    companion object {
        const val VIEW_TYPE_DIRECTORY = 0
        const val VIEW_TYPE_FILE = 1
        const val INDENT_WIDTH = 24 // dp per level
    }
    
    fun updateItems(newChanges: List<GitMonitorActivity.GitChange>) {
        val builder = GitTreeBuilder()
        val tree = builder.buildTree(newChanges)
        val newItems = builder.flattenTree(tree)
        
        // Track which files just changed
        val currentTime = System.currentTimeMillis()
        newItems.forEach { item ->
            if (item is TreeItem.FileItem) {
                val lastChange = recentChanges[item.path] ?: 0
                if (lastChange == 0L) {
                    recentChanges[item.path] = currentTime
                }
            }
        }
        
        // Use DiffUtil for smooth updates
        val diffResult = DiffUtil.calculateDiff(TreeDiffCallback(items, newItems))
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }
    
    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is TreeItem.DirectoryItem -> VIEW_TYPE_DIRECTORY
            is TreeItem.FileItem -> VIEW_TYPE_FILE
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DIRECTORY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_git_tree_directory, parent, false)
                DirectoryViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_git_tree_file, parent, false)
                FileViewHolder(view)
            }
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        
        // Set indentation based on level
        val indentView = holder.itemView.findViewById<View>(R.id.indent_space)
        val params = indentView.layoutParams
        params.width = item.level * (INDENT_WIDTH * context.resources.displayMetrics.density).toInt()
        indentView.layoutParams = params
        
        when (holder) {
            is DirectoryViewHolder -> {
                val dirItem = item as TreeItem.DirectoryItem
                holder.bind(dirItem, expandedDirs.contains(dirItem.path))
            }
            is FileViewHolder -> {
                val fileItem = item as TreeItem.FileItem
                val isRecent = (System.currentTimeMillis() - (recentChanges[fileItem.path] ?: 0)) < 5000
                holder.bind(fileItem, isRecent)
            }
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(item)
            if (item is TreeItem.DirectoryItem) {
                toggleDirectory(item.path)
            }
        }
    }
    
    override fun getItemCount() = items.size
    
    private fun toggleDirectory(path: String) {
        if (expandedDirs.contains(path)) {
            expandedDirs.remove(path)
        } else {
            expandedDirs.add(path)
        }
        // For now, just notify data changed. Later we can implement proper expand/collapse
        notifyDataSetChanged()
    }
    
    fun pulseFileChange(filePath: String) {
        recentChanges[filePath] = System.currentTimeMillis()
        val index = items.indexOfFirst { 
            it is TreeItem.FileItem && it.path == filePath 
        }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }
    
    inner class DirectoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.directory_name)
        private val childCountText: TextView = view.findViewById(R.id.child_count)
        private val folderIcon: TextView = view.findViewById(R.id.folder_icon)
        
        fun bind(item: TreeItem.DirectoryItem, isExpanded: Boolean) {
            nameText.text = item.name
            childCountText.text = "(${item.childCount})"
            
            // Change folder icon based on expanded state
            folderIcon.text = if (isExpanded) "📂" else "📁"
        }
    }
    
    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val statusText: TextView = view.findViewById(R.id.status_indicator)
        private val nameText: TextView = view.findViewById(R.id.file_name)
        private val descriptionText: TextView = view.findViewById(R.id.file_description)
        private val activityIndicator: View = view.findViewById(R.id.activity_indicator)
        
        fun bind(item: TreeItem.FileItem, isRecent: Boolean) {
            nameText.text = item.name
            statusText.text = when {
                item.status.startsWith("S:") -> "S"
                item.status == "??" -> "?"
                item.status.startsWith("M:") -> "M"
                else -> item.status.take(1)
            }
            
            // Set status color
            val statusColor = when {
                item.status.startsWith("S:") -> Color.parseColor("#4CAF50") // Green for staged
                item.status == "??" -> Color.parseColor("#9E9E9E") // Gray for untracked
                item.status.startsWith("M:") -> Color.parseColor("#FF9800") // Orange for modified
                else -> Color.parseColor("#757575")
            }
            statusText.setTextColor(statusColor)
            
            // Pulse animation for recent changes
            if (isRecent) {
                pulseAnimation(activityIndicator, statusColor)
            } else {
                activityIndicator.setBackgroundColor(Color.TRANSPARENT)
            }
            
            // Show/hide description
            if (item.description.isNotEmpty() && item.description != item.name) {
                descriptionText.text = item.description
                descriptionText.visibility = View.VISIBLE
            } else {
                descriptionText.visibility = View.GONE
            }
        }
        
        private fun pulseAnimation(view: View, color: Int) {
            val animator = ValueAnimator.ofFloat(0f, 1f, 0f)
            animator.duration = 2000
            animator.repeatCount = 2
            animator.addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                val alpha = (255 * value).toInt()
                val pulseColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                view.setBackgroundColor(pulseColor)
            }
            animator.start()
        }
    }
    
    class TreeDiffCallback(
        private val oldList: List<TreeItem>,
        private val newList: List<TreeItem>
    ) : DiffUtil.Callback() {
        
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        
        override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
            val old = oldList[oldPosition]
            val new = newList[newPosition]
            return when {
                old is TreeItem.DirectoryItem && new is TreeItem.DirectoryItem -> old.path == new.path
                old is TreeItem.FileItem && new is TreeItem.FileItem -> old.path == new.path
                else -> false
            }
        }
        
        override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
            return oldList[oldPosition] == newList[newPosition]
        }
    }
}