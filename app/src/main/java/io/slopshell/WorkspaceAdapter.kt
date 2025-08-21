package io.slopshell

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adapter for the main workspace ViewPager2.
 * Manages the Terminal and Git Monitor fragments.
 */
class WorkspaceAdapter(private val activity: FragmentActivity) : FragmentStateAdapter(activity) {
    
    var pendingUri: android.net.Uri? = null
    
    override fun getItemCount(): Int = 2
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                val fragment = ConsoleFragment()
                // Pass the URI if we have one
                pendingUri?.let { uri ->
                    fragment.arguments = android.os.Bundle().apply {
                        putString("uri", uri.toString())
                    }
                }
                fragment
            }
            1 -> GitMonitorFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}