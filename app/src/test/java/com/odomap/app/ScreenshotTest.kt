package com.odomap.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.odomap.app.record.DriveRecorder
import com.odomap.app.tts.Speaker
import com.odomap.app.ui.OdoAppUi
import com.odomap.app.ui.UiStart
import com.odomap.app.ui.design.CarScale
import com.odomap.app.ui.design.Look
import com.odomap.app.ui.design.LookStore
import com.odomap.app.ui.map.LocalCanvasMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Every screen rendered on the JVM (Robolectric native graphics + Roborazzi) with the real recorder fed by demo drives. The map is CanvasMap
 * (MapLibre's GL renderer cannot run here). Car windows at 160 dpi: narrow 840×1184 dp (two apps side by side — 발자취 tab layout) and wide
 * 1700×1184 dp (left panel). Output: ui2-<size>-<screen>.png in -PodomapRenderDir (pictures skipped without it; the flow still runs).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir: String? = System.getProperty("odomap.render.dir")
    private val spoken = ArrayList<String>()

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(150)
        compose.waitForIdle()
        val dir = outDir ?: return
        captureScreenRoboImage("$dir/ui2-$name.png")
    }

    private fun recorder(root: File): DriveRecorder {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return DriveRecorder(
            app, root,
            object : Speaker {
                override fun speak(text: String) {
                    synchronized(spoken) { spoken.add(text) }
                }

                override fun shutdown() {}
            },
        ).also { it.replaySpeed.value = 1_000_000 }
    }

    private fun waitDrives(r: DriveRecorder, n: Int) {
        val until = System.currentTimeMillis() + 180_000
        while (r.drives.value.size < n || r.replayState.value.playing) {
            check(System.currentTimeMillis() < until) { "replay timeout" }
            Thread.sleep(50)
        }
        compose.waitForIdle()
    }

    private fun run(qualifiers: String, tag: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = Files.createTempDirectory("odomap-ui").toFile()
        val r = recorder(root)
        val looks = LookStore(app).also { it.set(Look()) }
        var start by mutableStateOf(UiStart())
        compose.setContent {
            CompositionLocalProvider(LocalCanvasMap provides true) {
                CarScale { key(start) { OdoAppUi(recorder = r, looks = looks, hasLocation = true, onAskPermission = {}, start = start) } }
            }
        }
        shot("$tag-00-empty")

        val prep = listOf("commute-1-am", "commute-1-pm", "commute-2-am", "commute-3-am", "weekend-gapyeong", "weekend-gapyeong-back")
        r.playDemo(prep)
        waitDrives(r, prep.size)
        r.dismissArrival()
        assertEquals(prep.size, r.drives.value.size)
        assertTrue("visited ${r.visited.value}", r.visited.value.size >= 5)
        assertTrue("first visits", r.drives.value.any { it.firstVisits.isNotEmpty() })
        println("[ui] $tag visited ${r.visited.value.size}: ${r.visited.value}; drives " + r.drives.value.joinToString { "${it.title} ${it.regions}" })

        start = UiStart()
        shot("$tag-01-home")
        start = UiStart(expanded = true)
        shot("$tag-02-records")
        start = UiStart(expanded = true, recordsIndex = 2)
        shot("$tag-02b-records-week")
        start = UiStart(expanded = true, recordsIndex = 4)
        shot("$tag-02c-records-drives")
        val gapyeong = r.drives.value.first { it.title?.contains("가평") == true && it.firstVisits.isNotEmpty() }
        start = UiStart(stack = listOf("home", "share:map"))
        shot("$tag-09-share-map")
        start = UiStart(stack = listOf("home", "share:${gapyeong.id}"))
        shot("$tag-09b-share-drive")
        start = UiStart(stack = listOf("home", "share:regions"))
        shot("$tag-09c-share-regions")
        start = UiStart(stack = listOf("home", "replay:all"))
        shot("$tag-14-replay-all")
        start = UiStart(stack = listOf("home", "replay:${gapyeong.id}"))
        shot("$tag-14b-replay-drive")
        start = UiStart(stack = listOf("home", "replay:region:41820"))
        shot("$tag-14c-replay-region")
        start = UiStart(stack = listOf("home", "drive:${gapyeong.id}"))
        shot("$tag-03-drive")
        start = UiStart(look = true)
        shot("$tag-04-look")
        start = UiStart(stack = listOf("home", "regions"))
        shot("$tag-05-regions")
        start = UiStart(stack = listOf("home", "region:41820"))
        shot("$tag-06-region")
        start = UiStart(stack = listOf("home", "unvisited"))
        shot("$tag-07-unvisited")
        start = UiStart(stack = listOf("home", "settings"))
        shot("$tag-08-settings")

        // 밤 배색
        looks.set(Look(paper = "night"))
        start = UiStart()
        shot("$tag-10-night-home")
        start = UiStart(expanded = true)
        shot("$tag-11-night-records")
        start = UiStart(stack = listOf("home", "drive:${gapyeong.id}"))
        shot("$tag-12-night-drive")
        // 한 가지 더: 한지 + 점선
        looks.set(Look(paper = "hanji", line = com.odomap.app.ui.design.LineStyle.DOTTED))
        start = UiStart()
        shot("$tag-13-hanji-home")
        looks.set(Look())

        // 주행 중(규제): 시연 제한 풀기 끔, 느리게 틀어 중간에 찍는다
        start = UiStart(demoOverride = false)
        compose.waitForIdle()
        r.replaySpeed.value = 240
        r.playDemo(listOf("commute-3-pm"))
        val until = System.currentTimeMillis() + 120_000
        while ((r.live.value?.distanceM ?: 0.0) < 9_000) {
            check(System.currentTimeMillis() < until) { "live timeout" }
            Thread.sleep(40)
        }
        shot("$tag-20-driving")
        r.replaySpeed.value = 1_000_000
        waitDrives(r, prep.size + 1)
        shot("$tag-21-arrival")
        start = UiStart(expanded = true)
        shot("$tag-22-arrival-records")
        println("[ui] $tag tts=$spoken")

        // 드라이브 지우기: 남은 드라이브로 내 지도를 다시 만든다
        val before = r.drives.value.size
        val netBefore = r.net.value.netLengthM
        val victim = r.drives.value.first { it.title?.contains("가평에서") == true }
        var done = false
        r.deleteDrive(victim.id) { done = true }
        val deadline = System.currentTimeMillis() + 120_000
        while (!done) {
            check(System.currentTimeMillis() < deadline) { "delete timeout" }
            Thread.sleep(50)
        }
        assertEquals(before - 1, r.drives.value.size)
        assertTrue("net shrinks or stays: $netBefore → ${r.net.value.netLengthM}", r.net.value.netLengthM in 1_000.0..netBefore + 1)
        assertTrue(r.drives.value.none { it.id == victim.id })
        println("[ui] $tag delete: net ${netBefore.toInt()} → ${r.net.value.netLengthM.toInt()} m, drives $before → ${r.drives.value.size}")
        root.deleteRecursively()
    }

    @Test
    fun narrow() = run("w840dp-h1184dp-port-mdpi", "narrow")

    @Test
    fun wide() = run("w1700dp-h1184dp-land-mdpi", "wide")
}
