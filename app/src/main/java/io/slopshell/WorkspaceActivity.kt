package io.slopshell

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.PopupMenu
import android.view.View
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import com.google.android.material.button.MaterialButton

/**
 * Main workspace activity that contains tabs for Terminal and Git Monitor.
 * Provides quick switching between the two main work modes.
 */
class WorkspaceActivity : AppCompatActivity() {
    
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: WorkspaceAdapter
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    // Voice recording UI
    private var voiceRecordingIndicator: LinearLayout? = null
    private var recordingText: TextView? = null
    private var recordingDot: View? = null
    private var voiceCancelButton: MaterialButton? = null
    private var voiceEnterButton: MaterialButton? = null
    private var recognizedText: String? = null
    private var pulseAnimator: ObjectAnimator? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.act_workspace)
        
        setupToolbar()
        setupViewPager()
        setupTabs()
        setupFabMenu()
        setupVoiceUI()
        
        // Handle intent to determine initial tab
        handleInitialIntent(intent)
        
        // Restore last selected tab if saved
        savedInstanceState?.let {
            val selectedTab = it.getInt(STATE_SELECTED_TAB, 0)
            viewPager.setCurrentItem(selectedTab, false)
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.app_name)
        }
    }
    
    private fun setupViewPager() {
        viewPager = findViewById(R.id.view_pager)
        adapter = WorkspaceAdapter(this)
        // Pass the URI from intent to adapter
        intent.data?.let { uri ->
            adapter.pendingUri = uri
        }
        viewPager.adapter = adapter
        
        // Optional: Disable swipe if needed for terminal interaction
        // viewPager.isUserInputEnabled = false
        
        // Add page change callback for analytics or state tracking
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateToolbarTitle(position)
                // Invalidate menu to update based on current fragment
                invalidateOptionsMenu()
            }
        })
    }
    
    private fun setupTabs() {
        tabLayout = findViewById(R.id.tab_layout)
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Terminal"
                1 -> "Git Monitor"
                else -> ""
            }
            
            // Optional: Add icons to tabs
            when (position) {
                0 -> tab.setIcon(R.drawable.ic_terminal)
                1 -> tab.setIcon(R.drawable.ic_git)
            }
        }.attach()
    }
    
    private fun setupFabMenu() {
        val fabMain = findViewById<FloatingActionButton>(R.id.fab_menu)
        val fabKeyboard = findViewById<FloatingActionButton>(R.id.fab_keyboard)
        val fabDisconnect = findViewById<FloatingActionButton>(R.id.fab_disconnect)
        val fabPaste = findViewById<FloatingActionButton>(R.id.fab_paste)
        val fabVoice = findViewById<FloatingActionButton>(R.id.fab_voice)
        val fabEscape = findViewById<FloatingActionButton>(R.id.fab_escape)
        
        var isFabMenuOpen = false
        
        // Main FAB toggles the menu
        fabMain.setOnClickListener {
            if (isFabMenuOpen) {
                closeFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
                isFabMenuOpen = false
            } else {
                openFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
                isFabMenuOpen = true
            }
        }
        
        // Escape FAB - send ESC key
        fabEscape.setOnClickListener {
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            if (currentFragment is ConsoleFragment) {
                currentFragment.sendEscapeKey()
            }
            closeFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
            isFabMenuOpen = false
        }
        
        // Keyboard FAB - context aware
        fabKeyboard.setOnClickListener {
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            if (currentFragment is ConsoleFragment) {
                currentFragment.toggleKeyboard()
            }
            closeFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
            isFabMenuOpen = false
        }
        
        // Voice FAB - trigger voice input directly
        fabVoice.setOnClickListener {
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            if (currentFragment is ConsoleFragment) {
                // Start voice recognition intent
                startVoiceInput()
            }
            closeFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
            isFabMenuOpen = false
        }
        
        // Disconnect FAB - context aware
        fabDisconnect.setOnClickListener {
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            if (currentFragment is ConsoleFragment) {
                currentFragment.disconnectCurrentSession()
            }
            closeFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
            isFabMenuOpen = false
        }
        
        // Paste FAB - context aware
        fabPaste.setOnClickListener {
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            if (currentFragment is ConsoleFragment) {
                currentFragment.pasteFromClipboard()
            }
            closeFabMenu(fabMain, fabEscape, fabVoice, fabPaste, fabDisconnect, fabKeyboard)
            isFabMenuOpen = false
        }
    }
    
    private fun setupVoiceUI() {
        try {
            voiceRecordingIndicator = findViewById(R.id.voice_recording_indicator)
            recordingText = findViewById(R.id.recording_text)
            recordingDot = findViewById(R.id.recording_dot)
            voiceCancelButton = findViewById(R.id.voice_cancel_button)
            voiceEnterButton = findViewById(R.id.voice_enter_button)
        
        // Setup cancel button
        voiceCancelButton.setOnClickListener {
            stopListening()
            hideVoiceIndicator()
        }
        
        // Setup enter button
        voiceEnterButton.setOnClickListener {
            recognizedText?.let { text ->
                val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                if (currentFragment is ConsoleFragment) {
                    // Send text with enter key
                    currentFragment.injectVoiceText(text + "\n")
                }
            }
            stopListening()
            hideVoiceIndicator()
        }
        
            // Start pulsing animation for recording dot
            pulseAnimator = ObjectAnimator.ofFloat(recordingDot, "alpha", 1f, 0.3f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
        } catch (e: Exception) {
            android.util.Log.e("WorkspaceActivity", "Error setting up voice UI", e)
        }
    }
    
    private fun showVoiceIndicator() {
        voiceRecordingIndicator.visibility = View.VISIBLE
        voiceRecordingIndicator.alpha = 0f
        voiceRecordingIndicator.animate().alpha(1f).setDuration(200).start()
        pulseAnimator?.start()
        recordingText.text = "Listening..."
        voiceEnterButton.isEnabled = false
        recognizedText = null
    }
    
    private fun hideVoiceIndicator() {
        pulseAnimator?.cancel()
        voiceRecordingIndicator.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                voiceRecordingIndicator.visibility = View.GONE
            }
            .start()
    }
    
    private fun startVoiceInput() {
        // Check audio recording permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.RECORD_AUDIO), 
                REQUEST_CODE_AUDIO_PERMISSION)
            return
        }
        
        if (isListening) {
            stopListening()
            return
        }
        
        // Initialize speech recognizer if needed
        if (speechRecognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
                return
            }
            
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    // Show the voice indicator
                    showVoiceIndicator()
                    isListening = true
                }
                
                override fun onBeginningOfSpeech() {
                    recordingText.text = "Listening..."
                }
                
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onEndOfSpeech() {
                    isListening = false
                    recordingText.text = "Processing..."
                }
                
                override fun onError(error: Int) {
                    isListening = false
                    hideVoiceIndicator()
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Speech recognition error"
                    }
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@WorkspaceActivity, errorMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        recognizedText = spokenText
                        
                        // Update UI to show recognized text
                        recordingText.text = "\"$spokenText\""
                        voiceEnterButton.isEnabled = true
                        
                        // Don't auto-send, wait for user to tap Send or Cancel
                    } else {
                        hideVoiceIndicator()
                    }
                }
                
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        
        // Start listening
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        
        speechRecognizer?.startListening(intent)
    }
    
    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }
    
    private fun openFabMenu(fabMain: FloatingActionButton, vararg fabs: FloatingActionButton) {
        // Rotate main FAB
        fabMain.animate().rotation(45f)
        // Show menu items with animation
        fabs.forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.animate().alpha(1f).setDuration(200L + (index * 50L))
        }
    }
    
    private fun closeFabMenu(fabMain: FloatingActionButton, vararg fabs: FloatingActionButton) {
        // Rotate main FAB back
        fabMain.animate().rotation(0f)
        // Hide menu items
        fabs.forEach { fab ->
            fab.animate().alpha(0f).setDuration(200).withEndAction {
                fab.visibility = View.GONE
            }
        }
    }
    
    private fun handleInitialIntent(intent: Intent) {
        // Check if we should open a specific tab
        when {
            intent.hasExtra(EXTRA_OPEN_TAB) -> {
                val tabName = intent.getStringExtra(EXTRA_OPEN_TAB)
                val tabIndex = when (tabName) {
                    TAB_TERMINAL -> 0
                    TAB_GIT_MONITOR -> 1
                    else -> 0
                }
                viewPager.setCurrentItem(tabIndex, false)
            }
            // Handle SSH/telnet URLs - open terminal tab
            intent.data != null -> {
                viewPager.setCurrentItem(0, false)
            }
        }
    }
    
    private fun updateToolbarTitle(position: Int) {
        supportActionBar?.subtitle = when (position) {
            0 -> "Terminal Sessions"
            1 -> "Repository Status"
            else -> null
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInitialIntent(intent)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Ctrl+Tab to switch tabs
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_TAB) {
            switchToNextTab()
            return true
        }
        
        // Alt+1 for Terminal, Alt+2 for Git Monitor
        if (event.isAltPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_1 -> {
                    viewPager.setCurrentItem(0, true)
                    return true
                }
                KeyEvent.KEYCODE_2 -> {
                    viewPager.setCurrentItem(1, true)
                    return true
                }
            }
        }
        
        // Pass key events to the current fragment
        val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
        if (currentFragment is KeyEventHandler) {
            if (currentFragment.handleKeyEvent(keyCode, event)) {
                return true
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    private fun switchToNextTab() {
        val nextIndex = (viewPager.currentItem + 1) % adapter.itemCount
        viewPager.setCurrentItem(nextIndex, true)
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start voice input
                startVoiceInput()
            } else {
                Toast.makeText(this, "Audio permission required for voice input", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, viewPager.currentItem)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Clear existing menu items
        menu.clear()
        
        // Get the current fragment by tag
        val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
        
        // Inflate menu based on current fragment type
        when (currentFragment) {
            is ConsoleFragment -> {
                // Console fragment - show console menu
                menuInflater.inflate(R.menu.console_menu, menu)
            }
            is GitMonitorFragment -> {
                // Git monitor fragment - show workspace menu
                menuInflater.inflate(R.menu.workspace_menu, menu)
            }
            else -> {
                // Default workspace menu
                menuInflater.inflate(R.menu.workspace_menu, menu)
            }
        }
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.menu_disconnect_all -> {
                // Disconnect all terminals
                // TODO: Implement disconnect all
                true
            }
            R.id.menu_settings -> {
                // Open settings
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.menu_help -> {
                // Open help
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onBackPressed() {
        // Let the current fragment handle back press first
        val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
        if (currentFragment is BackPressHandler) {
            if (currentFragment.handleBackPress()) {
                return
            }
        }
        
        super.onBackPressed()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
    
    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_TERMINAL = "terminal"
        const val TAB_GIT_MONITOR = "git_monitor"
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val REQUEST_CODE_VOICE_INPUT = 100
        private const val REQUEST_CODE_AUDIO_PERMISSION = 101
    }
    
    // Interfaces for fragment communication
    interface KeyEventHandler {
        fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean
    }
    
    interface BackPressHandler {
        fun handleBackPress(): Boolean
    }
}