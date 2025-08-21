package io.slopshell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.StrictMode
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewpager.widget.PagerAdapter
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.slopshell.service.BridgeDisconnectedListener
import io.slopshell.service.PromptHelper
import io.slopshell.service.TerminalBridge
import io.slopshell.service.TerminalManager
import io.slopshell.util.TerminalViewPager
import java.lang.ref.WeakReference

/**
 * Console Fragment - Converted from ConsoleActivity to Kotlin
 * Manages terminal sessions and provides terminal UI functionality
 */
class ConsoleFragment : Fragment(), BridgeDisconnectedListener, WorkspaceActivity.KeyEventHandler {
    
    companion object {
        const val TAG = "CB.ConsoleFragment"
        private const val STATE_SELECTED_URI = "selectedUri"
    }
    
    private var pager: TerminalViewPager? = null
    private var adapter: TerminalPagerAdapter? = null
    private var bound: TerminalManager? = null
    private var requested: TerminalBridge? = null
    private val flip = true
    
    private var emptyView: TextView? = null
    private var passwordGroup: View? = null
    private var passwordField: TextInputEditText? = null
    private var passwordLayout: TextInputLayout? = null
    private var passwordInstructions: TextView? = null
    private var booleanGroup: View? = null
    private var booleanPrompt: TextView? = null
    private var booleanYes: View? = null
    private var booleanNo: View? = null
    
    private var keyboardGroup: View? = null
    private var keyboardButton: View? = null
    
    private val disconnectHandler = DisconnectHandler(this)
    
    // Prompt handler for password/authentication prompts
    private val promptHandler = Handler { msg ->
        android.util.Log.d(TAG, "promptHandler: Received prompt request from bridge")
        // Trigger prompt update when we receive a prompt request
        updatePromptVisible()
        true
    }
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            bound = (service as TerminalManager.TerminalBinder).service
            
            // Update our adapter now that we're connected
            adapter = TerminalPagerAdapter()
            pager?.adapter = adapter
            
            // Check if we have a URI to connect to
            val uriString = arguments?.getString("uri")
            if (uriString != null) {
                val uri = android.net.Uri.parse(uriString)
                
                // First check if connection already exists
                val existingBridges = bound?.getBridges()
                val existingBridge = existingBridges?.find { bridge ->
                    bridge.host?.uri?.toString() == uriString
                }
                
                if (existingBridge != null) {
                    // Connection already exists, just select it
                    android.util.Log.d(TAG, "Using existing bridge for URI: $uriString")
                    existingBridge.promptHelper?.setHandler(promptHandler)
                    val index = existingBridges.indexOf(existingBridge)
                    pager?.setCurrentItem(index, false)
                    android.util.Log.d(TAG, "Selected existing bridge at index: $index")
                } else {
                    // Open new connection
                    android.util.Log.d(TAG, "Opening new connection for URI: $uriString")
                    try {
                        val bridge = bound?.openConnection(uri)
                        if (bridge != null) {
                            android.util.Log.d(TAG, "Bridge created, setting prompt handler")
                            // Set prompt handler for password prompts
                            bridge.promptHelper?.setHandler(promptHandler)
                            // Refresh adapter to show new connection
                            adapter?.notifyDataSetChanged()
                            // Select the new connection
                            Handler().postDelayed({
                                val bridges = bound?.getBridges()
                                val newIndex = bridges?.indexOf(bridge) ?: -1
                                if (newIndex >= 0) {
                                    pager?.setCurrentItem(newIndex, false)
                                    android.util.Log.d(TAG, "Selected new bridge at index: $newIndex")
                                }
                            }, 100)
                        } else {
                            android.util.Log.d(TAG, "Failed to create bridge - openConnection returned null")
                        }
                    } catch (e: IllegalArgumentException) {
                        // Connection already exists, find and select it
                        val bridges = bound?.getBridges()
                        val index = bridges?.indexOfFirst { it.host?.uri?.toString() == uriString } ?: -1
                        if (index >= 0) {
                            pager?.setCurrentItem(index, false)
                        }
                    }
                }
            } else {
                // Check for any existing bridges
                val requestedNickname = arguments?.getString("nickname")
                val bridges = bound?.getBridges()
                val requestedIndex = bridges?.indexOfFirst { it.host?.nickname == requestedNickname } ?: -1
                
                if (requestedIndex >= 0) {
                    pager?.setCurrentItem(requestedIndex, false)
                    requested = bridges?.get(requestedIndex)
                } else {
                    // If no specific bridge requested, show the first one if available
                    if ((bridges?.size ?: 0) > 0) {
                        pager?.setCurrentItem(0, false)
                    }
                }
            }
            
            updateEmptyVisible()
        }
        
        override fun onServiceDisconnected(className: ComponentName) {
            bound = null
            adapter = null
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_console, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Allow network operations on main thread for SSH operations
        // This matches what the original ConsoleActivity does
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        
        android.util.Log.d(TAG, "onViewCreated: Fragment view created")
        setupViews(view)
        
        // Start and connect to the terminal service
        val serviceIntent = Intent(requireContext(), TerminalManager::class.java)
        requireActivity().startService(serviceIntent)
        requireActivity().bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        android.util.Log.d(TAG, "onViewCreated: Service binding initiated")
    }
    
    override fun onResume() {
        super.onResume()
        android.util.Log.d(TAG, "onResume: Fragment resumed, checking for prompts")
        // Check for prompts when resuming
        updatePromptVisible()
        
        // Also ensure current bridge has the prompt handler set
        val currentIndex = pager?.currentItem ?: -1
        val bridges = bound?.getBridges()
        if (bridges != null && currentIndex >= 0 && currentIndex < bridges.size) {
            val bridge = bridges[currentIndex]
            android.util.Log.d(TAG, "onResume: Setting prompt handler for bridge at index $currentIndex")
            bridge.promptHelper?.setHandler(promptHandler)
        }
    }
    
    private fun setupViews(view: View) {
        pager = view.findViewById<TerminalViewPager>(R.id.console_flip)
        emptyView = view.findViewById(android.R.id.empty)
        
        // Password prompt views
        passwordGroup = view.findViewById(R.id.console_password_group)
        passwordField = view.findViewById(R.id.console_password)
        passwordLayout = view.findViewById(R.id.console_password_layout)
        passwordInstructions = view.findViewById(R.id.console_password_instructions)
        
        // Boolean prompt views
        booleanGroup = view.findViewById(R.id.console_boolean_group)
        booleanPrompt = view.findViewById(R.id.console_prompt)
        booleanYes = view.findViewById(R.id.console_prompt_yes)
        booleanNo = view.findViewById(R.id.console_prompt_no)
        
        android.util.Log.d(TAG, "setupViews: booleanGroup=$booleanGroup, booleanPrompt=$booleanPrompt, booleanYes=$booleanYes, booleanNo=$booleanNo")
        
        // Setup keyboard
        setupKeyboard(view)
        
        // Setup page change listener
        pager?.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
            override fun onPageSelected(position: Int) {
                onTerminalChanged()
                // Set prompt handler for the selected bridge
                val bridges = bound?.getBridges()
                if (bridges != null && position < bridges.size) {
                    bridges[position].promptHelper?.setHandler(promptHandler)
                }
            }
            override fun onPageScrollStateChanged(state: Int) {}
        })
        
        // Setup prompt handlers
        setupPromptHandlers()
    }
    
    private fun setupKeyboard(view: View) {
        // Inflate keyboard layout from ViewStub
        val keyboardStub = view.findViewById<ViewStub>(R.id.keyboard_stub)
        if (keyboardStub != null) {
            // Default to compact keyboard
            val prefs = requireContext().getSharedPreferences("ConnectBot", Context.MODE_PRIVATE)
            val useCompactKeyboard = prefs.getBoolean("compactKeyboard", true) // Default to true for compact
            
            keyboardStub.layoutResource = if (useCompactKeyboard) {
                R.layout.inc_keyboard_compact
            } else {
                R.layout.inc_keyboard
            }
            
            try {
                keyboardStub.inflate()
                keyboardGroup = view.findViewById(R.id.keyboard_group)
                
                // Setup keyboard toggle button (button inside the keyboard layout)
                keyboardButton = view.findViewById(R.id.button_keyboard)
                keyboardButton?.setOnClickListener {
                    toggleSystemKeyboard()
                }
                
                // Always show the keyboard shortcut bar
                keyboardGroup?.visibility = View.VISIBLE
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error inflating keyboard", e)
            }
        }
    }
    
    private fun toggleSystemKeyboard() {
        // Toggle the system soft keyboard
        val currentView = adapter?.getCurrentView()
        if (currentView != null) {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.toggleSoftInputFromWindow(
                currentView.applicationWindowToken,
                android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                0
            )
            // Request focus after toggling keyboard, matching ConsoleActivity
            currentView.requestFocus()
        }
    }
    
    fun disconnectCurrentSession() {
        val currentIndex = pager?.currentItem ?: -1
        val bridges = bound?.getBridges()
        if (bridges != null && currentIndex >= 0 && currentIndex < bridges.size) {
            val bridge = bridges[currentIndex]
            bridge.dispatchDisconnect(true)
        }
    }
    
    fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (text != null) {
                // Send text to current terminal
                val currentIndex = pager?.currentItem ?: -1
                val bridges = bound?.getBridges()
                if (bridges != null && currentIndex >= 0 && currentIndex < bridges.size) {
                    val bridge = bridges[currentIndex]
                    bridge.injectString(text)
                }
            }
        }
    }
    
    fun injectVoiceText(text: String) {
        // Send voice text to current terminal
        val currentIndex = pager?.currentItem ?: -1
        val bridges = bound?.getBridges()
        if (bridges != null && currentIndex >= 0 && currentIndex < bridges.size) {
            val bridge = bridges[currentIndex]
            bridge.injectString(text)
            android.util.Log.d(TAG, "Injected voice text: $text")
        }
    }
    
    fun sendEscapeKey() {
        // Send ESC key to current terminal
        val currentIndex = pager?.currentItem ?: -1
        val bridges = bound?.getBridges()
        if (bridges != null && currentIndex >= 0 && currentIndex < bridges.size) {
            val bridge = bridges[currentIndex]
            // ESC key is ASCII 27
            val escapeChar = 27.toChar().toString()
            bridge.injectString(escapeChar)
            android.util.Log.d(TAG, "Sent ESC key to terminal")
        }
    }
    
    fun toggleKeyboard() {
        // Toggle the system soft keyboard
        val currentView = adapter?.getCurrentView()
        if (currentView != null && currentView is TerminalView) {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            
            // Ensure the terminal view has focus
            currentView.isFocusable = true
            currentView.isFocusableInTouchMode = true
            currentView.requestFocus()
            
            // Show the keyboard
            val shown = imm.showSoftInput(currentView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            if (!shown) {
                // If soft input wasn't shown, try forcing it
                imm.toggleSoftInput(
                    android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    android.view.inputmethod.InputMethodManager.HIDE_NOT_ALWAYS
                )
            }
            
            android.util.Log.d(TAG, "toggleKeyboard: TerminalView focused, keyboard shown: $shown")
        } else {
            android.util.Log.d(TAG, "toggleKeyboard: No TerminalView available")
        }
    }
    
    private fun focusTerminal() {
        android.util.Log.d(TAG, "focusTerminal: Focusing terminal after login")
        val currentView = adapter?.getCurrentView()
        
        if (currentView is TerminalView) {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            
            // Clear focus from any other view
            val focusedView = requireActivity().currentFocus
            focusedView?.clearFocus()
            
            // Make sure terminal view can receive focus
            currentView.isFocusable = true
            currentView.isFocusableInTouchMode = true
            
            // Request focus
            val focusRequested = currentView.requestFocus()
            android.util.Log.d(TAG, "focusTerminal: requestFocus() returned: $focusRequested")
            
            // Try to show keyboard (optional - user may not want keyboard immediately)
            currentView.postDelayed({
                val shown = imm.showSoftInput(currentView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                android.util.Log.d(TAG, "focusTerminal: showSoftInput(SHOW_IMPLICIT) returned: $shown")
                
                if (!shown) {
                    android.util.Log.d(TAG, "focusTerminal: Trying SHOW_FORCED")
                    imm.toggleSoftInput(
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                        android.view.inputmethod.InputMethodManager.HIDE_NOT_ALWAYS
                    )
                }
                
                android.util.Log.d(TAG, "focusTerminal: Terminal should now have focus and accept input")
            }, 100)
        } else {
            android.util.Log.d(TAG, "focusTerminal: Current view is not a TerminalView")
        }
    }
    
    private fun setupPromptHandlers() {
        android.util.Log.d(TAG, "setupPromptHandlers: Setting up prompt handlers")
        passwordField?.setOnEditorActionListener { _, _, _ ->
            android.util.Log.d(TAG, "passwordField: User submitted password")
            // Send the password to the current bridge
            val password = passwordField?.text.toString()
            val promptHelper = getCurrentPromptHelper()
            android.util.Log.d(TAG, "passwordField: Sending password to promptHelper=$promptHelper")
            promptHelper?.setResponse(password)
            passwordGroup?.visibility = View.GONE
            
            // Focus terminal after password submission
            // This fixes the issue where terminal doesn't get focus after login
            Handler().postDelayed({
                focusTerminal()
            }, 500) // Small delay to ensure login completes
            
            true
        }
        
        booleanYes?.setOnClickListener {
            android.util.Log.d(TAG, "booleanYes: User clicked YES")
            getCurrentPromptHelper()?.setResponse(true)
            booleanGroup?.visibility = View.GONE
        }
        
        booleanNo?.setOnClickListener {
            android.util.Log.d(TAG, "booleanNo: User clicked NO")
            getCurrentPromptHelper()?.setResponse(false)
            booleanGroup?.visibility = View.GONE
        }
    }
    
    private fun onTerminalChanged() {
        updateDefault()
        updatePromptVisible()
    }
    
    private fun updateDefault() {
        // Update the default bridge if we have one selected
        val currentIndex = pager?.currentItem ?: -1
        if (currentIndex >= 0 && currentIndex < (bound?.getBridges()?.size ?: 0)) {
            bound?.defaultBridge = bound?.getBridges()?.get(currentIndex)
        }
    }
    
    private fun updatePromptVisible() {
        val promptHelper = getCurrentPromptHelper()
        
        android.util.Log.d(TAG, "updatePromptVisible: promptHelper=$promptHelper")
        
        if (promptHelper == null) {
            android.util.Log.d(TAG, "updatePromptVisible: No prompt helper available")
            passwordGroup?.visibility = View.GONE
            booleanGroup?.visibility = View.GONE
            return
        }
        
        android.util.Log.d(TAG, "updatePromptVisible: promptRequested=${promptHelper.promptRequested}, instructions=${promptHelper.promptInstructions}")
        android.util.Log.d(TAG, "updatePromptVisible: checking - java.lang.String=${java.lang.String::class.java}, java.lang.Boolean=${java.lang.Boolean::class.java}")
        android.util.Log.d(TAG, "updatePromptVisible: equals check - String=${promptHelper.promptRequested == java.lang.String::class.java}, Boolean=${promptHelper.promptRequested == java.lang.Boolean::class.java}")
        
        when {
            promptHelper.promptRequested == java.lang.String::class.java -> {
                // String class means password prompt expected
                android.util.Log.d(TAG, "updatePromptVisible: String.class detected, showing password prompt")
                passwordGroup?.visibility = View.VISIBLE
                booleanGroup?.visibility = View.GONE
                
                passwordInstructions?.text = promptHelper.promptInstructions ?: "Password:"
                passwordField?.setText("")
                passwordField?.requestFocus()
                
                // Show keyboard for password field
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                passwordField?.postDelayed({
                    imm.showSoftInput(passwordField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 100)
            }
            promptHelper.promptRequested == java.lang.Boolean::class.java -> {
                // Boolean class means boolean prompt expected
                android.util.Log.d(TAG, "updatePromptVisible: Boolean.class detected, showing boolean prompt")
                android.util.Log.d(TAG, "updatePromptVisible: Setting booleanGroup visible, buttons: yes=$booleanYes, no=$booleanNo")
                passwordGroup?.visibility = View.GONE
                booleanGroup?.visibility = View.VISIBLE
                
                val promptText = promptHelper.promptHint ?: promptHelper.promptInstructions ?: "Yes/No?"
                android.util.Log.d(TAG, "updatePromptVisible: Setting prompt text: $promptText")
                booleanPrompt?.text = promptText
                
                // Make sure buttons are visible
                booleanYes?.visibility = View.VISIBLE
                booleanNo?.visibility = View.VISIBLE
                android.util.Log.d(TAG, "updatePromptVisible: Button visibility set - yes=${booleanYes?.visibility}, no=${booleanNo?.visibility}")
            }
            promptHelper.promptRequested is String -> {
                // Actual String instance (shouldn't happen based on PromptHelper code)
                android.util.Log.d(TAG, "updatePromptVisible: String instance detected")
                passwordGroup?.visibility = View.VISIBLE
                booleanGroup?.visibility = View.GONE
                
                passwordInstructions?.text = promptHelper.promptInstructions
                passwordField?.setText("")
                passwordField?.requestFocus()
            }
            promptHelper.promptRequested is Boolean -> {
                // Actual Boolean instance (shouldn't happen based on PromptHelper code)
                android.util.Log.d(TAG, "updatePromptVisible: Boolean instance detected")
                passwordGroup?.visibility = View.GONE
                booleanGroup?.visibility = View.VISIBLE
                
                booleanPrompt?.text = promptHelper.promptInstructions
            }
            else -> {
                android.util.Log.d(TAG, "updatePromptVisible: No prompt requested or unknown type: ${promptHelper.promptRequested}")
                val wasPasswordVisible = passwordGroup?.visibility == View.VISIBLE
                passwordGroup?.visibility = View.GONE
                booleanGroup?.visibility = View.GONE
                
                // If password prompt was visible and now hidden, focus the terminal
                // This handles the case where login completes
                if (wasPasswordVisible) {
                    Handler().postDelayed({
                        focusTerminal()
                    }, 300)
                }
            }
        }
    }
    
    private fun getCurrentPromptHelper(): PromptHelper? {
        val view = adapter?.getCurrentView()
        android.util.Log.d(TAG, "getCurrentPromptHelper: currentView=$view")
        if (view is TerminalView) {
            val helper = view.bridge?.promptHelper
            android.util.Log.d(TAG, "getCurrentPromptHelper: Found TerminalView with promptHelper=$helper")
            return helper
        }
        android.util.Log.d(TAG, "getCurrentPromptHelper: No TerminalView found")
        return null
    }
    
    private fun updateEmptyVisible() {
        val visibility = if ((bound?.getBridges()?.size ?: 0) == 0) View.VISIBLE else View.GONE
        emptyView?.visibility = visibility
    }
    
    override fun onDisconnected(bridge: TerminalBridge) {
        disconnectHandler.dispatch(bridge)
    }
    
    override fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        // Handle key events for the current terminal
        val view = adapter?.getCurrentView()
        if (view is TerminalView) {
            // TerminalView handles key events internally through its own onKeyDown
            return view.onKeyDown(keyCode, event)
        }
        return false
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        requireActivity().unbindService(connection)
    }
    
    /**
     * Adapter for terminal views in the ViewPager
     */
    inner class TerminalPagerAdapter : PagerAdapter() {
        
        private var currentView: View? = null
        
        override fun getCount(): Int = bound?.getBridges()?.size ?: 0
        
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val bridge = bound?.getBridges()?.get(position)
            
            return if (bridge != null) {
                val view = TerminalView(requireContext(), bridge, pager)
                view.id = View.generateViewId()
                
                // Ensure the view can receive focus and show keyboard
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                
                container.addView(view)
                
                // Make sure we redraw on connect
                bridge.parentChanged(view)
                
                // Request focus immediately to avoid needing a key press to activate
                // This matches what the original ConsoleActivity does
                view.requestFocus()
                
                // Add focus change listener for debugging
                view.setOnFocusChangeListener { v, hasFocus ->
                    android.util.Log.d(TAG, "TerminalView focus changed: hasFocus=$hasFocus, view=$v")
                    if (hasFocus) {
                        android.util.Log.d(TAG, "TerminalView gained focus - bridge.isDisconnected=${bridge.isDisconnected}")
                    } else {
                        android.util.Log.d(TAG, "TerminalView lost focus - new focus owner=${requireActivity().currentFocus}")
                    }
                }
                
                view
            } else {
                View(requireContext())
            }
        }
        
        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }
        
        override fun setPrimaryItem(container: ViewGroup, position: Int, `object`: Any) {
            super.setPrimaryItem(container, position, `object`)
            currentView = `object` as View
        }
        
        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view == `object`
        }
        
        override fun getPageTitle(position: Int): CharSequence? {
            val bridge = bound?.getBridges()?.get(position)
            return bridge?.host?.nickname ?: "Terminal $position"
        }
        
        fun getCurrentView(): View? = currentView
        
        override fun notifyDataSetChanged() {
            super.notifyDataSetChanged()
            updateEmptyVisible()
        }
    }
    
    /**
     * Handler for disconnect events
     */
    class DisconnectHandler(fragment: ConsoleFragment) : Handler() {
        private val fragmentRef = WeakReference(fragment)
        
        override fun handleMessage(msg: Message) {
            val fragment = fragmentRef.get() ?: return
            
            synchronized(fragment.adapter ?: return) {
                val bridge = msg.obj as? TerminalBridge ?: return
                fragment.adapter?.notifyDataSetChanged()
                
                // If this was the last bridge, show empty view
                if ((fragment.bound?.getBridges()?.size ?: 0) == 0) {
                    fragment.updateEmptyVisible()
                }
                
                val message = "Connection closed: ${bridge.host?.nickname}"
                Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
        
        fun dispatch(bridge: TerminalBridge) {
            val msg = Message.obtain(this, -1, bridge)
            sendMessage(msg)
        }
    }
}