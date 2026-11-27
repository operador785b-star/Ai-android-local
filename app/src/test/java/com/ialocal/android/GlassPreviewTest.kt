package com.ialocal.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.ialocal.android.chat.AppearanceMode
import com.ialocal.android.chat.ChatStore
import com.ialocal.android.chat.ChatViewModel
import com.ialocal.android.chat.MessageState
import com.ialocal.android.ui.LocalApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h830dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassPreviewTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun navigatesAcrossThemesAndSearchesRealHistory() {
        val vm = startApp(::seedChats)
        ui.onNodeWithText("Piensa en local.").assertIsDisplayed()
        ui.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
        capture("01-chat-oscuro")

        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithText("Apariencia").assertIsDisplayed()
        ui.onNodeWithContentDescription("Reducir animaciones").assertIsOn()
        capture("02-ajustes-oscuro")
        ui.onNodeWithText("Claro").performClick()
        assertEquals(AppearanceMode.LIGHT, vm.state.value.appearance.mode)
        capture("03-ajustes-claro")

        ui.onNodeWithContentDescription("Volver al chat").performClick()
        ui.onNodeWithText("Piensa en local.").assertIsDisplayed()
        capture("04-chat-claro")
        ui.onNodeWithText("Configurar modelo").performClick()
        ui.onNodeWithText("Biblioteca local").assertIsDisplayed()
        capture("05-modelos-claro")
        ui.onNodeWithTag("biblioteca-modelos").performScrollToNode(hasText("Requiere un motor compatible con lfm3."))
        ui.onNodeWithText("Requiere un motor compatible con lfm3.").assertIsDisplayed()
        ui.onNodeWithTag("biblioteca-modelos").performScrollToNode(hasText("Requiere un motor compatible con nanbeige."))
        ui.onNodeWithText("Requiere un motor compatible con nanbeige.").assertIsDisplayed()
        capture("08-candidatos-claro")
        ui.onNodeWithContentDescription("Volver al chat").performClick()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithText("Oscuro").performClick()
        ui.onNodeWithContentDescription("Volver al chat").performClick()

        ui.onNodeWithContentDescription("Historial").performClick()
        ui.onNodeWithText("Funciones en Kotlin").assertIsDisplayed()
        capture("06-historial-oscuro")

        ui.onNodeWithContentDescription("Buscar conversaciones").performTextInput("matematicas")
        ui.onNodeWithContentDescription("Buscar conversaciones").performImeAction()
        ui.onNodeWithText("Matemáticas con ejemplos").assertIsDisplayed()
        ui.onNodeWithText("Funciones en Kotlin").assertDoesNotExist()
        ui.onNodeWithContentDescription("Borrar búsqueda").performClick()
        ui.onNodeWithText("Funciones en Kotlin").performClick()
        ui.onNodeWithText("Copiar código").assertIsDisplayed()
        capture("07-conversacion-oscuro")

        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithContentDescription("Reducir animaciones").performClick()
        assertFalse(vm.state.value.appearance.reduceMotion)
        val prefs = ui.activity.getSharedPreferences("preferences", Application.MODE_PRIVATE)
        assertEquals(AppearanceMode.DARK.name, prefs.getString("appearance", null))
        assertFalse(prefs.getBoolean("reduce_motion", true))
        ui.onNodeWithContentDescription("Volver al chat").performClick()
        ui.onNodeWithText("Copiar código").assertIsDisplayed()

        ui.onRoot().performTouchInput {
            swipe(Offset(width * 0.025f, height * 0.65f), Offset(width * 0.85f, height * 0.65f))
        }
        ui.onNodeWithText("Nuevo chat").assertIsDisplayed()
        ui.onRoot().performTouchInput {
            swipe(Offset(width * 0.70f, height * 0.65f), Offset(width * 0.03f, height * 0.65f))
        }
        assertFalse(ui.onNodeWithText("Nuevo chat").isDisplayed())
        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithText("Apariencia").assertIsDisplayed()
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitUntil(4_000) { ui.onNodeWithText("Copiar código").isDisplayed() }
        ui.onNodeWithText("Copiar código").assertIsDisplayed()
    }

    private fun seedChats(app: Application) {
        ChatStore(app).use { chats ->
            val math = chats.beginTurn(null, "¿Cómo reviso una ecuación?")
            chats.rename(math.chatId, "Matemáticas con ejemplos")
            val code = chats.beginTurn(null, "¿Cómo se define una función en Kotlin?")
            chats.rename(code.chatId, "Funciones en Kotlin")
            val fence = 0x60.toChar().toString().repeat(3)
            chats.saveAnswer(
                code.answerId,
                "Ejemplo de formato para la vista previa.\n\n${fence}kotlin\nfun doble(n: Int): Int = n * 2\n$fence",
                MessageState.COMPLETE
            )
        }
    }

    private fun startApp(seed: (Application) -> Unit = {}): ChatViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seed(app)
        app.getSharedPreferences("preferences", Application.MODE_PRIVATE).edit()
            .putString("appearance", AppearanceMode.DARK.name).putBoolean("reduce_motion", true).commit()
        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[ChatViewModel::class.java] }
        ui.setContent { LocalApp(vm) }
        ui.waitUntil(10_000) { !vm.state.value.loading }
        return vm
    }

    private fun capture(name: String) {
        val directory = File(checkNotNull(System.getProperty("ia.preview.directory")))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = ui.runOnIdle {
            val root = ui.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
                root.draw(Canvas(it))
            }
        }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
