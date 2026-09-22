package com.prasbin.shadowlearn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.navigation.AppNav
import com.prasbin.shadowlearn.ui.theme.ShadowLearnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Warm the singleton graph on the UI thread; Room opens lazily.
        AppContainer.database(this)
        setContent {
            val dark by AppContainer.settings(this).darkMode
                .collectAsStateWithLifecycle(initialValue = true)
            ShadowLearnTheme(darkMode = dark) {
                AppNav()
            }
        }
    }
}
