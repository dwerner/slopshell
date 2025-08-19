package io.slopshell;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.appcompat.app.AppCompatActivity;
import io.slopshell.util.PreferenceConstants;

/**
 * Base activity that handles theme switching
 */
public abstract class ThemedActivity extends AppCompatActivity {
    
    private String currentTheme;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply theme before super.onCreate
        applyTheme();
        super.onCreate(savedInstanceState);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Check if theme changed while activity was paused
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String newTheme = prefs.getString(PreferenceConstants.APP_THEME, "green");
        if (!newTheme.equals(currentTheme)) {
            recreate(); // Restart activity with new theme
        }
    }
    
    private void applyTheme() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentTheme = prefs.getString(PreferenceConstants.APP_THEME, "green");
        
        int themeResId;
        switch (currentTheme) {
            case "red":
                themeResId = R.style.AppTheme_Red;
                break;
            case "blue":
                themeResId = R.style.AppTheme_Blue;
                break;
            case "purple":
                themeResId = R.style.AppTheme_Purple;
                break;
            case "orange":
                themeResId = R.style.AppTheme_Orange;
                break;
            case "green":
            default:
                themeResId = R.style.AppTheme;
                break;
        }
        
        setTheme(themeResId);
    }
    
    protected void switchTheme(String newTheme) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString(PreferenceConstants.APP_THEME, newTheme).apply();
        recreate(); // Restart activity with new theme
    }
}