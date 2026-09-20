package com.vela.simulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.screens.EditorScreen
import com.vela.simulator.ui.screens.HomeScreen
import com.vela.simulator.ui.screens.RunScreen
import com.vela.simulator.ui.screens.SettingsScreen
import com.vela.simulator.ui.screens.TemplateDetailScreen
import com.vela.simulator.ui.theme.VelaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VelaTheme {
                VelaApp()
            }
        }
    }
}

@Composable
fun VelaApp(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "home"

    // 首次进入加载模板
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshTemplates() }

    Scaffold(
        containerColor = com.vela.simulator.ui.theme.VelaBg,
        bottomBar = {
            if (currentRoute.startsWith("home") || currentRoute == "settings") {
                NavigationBar(containerColor = com.vela.simulator.ui.theme.VelaSurface) {
                    NavigationBarItem(
                        selected = currentRoute.startsWith("home"),
                        onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("设备") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = { nav.navigate("settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(pad),
        ) {
            composable("home") {
                HomeScreen(vm) { id -> nav.navigate("detail/$id") }
            }
            composable(
                route = "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                TemplateDetailScreen(vm, id,
                    onRun = { nav.navigate("run/$id") },
                    onEdit = { nav.navigate("editor/$id") },
                    onBack = { nav.popBackStack() })
            }
            composable(
                route = "run/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                RunScreen(vm, id, onBack = { nav.popBackStack() })
            }
            composable(
                route = "editor/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                EditorScreen(vm, id, onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(vm)
            }
        }
    }
}
