package com.abdullahsolutions.kancil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.abdullahsolutions.kancil.ui.ChatScreen
import com.abdullahsolutions.kancil.ui.theme.KancilTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, KancilService::class.java))
        enableEdgeToEdge()
        setContent {
            KancilTheme {
                ChatScreen(vm)
            }
        }
    }
}
