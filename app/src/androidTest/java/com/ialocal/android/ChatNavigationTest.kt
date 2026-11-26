package com.ialocal.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ChatNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsAndLocalModelLibraryAreReachable() {
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Memoria y contexto").assertIsDisplayed()
        // Lazy content may start below the first viewport on small devices.
        compose.onNodeWithContentDescription("Volver al chat").performClick()
        compose.onNodeWithText("Configurar modelo").performClick()
        compose.onNodeWithText("Descargar Qwen3.5-2B").assertIsDisplayed()
    }
}
