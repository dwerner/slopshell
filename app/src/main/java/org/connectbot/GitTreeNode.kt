package org.connectbot

import java.util.TreeMap

/**
 * Represents a node in the file tree (either a directory or a file)
 */
sealed class GitTreeNode(
    open val name: String,
    open val path: String
) {
    data class Directory(
        override val name: String,
        override val path: String,
        val children: TreeMap<String, GitTreeNode> = TreeMap() // Sorted alphabetically
    ) : GitTreeNode(name, path) {
        
        fun addChild(node: GitTreeNode) {
            children[node.name] = node
        }
        
        fun getOrCreateDirectory(name: String): Directory {
            return children[name] as? Directory ?: Directory(name, "$path/$name").also {
                addChild(it)
            }
        }
    }
    
    data class File(
        override val name: String,
        override val path: String,
        val status: String,
        val description: String,
        var lastChangeTime: Long = System.currentTimeMillis()
    ) : GitTreeNode(name, path)
}

/**
 * Builds a tree structure from a flat list of file paths
 */
class GitTreeBuilder {
    fun buildTree(changes: List<GitMonitorActivity.GitChange>): GitTreeNode.Directory {
        val root = GitTreeNode.Directory("", "")
        
        for (change in changes) {
            val parts = change.fileName.split("/")
            var current = root
            
            // Navigate/create directories
            for (i in 0 until parts.size - 1) {
                current = current.getOrCreateDirectory(parts[i])
            }
            
            // Add the file
            if (parts.isNotEmpty()) {
                val fileName = parts.last()
                current.addChild(
                    GitTreeNode.File(
                        name = fileName,
                        path = change.fileName,
                        status = change.status,
                        description = change.description
                    )
                )
            }
        }
        
        return root
    }
    
    fun flattenTree(node: GitTreeNode, level: Int = 0, result: MutableList<TreeItem> = mutableListOf()): List<TreeItem> {
        when (node) {
            is GitTreeNode.Directory -> {
                if (node.name.isNotEmpty()) { // Skip root
                    result.add(TreeItem.DirectoryItem(node.name, node.path, level, node.children.size))
                }
                val nextLevel = if (node.name.isEmpty()) level else level + 1
                for (child in node.children.values) {
                    flattenTree(child, nextLevel, result)
                }
            }
            is GitTreeNode.File -> {
                result.add(TreeItem.FileItem(
                    name = node.name,
                    path = node.path,
                    status = node.status,
                    description = node.description,
                    level = level,
                    lastChangeTime = node.lastChangeTime
                ))
            }
        }
        return result
    }
}

/**
 * Items for the RecyclerView adapter
 */
sealed class TreeItem {
    abstract val level: Int
    
    data class DirectoryItem(
        val name: String,
        val path: String,
        override val level: Int,
        val childCount: Int
    ) : TreeItem()
    
    data class FileItem(
        val name: String,
        val path: String,
        val status: String,
        val description: String,
        override val level: Int,
        val lastChangeTime: Long
    ) : TreeItem()
}