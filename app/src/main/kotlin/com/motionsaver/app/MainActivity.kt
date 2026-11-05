package com.motionsaver.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionsaver.app.ui.AppRoot
import com.motionsaver.app.ui.MotionSaverTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MotionSaverApp
        val sharedText = intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            MotionSaverTheme {
                val vm: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(
                        app.container.saveMedia,
                        app.container.settings,
                        app.container.history,
                        applicationContext
                    )
                )
                androidx.compose.runtime.LaunchedEffect(sharedText) {
                    if (!sharedText.isNullOrBlank()) vm.onInputChange(sharedText)
                }
                AppRoot(vm)
            }
        }
    }
}
