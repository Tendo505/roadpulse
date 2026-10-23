package io.github.tendo505.roadpulse.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.tendo505.roadpulse.android.data.AppContainer
import io.github.tendo505.roadpulse.android.data.Speaker
import io.github.tendo505.roadpulse.android.ui.MapScreen
import io.github.tendo505.roadpulse.android.ui.MapViewModel

class RoadPulseApp : Application() {
    lateinit var container: AppContainer
        private set
    val speaker by lazy { Speaker(this) }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RoadPulseApp
        setContent {
            RoadPulseTheme {
                val vm: MapViewModel = viewModel(factory = viewModelFactory {
                    initializer { MapViewModel(app.container, speak = app.speaker::say) }
                })
                MapScreen(vm)
            }
        }
    }
}

@Composable
fun RoadPulseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF60A5FA), onPrimary = Color(0xFF06223F), secondaryContainer = Color(0xFF1E3A5F),
        primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFDCEAFE), surfaceContainer = Color(0xFF1B2129), surfaceContainerLow = Color(0xFF1B2129),
        surface = Color(0xFF151A21), background = Color(0xFF0E1217), error = Color(0xFFF87171),
    ) else lightColorScheme(
        primary = Color(0xFF1D5FD0), onPrimary = Color.White, secondaryContainer = Color(0xFFDCEAFE),
        primaryContainer = Color(0xFFDCEAFE), onPrimaryContainer = Color(0xFF0B2C5E), surfaceContainer = Color(0xFFF1F4F8), surfaceContainerLow = Color(0xFFF7F9FC),
        surface = Color.White, background = Color(0xFFF5F7FA), error = Color(0xFFC62828),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
