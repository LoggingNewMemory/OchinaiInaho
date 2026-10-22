/*
 * Inaho Music Player - Navigation Components
 * Copyright (C) 2026 Kanagawa Yamada
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kanagawa.yamada.inaho

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class NavItem(
    val screen: AppScreen,
    val icon: ImageVector,
    val label: String
)

private val navItems = listOf(
    NavItem(AppScreen.HOME, Icons.Default.Home, "Home"),
    NavItem(AppScreen.LIST, Icons.AutoMirrored.Filled.List, "Songs"),
    NavItem(AppScreen.PLAYLIST, Icons.AutoMirrored.Filled.QueueMusic, "Playlists"),
    NavItem(AppScreen.SETTINGS, Icons.Default.Settings, "Settings")
)

@Composable
fun NavBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    amoledBlack: Boolean,
    accentColor: Color
) {
    val containerColor = if (amoledBlack) Color(0xFF0A0A0A) else Color(0xFF1E1414)
    val unselectedColor = Color(0xFF888888)

    NavigationBar(
        containerColor = containerColor,
        contentColor = Color.White,
        tonalElevation = 8.dp
    ) {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = currentScreen == item.screen,
                onClick = { onNavigate(item.screen) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accentColor,
                    selectedTextColor = accentColor,
                    unselectedIconColor = unselectedColor,
                    unselectedTextColor = unselectedColor,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun NavRail(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    amoledBlack: Boolean,
    accentColor: Color
) {
    val containerColor = if (amoledBlack) Color(0xFF0A0A0A) else Color(0xFF1E1414)
    val unselectedColor = Color(0xFF888888)

    NavigationRail(
        containerColor = containerColor,
        contentColor = Color.White
    ) {
        navItems.forEach { item ->
            NavigationRailItem(
                selected = currentScreen == item.screen,
                onClick = { onNavigate(item.screen) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = accentColor,
                    selectedTextColor = accentColor,
                    unselectedIconColor = unselectedColor,
                    unselectedTextColor = unselectedColor,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
