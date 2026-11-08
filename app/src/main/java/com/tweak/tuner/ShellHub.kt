package com.tweak.tuner

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Sambungan shell bersama (satu proses): dipakai MainActivity dan OverlayService. */
object ShellHub {
    @Volatile var svc: IShellService? = null
    @Volatile private var binding = false
    @Volatile private var bindAt = 0L

    private val local = object : IShellService.Stub() {
        override fun exec(script: String): String = ShellProc.exec(script)
        override fun destroy() {}
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = IShellService.Stub.asInterface(binder); binding = false
        }
        override fun onServiceDisconnected(name: ComponentName) { svc = null; binding = false }
    }

    private fun args(ctx: Context): Shizuku.UserServiceArgs {
        val stamp = try {
            (ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime / 1000L).toInt()
        } catch (e: Exception) { 3 }
        return Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(false).version(stamp)
    }

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** Tidak memblok. null = belum siap (Shizuku mati / izin belum / masih menyambung). */
    fun get(ctx: Context): IShellService? {
        val s = svc
        if (s != null && s.asBinder().pingBinder()) return s
        svc = null
        if (!ready()) return null
        val lama = try { Shizuku.isPreV11() || Shizuku.getVersion() < 10 } catch (e: Throwable) { false }
        if (lama) { svc = local; return local }
        val now = System.currentTimeMillis()
        if (binding && now - bindAt < 5000) return null
        if (binding) { binding = false; svc = local; return local }     // lewat 5 detik: pakai cadangan
        binding = true; bindAt = now
        try { Shizuku.bindUserService(args(ctx), conn) } catch (e: Throwable) {
            binding = false; svc = local; return local
        }
        return null
    }
}
