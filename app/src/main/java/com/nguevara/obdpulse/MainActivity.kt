package com.nguevara.obdpulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nguevara.obdpulse.ui.AppRoot
import com.nguevara.obdpulse.ui.theme.ObdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ObdTheme {
                AppRoot()
            }
        }
    }
}
