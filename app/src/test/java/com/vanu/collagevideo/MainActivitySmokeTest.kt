package com.vanu.collagevideo

import com.vanu.faceswap.core.*

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Launches the app: fresh install -> first-run model setup; models present -> main screen. Nothing crashes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivitySmokeTest {
    private fun shot(act: android.app.Activity, name: String) {
        val v = act.window.decorView
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(System.getProperty("user.dir"), "build/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun freshInstallShowsSetup() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { act ->
                repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
                val vm = ViewModelProvider(act)[CollageViewModel::class.java]
                assertEquals(ModelPhase.NEEDED, vm.models.state.value.phase)
                assertEquals(566_479_298L, Models.COLLAGE_REQUIRED_BYTES)
                assertEquals(5, Models.COLLAGE_REQUIRED.size)
                shot(act, "setup_screen")
            }
        }
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun installedModelsShowMainScreen() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        for (s in Models.COLLAGE_REQUIRED) {
            java.io.RandomAccessFile(File(dir, s.file), "rw").use { it.setLength(s.bytes) }
            File(dir, s.file + ".ok").writeText(s.sha256)
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { act ->
                repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
                val vm = ViewModelProvider(act)[CollageViewModel::class.java]
                assertEquals(ModelPhase.READY, vm.models.state.value.phase)
                assertEquals(TransferOptions(), vm.state.value.transfer)
                vm.setTransfer(TransferOptions(face = false, hair = false, skin = false, outfit = false))   // refused: nothing to take
                assertEquals(TransferOptions(), vm.state.value.transfer)
                vm.setTransfer(TransferOptions(outfit = false))
                assertEquals(false, vm.state.value.transfer.outfit)
                repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30) }
                shot(act, "main_screen")
            }
        }
    }
}
