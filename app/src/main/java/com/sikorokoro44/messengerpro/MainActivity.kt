package com.sikorokoro44.messengerpro

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sikorokoro44.messengerpro.ui.MessengerApp
import com.sikorokoro44.messengerpro.ui.MessengerController

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("messenger_state", Context.MODE_PRIVATE)
        val controller = MessengerController(prefs)
        setContent {
            MessengerApp(controller)
        }
    }
}
