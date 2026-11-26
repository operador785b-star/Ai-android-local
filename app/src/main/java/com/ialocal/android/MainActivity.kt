package com.ialocal.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ialocal.android.chat.ChatViewModel
import com.ialocal.android.ui.LocalApp
import com.ialocal.android.ui.LocalTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LocalTheme { LocalApp(viewModel) } }
    }
}
