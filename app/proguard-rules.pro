# JNI mencari fungsi Java_com_tweak_tuner_Native_*, jadi nama kelas tidak boleh berubah
-keep class com.tweak.tuner.Native { *; }
-keepclassmembers class * { native <methods>; }

# Shizuku memuat kelas ini lewat nama, di proses lain
-keep class com.tweak.tuner.ShellService { *; }
-keep class com.tweak.tuner.IShellService { *; }
-keep class com.tweak.tuner.IShellService$Stub { *; }
-keep class com.tweak.tuner.IShellService$Stub$Proxy { *; }

# API Shizuku, termasuk newProcess yang dipanggil lewat refleksi di ShellProc
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.**