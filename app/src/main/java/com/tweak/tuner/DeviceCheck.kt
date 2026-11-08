package com.tweak.tuner

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import rikka.shizuku.Shizuku

data class Check(val name: String, val score: Int, val note: String)

object DeviceCheck {

    fun run(c: Context): List<Check> =
        listOf(fps(c), gameMode(c), gpu(c), resolution(), shizuku(c))

    // Max FPS: refresh rate tertinggi panel
    private fun fps(c: Context): Check {
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val hz = Math.round(dm.getDisplay(Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate })
        val s = when { hz >= 120 -> 2; hz >= 90 -> 1; else -> 0 }
        return Check("Max FPS", s, "Layar hingga $hz Hz")
    }

    // game_overlay / Game Mode: Android 12+, Game Dashboard Android 13+
    private fun gameMode(c: Context): Check {
        val sdk = Build.VERSION.SDK_INT
        val ada = sdk >= 31 && c.getSystemService("game") != null
        return when {
            ada && sdk >= 33 -> Check("Game overlay", 2, "Android ${Build.VERSION.RELEASE}: Game Mode dan Game Dashboard")
            ada -> Check("Game overlay", 1, "Android ${Build.VERSION.RELEASE}: Game Mode tersedia")
            else -> Check("Game overlay", 0, "Butuh Android 12 atau lebih baru")
        }
    }

    // GPU: OpenGL ES dan Vulkan (dasar untuk ANGLE)
    private fun gpu(c: Context): Check {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gl = am.deviceConfigurationInfo.reqGlEsVersion
        val vk = c.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)
        val ver = "OpenGL ES ${gl shr 16}.${gl and 0xffff}" + if (vk) ", Vulkan 1.1+" else ", tanpa Vulkan 1.1"
        val sdk = Build.VERSION.SDK_INT
        val s = when {
            sdk >= 29 && gl >= 0x30002 && vk -> 2
            sdk >= 29 && gl >= 0x30000 -> 1
            else -> 0
        }
        return Check("GPU dan ANGLE", s, ver)
    }

    // Resolusi dan DPI lewat wm: tersedia di semua perangkat, selama ada Shizuku
    private fun resolution() = Check("Resolusi dan DPI", 2, "Bisa diubah lewat wm")

    private fun shizuku(c: Context): Check {
        val ada = try { c.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true } catch (e: Exception) { false }
        val jalan = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        return when {
            jalan -> Check("Shizuku", 2, "Berjalan")
            ada -> Check("Shizuku", 1, "Terpasang, belum berjalan")
            else -> Check("Shizuku", 0, "Belum terpasang")
        }
    }
}