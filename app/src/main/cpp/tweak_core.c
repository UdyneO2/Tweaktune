#include "tweak_core.h"
#include <ctype.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define N(a) (sizeof(a) / sizeof((a)[0]))
#define BK_DIR "/data/local/tmp/"
#define BK_PROFILE BK_DIR "tweak_tuner.profile"        /* penanda profil aktif */
#define BK_LEGACY BK_DIR "tweak_tuner.bak"          /* backup versi 1.5 (satu file) */

typedef struct { const char *ns, *key, *val; } Tweak;   /* ns "@settings" = settings global */
typedef struct { const char *title; const Tweak *t; size_t n; } Section;
typedef struct { unsigned bit; const char *name, *label; } Module;

static const Module MODS[] = {
    {TC_M_TWEAK,    "tweak",    "Tweak sistem"},
    {TC_M_OVERLAY,  "overlay",  "Game overlay"},
    {TC_M_FPS,      "fps",      "FPS / refresh rate"},
    {TC_M_DISPLAY,  "display",  "Display"},
    {TC_M_WM,       "wm",       "Ukuran layar & DPI"},
    {TC_M_RENDERER, "renderer", "Renderer per game"},
};

static const char *const RD_NAME[TC_R_COUNT] = {"default", "native", "angle", "gamedriver", "prerelease"};

/* ───────────── GAMING (modul tweak) ───────────── */
static const Tweak G_ADPF[] = {
    {"game", "android.os.adpf_prefer_power_efficiency", "false"},
    {"game", "android.os.adpf_hwui_gpu", "true"},
    {"game", "android.os.adpf_fmq_eager_send", "true"},
    {"game", "android.os.adpf_use_fmq_channel_fixed", "true"},
    {"game", "android.os.adpf_measure_during_input_event_boost", "true"},
    {"game", "android.os.adpf_obtainview_boost", "true"},
};
static const Tweak G_UI[] = {
    {"windowing_frontend", "com.android.window.flags.fifo_priority_for_major_ui_processes", "true"},
};
static const Tweak G_BG[] = {
    {"activity_manager", "max_phantom_processes", "2147483647"},
    {"activity_manager", "use_compaction", "false"},
    {"activity_manager_native_boot", "use_freezer", "false"},
    {"@settings", "settings_enable_monitor_phantom_procs", "false"},
};
static const Tweak G_NET[] = {
    {"netd_native", "parallel_lookup", "1"},
    {"netd_native", "retransmission_time_interval", "1500"},
};
static const Tweak G_EXIST[] = {   /* refresh_rate / game_default_frame_rate_override pindah ke modul FPS */
    {"surfaceflinger", "enable_frame_rate_override", "0"},
    {"surfaceflinger", "set_idle_timer_ms", "0"},
    {"surfaceflinger", "set_touch_timer_ms", "0"},
    {"surfaceflinger", "set_display_power_timer_ms", "0"},
    {"surfaceflinger", "uclamp.min", "130"},
    {"surfaceflinger", "vsync_eventphase_offset_ns", "1200000"},
    {"surfaceflinger", "vsync_sfoffset_ns", "1200000"},
    {"surfaceflinger", "max_frame_buffer_acquired_count", "3"},
    {"surfaceflinger", "min_swap_interval", "0"},
    {"surfaceflinger", "disable_expensive_timestamps", "1"},
    {"surfaceflinger", "use_content_detection_for_refresh_rate", "1"},
    {"surfaceflinger", "set_max_frame_rate_multiplier", "0.5"},
    {"input", "touch_screen_sample_interval_ms", "8"},
    {"input", "filtered_accel_event_rate_hz", "240"},
    {"input", "default_key_press_repeat_rate", "33"},
};
static const Section GAMING[] = {
    {"ADPF / game hint", G_ADPF, N(G_ADPF)},
    {"Prioritas UI", G_UI, N(G_UI)},
    {"Anti kill/freeze background", G_BG, N(G_BG)},
    {"DNS", G_NET, N(G_NET)},
    {"Tweak yang sudah ada (dikunci)", G_EXIST, N(G_EXIST)},
};

/* ───────────── DISPLAY ───────────── */
static const Tweak M_DISPLAY[] = {
    {"display_manager", "com.android.server.display.feature.flags.enable_vsync_low_power_vote", "false"},
    {"display_manager", "com.android.server.display.feature.flags.enable_vsync_low_light_vote", "false"},
    {"display_manager", "com.android.server.display.feature.flags.idle_screen_refresh_rate_timeout", "false"},
};

/* ───────────── DAILY (modul tweak) ───────────── */
static const Tweak D_POWER[] = {
    {"backstage_power", "com.android.server.job.batch_active_bucket_jobs", "true"},
    {"backstage_power", "com.android.server.job.batch_connectivity_jobs_per_network", "true"},
    {"backstage_power", "com.android.server.deviceidle.disable_wakelocks_in_light_idle", "true"},
};
static const Tweak D_AM[] = {
    {"backstage_power", "com.android.server.am.batching_oom_adj", "true"},
    {"backstage_power", "com.android.server.am.skip_unimportant_connections", "true"},
};
static const Tweak D_MEM[] = {
    {"windowing_frontend", "com.android.window.flags.release_snapshot_aggressively", "true"},
    {"system_performance", "com.android.art.rw.flags.madvise_optimized_readahead", "true"},
    {"system_performance", "com.android.art.rw.flags.madvise_odex_using_profile", "true"},
};
static const Tweak D_INPUT[] = {
    {"input", "com.android.input.flags.rate_limit_user_activity_poke_in_dispatcher", "true"},
};
static const Tweak D_NET[] = {
    {"netd_native", "parallel_lookup", "1"},
    {"netd_native", "max_cache_entries", "1000"},
};
static const Section DAILY[] = {
    {"Baterai", D_POWER, N(D_POWER)},
    {"Activity Manager", D_AM, N(D_AM)},
    {"Memori & buka aplikasi", D_MEM, N(D_MEM)},
    {"Input", D_INPUT, N(D_INPUT)},
    {"DNS", D_NET, N(D_NET)},
};

/* ───────────── Script shell ─────────────
 * Setiap modul punya file backup sendiri ($BK diatur di awal tiap modul).
 * Semua perintah di dalam fungsi diberi </dev/null supaya cmd/settings/wm tidak
 * memakan stdin (penyebab loop reset berhenti setelah baris pertama). */
static const char PRE[] =
    "#!/system/bin/sh\n"
    "bk() { grep -q \"^$1 $2 \" \"$BK\" || echo \"$1 $2 $3\" >> \"$BK\"; }\n"
    "dput() { old=$(device_config get \"$1\" \"$2\" 2>/dev/null </dev/null); bk \"$1\" \"$2\" \"${old:-null}\"; "
    "if device_config put \"$1\" \"$2\" \"$3\" >/dev/null 2>&1 </dev/null; then echo \"OK  $1/$2\"; else echo \"GAGAL $1/$2\"; fi; }\n"
    "sput() { case \"$1\" in global) n=@settings;; *) n=@$1;; esac; "
    "old=$(settings get \"$1\" \"$2\" 2>/dev/null </dev/null); bk \"$n\" \"$2\" \"${old:-null}\"; "
    "if settings put \"$1\" \"$2\" \"$3\" >/dev/null 2>&1 </dev/null; then echo \"OK  $1/$2\"; else echo \"GAGAL $1/$2\"; fi; }\n"
    "sdel() { case \"$1\" in global) n=@settings;; *) n=@$1;; esac; "
    "old=$(settings get \"$1\" \"$2\" 2>/dev/null </dev/null); [ \"${old:-null}\" = null ] && return 0; bk \"$n\" \"$2\" \"$old\"; "
    "if settings delete \"$1\" \"$2\" >/dev/null 2>&1 </dev/null; then echo \"DEL $1/$2\"; else echo \"GAGAL $1/$2\"; fi; }\n";

static const char RV[] =
    "rv() {\n"
    "  f=$1\n"
    "  if [ ! -s \"$f\" ]; then [ \"$3\" = q ] || echo \"-- $2: tidak ada yang perlu di-reset\"; return 0; fi\n"
    "  : > \"$f.new\"\n"
    "  while read -r ns key old; do\n"
    "    ok=1\n"
    "    case \"$ns\" in\n"
    "      @settings|@system|@secure)\n"
    "        t=${ns#@}; [ \"$t\" = settings ] && t=global\n"
    "        if [ \"$old\" = null ]; then settings delete \"$t\" \"$key\" </dev/null >/dev/null 2>&1 || ok=0\n"
    "        else settings put \"$t\" \"$key\" \"$old\" </dev/null >/dev/null 2>&1 || ok=0; fi ;;\n"
    "      @game) cmd game reset \"$key\" </dev/null >/dev/null 2>&1 ;;\n"
    "      @wm) wm \"$key\" reset </dev/null >/dev/null 2>&1 ;;\n"
    "      *)\n"
    "        if [ \"$old\" = null ]; then device_config delete \"$ns\" \"$key\" </dev/null >/dev/null 2>&1 || ok=0\n"
    "        else device_config put \"$ns\" \"$key\" \"$old\" </dev/null >/dev/null 2>&1 || ok=0; fi ;;\n"
    "    esac\n"
    "    if [ $ok = 1 ]; then echo \"REVERT $ns $key\"; else echo \"GAGAL  $ns $key\"; echo \"$ns $key $old\" >> \"$f.new\"; fi\n"
    "  done < \"$f\"\n"
    "  if [ -s \"$f.new\" ]; then mv \"$f.new\" \"$f\"; echo \"-- $2: sebagian gagal, backup disimpan\"; "
    "else rm -f \"$f\" \"$f.new\"; echo \"-- $2: selesai\"; fi\n"
    "}\n";

static const char DETECT[] =
    "#!/system/bin/sh\n"
    "p() { echo \"RD $1=$2\"; }\n"
    "f=$(pm list features </dev/null 2>/dev/null)\n"
    "a=0\n"
    "[ \"$(getprop ro.gfx.angle.supported)\" = true ] && a=1\n"
    "for x in /system/lib64/libEGL_angle.so /system/lib/libEGL_angle.so /system/lib64/egl/libEGL_angle.so "
    "/system/lib/egl/libEGL_angle.so /vendor/lib64/egl/libEGL_angle.so /vendor/lib/egl/libEGL_angle.so; do [ -e \"$x\" ] && a=1; done\n"
    "pm path com.android.angle </dev/null 2>/dev/null | grep -q '^package:' && a=1\n"
    "p angle $a\n"
    "p native 1\n"
    "d=0\n"
    "[ -n \"$(getprop ro.gfx.driver.0)\" ] && d=1\n"
    "pm list packages </dev/null 2>/dev/null | grep -qiE 'graphicsdriver|gpudrivers|gpu\\.driver|gamedriver' && d=1\n"
    "p gamedriver $d\n"
    "r=0\n"
    "[ -n \"$(getprop ro.gfx.driver.1)\" ] && r=1\n"
    "p prerelease $r\n"
    "k=$(echo \"$f\" | sed -n 's/.*android.hardware.vulkan.version=\\([0-9]*\\).*/\\1/p' | head -n 1)\n"
    "v=0\n"
    "[ -n \"$k\" ] && v=1\n"
    "[ -n \"$(getprop ro.hardware.vulkan)\" ] && v=1\n"
    "for x in /vendor/lib64/hw/vulkan.*.so /vendor/lib/hw/vulkan.*.so /system/lib64/hw/vulkan.*.so; do [ -e \"$x\" ] && v=1; done\n"
    "p vulkan $v\n"
    "g=$(echo \"$f\" | sed -n 's/.*reqGlEsVersion=0x\\([0-9a-fA-F]*\\).*/\\1/p' | head -n 1)\n"
    "if [ -n \"$g\" ]; then g=$((0x$g)); echo \"INFO gles=$((g>>16)).$((g&65535))\"; fi\n"
    "if [ -n \"$k\" ]; then echo \"INFO vulkan=$((k>>22)).$(((k>>12)&1023))\"; fi\n"
    "s=$(dumpsys SurfaceFlinger </dev/null 2>/dev/null | grep -m1 '^GLES:')\n"
    "gpu=$(echo \"$s\" | cut -d, -f2 | sed 's/^ *//')\n"
    "[ -n \"$gpu\" ] || gpu=$(getprop ro.hardware.egl)\n"
    "echo \"INFO gpu=$gpu\"\n"
    "echo \"INFO glstr=$(echo \"$s\" | cut -d, -f3- | sed 's/^ *//')\"\n"
    "echo \"INFO vkhw=$(getprop ro.hardware.vulkan)\"\n";

/* Pemantau FPS: jalan di latar belakang (uid shell), tulis satu baris per detik ke tt_fps.out:
 *   paket|API|fps|GPU|layer|jumlah_sampel|catatan
 * catatan: ok, nofocus (app depan tak terdeteksi), nolayer, novalid (tak ada timestamp), stale (tak ada frame baru).
 * Berhenti jika tt_fps.stop dibuat, atau tidak ada detak (tt_fps.hb) +-8 detik. */
static const char MONITOR[] =
    "#!/system/bin/sh\n"
    "D=/data/local/tmp\n"
    "OUT=$D/tt_fps.out; HB=$D/tt_fps.hb; STOP=$D/tt_fps.stop\n"
    "rm -f \"$STOP\"\n"
    "echo $$ > $D/tt_fps.pid\n"
    "gpu=$(dumpsys SurfaceFlinger </dev/null 2>/dev/null | grep -m1 '^GLES:' | cut -d, -f2 | sed 's/^ *//')\n"
    "[ -n \"$gpu\" ] || gpu=$(getprop ro.hardware.egl)\n"
    "# stdin: keluaran --latency; stdout: \"<ms terakhir> <frame dlm 1 detik> <sampel valid>\"\n"
    "calc() {\n"
    "  read -r p\n"
    "  last=0; L=\"\"; v=0\n"
    "  while read -r d a f; do\n"
    "    t=$a\n"
    "    case \"$t\" in \"\"|0) t=$f;; esac\n"
    "    case \"$t\" in \"\"|0) t=$d;; esac\n"
    "    case \"$t\" in \"\"|0) continue;; esac\n"
    "    [ ${#t} -gt 17 ] && continue\n"
    "    [ ${#t} -le 6 ] && continue\n"
    "    t=${t%??????}; L=\"$L $t\"; last=$t; v=$((v+1))\n"
    "  done\n"
    "  n=0; lim=$((last-1000))\n"
    "  for t in $L; do [ \"$t\" -gt \"$lim\" ] && n=$((n+1)); done\n"
    "  echo \"$last $n $v\"\n"
    "}\n"
    "# pilih layer kandidat dengan frame terbanyak (stdin: daftar kandidat)\n"
    "pick() {\n"
    "  while read -r c; do\n"
    "    [ -n \"$c\" ] || continue\n"
    "    r=$(dumpsys SurfaceFlinger --latency \"$c\" </dev/null 2>/dev/null | calc)\n"
    "    set -- $r\n"
    "    echo \"${2:-0} $c\"\n"
    "  done | sort -rn | head -n 1\n"
    "}\n"
    "# cadangan (Android 14+ : --latency kosong): total frame per layer dari --timestats; $1 = paket\n"
    "# format per layer: layerName = ..., packageName = ..., totalFrames = N\n"
    "tsframes() {\n"
    "  dumpsys SurfaceFlinger --timestats -dump </dev/null 2>/dev/null | {\n"
    "    cur=\"\"; ln=\"\"; best=0\n"
    "    while read -r k eq v; do\n"
    "      case \"$k\" in\n"
    "        layerName) ln=$v; cur=\"\"\n"
    "          case \"$v\" in *\"Background for\"*|*\"Bounds for\"*) ;; *\"$1\"*) cur=1;; esac;;\n"
    "        packageName) case \"$ln\" in *\"Background for\"*|*\"Bounds for\"*) ;; *) [ \"$v\" = \"$1\" ] && cur=1;; esac;;\n"
    "        totalFrames) [ -n \"$cur\" ] && [ \"${v:-0}\" -gt \"$best\" ] 2>/dev/null && best=$v; cur=\"\";;\n"
    "      esac\n"
    "    done\n"
    "    echo $best\n"
    "  }\n"
    "}\n"
    "pkg=\"\"; layer=\"\"; api=\"-\"; prev=0; i=0; miss=0; zero=0; vv=0; note=nofocus; tsen=0; ptf=\"\"; pup=0\n"
    "while :; do\n"
    "  [ -f \"$STOP\" ] && break\n"
    "  if [ -f \"$HB\" ]; then rm -f \"$HB\"; miss=0; else miss=$((miss+1)); [ $miss -ge 8 ] && break; fi\n"
    "  if [ $((i % 3)) -eq 0 ]; then\n"
    "    np=$(dumpsys window </dev/null 2>/dev/null | grep -m1 mCurrentFocus | sed -n 's/.* \\([A-Za-z0-9_.]*\\)\\/[^ }]*.*/\\1/p')\n"
    "    [ -n \"$np\" ] || np=$(dumpsys activity activities </dev/null 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | sed -n 's/.* \\([A-Za-z0-9_.]*\\)\\/[^ }]*.*/\\1/p')\n"
    "    if [ \"$np\" != \"$pkg\" ]; then pkg=$np; layer=\"\"; api=\"-\"; prev=0; zero=0; fi\n"
    "  fi\n"
    "  if [ -n \"$pkg\" ] && [ -z \"$layer\" ]; then\n"
    "    cands=$(dumpsys SurfaceFlinger --list </dev/null 2>/dev/null | grep -F \"$pkg\" | grep -v -E 'Background for|Bounds for|^Task=|ActivityRecord|WindowToken|Letterbox|Wallpaper' | head -n 8)\n"
    "    best=$(echo \"$cands\" | pick)\n"
    "    layer=${best#* }\n"
    "    [ \"$layer\" = \"$best\" ] && layer=\"\"\n"
    "  fi\n"
    "  if [ -n \"$pkg\" ] && { [ \"$api\" = \"-\" ] || [ $((i % 10)) -eq 0 ]; }; then\n"
    "    pid=$(pidof \"$pkg\" 2>/dev/null | cut -d' ' -f1)\n"
    "    if [ -z \"$pid\" ] || ! grep -q . \"/proc/$pid/maps\" 2>/dev/null; then api=GPU\n"
    "    elif grep -q libEGL_angle \"/proc/$pid/maps\"; then api=ANGLE\n"
    "    elif grep -q 'libvulkan.so' \"/proc/$pid/maps\"; then api=Vulkan\n"
    "    else api=\"OpenGL ES\"; fi\n"
    "  fi\n"
    "  fps=0; vv=0\n"
    "  if [ -z \"$pkg\" ]; then note=nofocus\n"
    "  elif [ -z \"$layer\" ]; then note=nolayer\n"
    "  else\n"
    "    res=$(dumpsys SurfaceFlinger --latency \"$layer\" </dev/null 2>/dev/null | calc)\n"
    "    set -- $res\n"
    "    l=${1:-0}; vv=${3:-0}\n"
    "    if [ \"$vv\" -eq 0 ]; then note=novalid\n"
    "    elif [ \"$l\" = \"$prev\" ]; then note=stale\n"
    "    else fps=${2:-0}; note=ok; fi\n"
    "    prev=$l\n"
    "    if [ $fps -eq 0 ]; then zero=$((zero+1)); [ $zero -ge 4 ] && { layer=\"\"; zero=0; }; else zero=0; fi\n"
    "  fi\n"
    "  if [ $fps -eq 0 ] && [ -n \"$pkg\" ] && { [ \"$note\" = nolayer ] || [ \"$note\" = novalid ]; }; then\n"
    "    if [ $tsen -eq 0 ]; then dumpsys SurfaceFlinger --timestats -enable </dev/null >/dev/null 2>&1; tsen=1; fi\n"
    "    tf=$(tsframes \"$pkg\"); read up _ < /proc/uptime; up=${up%.*}${up#*.}\n"
    "    if [ -n \"$ptf\" ] && [ \"$tf\" -ge \"$ptf\" ] && [ \"$up\" -gt \"$pup\" ]; then\n"
    "      d=$(( (tf-ptf)*100/(up-pup) )); [ $d -gt 0 ] && { fps=$d; note=timestats; }\n"
    "    fi\n"
    "    ptf=$tf; pup=$up\n"
    "  fi\n"
    "  echo \"$pkg|$api|$fps|$gpu|$layer|$vv|$note\" > \"$OUT.tmp\" && mv \"$OUT.tmp\" \"$OUT\"\n"
    "  i=$((i+1))\n"
    "  sleep 1\n"
    "done\n"
    "[ $tsen -eq 1 ] && dumpsys SurfaceFlinger --timestats -disable </dev/null >/dev/null 2>&1\n"
    "rm -f \"$OUT\" \"$OUT.tmp\" \"$D/tt_fps.pid\"\n";

/* Status: profil aktif = isi penanda tweak_tuner.profile, selama masih ada backup modul (jika tidak: standard) */
static const char STATUS[] =
    "#!/system/bin/sh\n"
    "D=/data/local/tmp\n"
    "any=0\n"
    "for x in tweak overlay fps display wm renderer; do\n"
    "  if [ -s \"$D/tweak_tuner.$x.bak\" ]; then echo \"MOD $x=1\"; any=1; else echo \"MOD $x=0\"; fi\n"
    "done\n"
    "[ -s \"$D/tweak_tuner.bak\" ] && any=1\n"
    "m=standard\n"
    "[ -f \"$D/tweak_tuner.profile\" ] && m=$(cat \"$D/tweak_tuner.profile\")\n"
    "[ $any -eq 0 ] && m=standard\n"
    "echo \"PROFILE $m\"\n"
    "echo \"DEBUG penanda=$(cat \"$D/tweak_tuner.profile\" 2>/dev/null) backup=$(ls $D/tweak_tuner*.bak 2>/dev/null | wc -l)\"\n";

/* ───────────── Buffer ───────────── */
typedef struct { char *p; size_t cap, len; int over; } Buf;

static void bf(Buf *b, const char *fmt, ...) {
    va_list ap;
    int n;
    if (b->over) return;
    va_start(ap, fmt);
    n = vsnprintf(b->p + b->len, b->cap - b->len, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= b->cap - b->len) b->over = 1; else b->len += (size_t)n;
}

static void seterr(char *err, size_t cap, const char *m) {
    if (err && cap) snprintf(err, cap, "%s", m);
}

/* ───────────── Nama ───────────── */
const char *tc_renderer_name(int r) { return (r >= 0 && r < TC_R_COUNT) ? RD_NAME[r] : "default"; }

int tc_renderer_from_name(const char *s) {
    int i;
    if (!s) return -1;
    for (i = 0; i < TC_R_COUNT; i++) if (!strcmp(s, RD_NAME[i])) return i;
    return -1;
}

unsigned tc_module_from_name(const char *s) {
    size_t i;
    if (!s) return 0;
    for (i = 0; i < N(MODS); i++) if (!strcmp(s, MODS[i].name)) return MODS[i].bit;
    return 0;
}

unsigned tc_default_modules(TcProfile p) {
    return p == TC_PROFILE_DAILY ? (TC_M_TWEAK | TC_M_WM) : TC_M_ALL;
}

static unsigned eff_mask(const TcConfig *c) {
    return c->modules ? (c->modules & TC_M_ALL) : tc_default_modules(c->profile);
}

/* ───────────── Validasi ───────────── */
static int valid_pkg(const char *s, size_t n) {
    size_t i;
    if (n == 0 || n > 200 || !isalpha((unsigned char)s[0])) return 0;
    for (i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (!(isalnum(c) || c == '.' || c == '_')) return 0;
    }
    return 1;
}

static const char *next_pkg(const char *s, char *tok, size_t cap) {
    size_t n = 0;
    while (*s == ',') s++;
    if (!*s) return NULL;
    while (*s && *s != ',') { if (n + 1 < cap) tok[n] = *s; n++; s++; }
    tok[n < cap ? n : cap - 1] = 0;
    return s;
}

/* "pkg=angle" -> pkg dan renderer; -1 jika format salah */
static int split_rd(char *tok, char **pkg) {
    char *eq = strchr(tok, '=');
    if (!eq) return -1;
    *eq = 0;
    *pkg = tok;
    return tc_renderer_from_name(eq + 1);
}

static int in_csv(const char *csv, const char *pkg) {
    char tok[256];
    const char *s = csv ? csv : "";
    while ((s = next_pkg(s, tok, sizeof tok)) != NULL) if (!strcmp(tok, pkg)) return 1;
    return 0;
}

void tc_defaults(TcConfig *c) {
    memset(c, 0, sizeof *c);
    c->profile = TC_PROFILE_GAMING;
    c->fps = 60;
    c->scale = 1.0;
    c->games = "";
    c->renderers = "";
}

int tc_validate(const TcConfig *c, char *err, size_t ecap) {
    unsigned m;
    if (c->profile < TC_PROFILE_GAMING || c->profile > TC_PROFILE_STANDARD) { seterr(err, ecap, "Mode tidak valid"); return -1; }
    if (c->profile == TC_PROFILE_STANDARD) return 0;     /* hanya reset */
    m = eff_mask(c);
    if ((m & (TC_M_OVERLAY | TC_M_FPS)) && (c->fps < 30 || c->fps > 240)) { seterr(err, ecap, "FPS harus 30-240"); return -1; }
    if ((m & TC_M_OVERLAY) && !(c->scale >= 0.30 && c->scale <= 1.00)) { seterr(err, ecap, "Skala harus 0.30-1.00"); return -1; }
    if (m & (TC_M_OVERLAY | TC_M_RENDERER)) {
        const char *s = c->games ? c->games : "";
        char tok[256];
        int count = 0;
        if (strlen(s) > 100000) { seterr(err, ecap, "Daftar game terlalu panjang"); return -1; }
        while ((s = next_pkg(s, tok, sizeof tok)) != NULL) {
            if (!valid_pkg(tok, strlen(tok))) { seterr(err, ecap, "Nama paket tidak valid"); return -1; }
            if (++count > 500) { seterr(err, ecap, "Maksimal 500 game"); return -1; }
        }
    }
    if (m & TC_M_RENDERER) {
        const char *s = c->renderers ? c->renderers : "";
        char tok[256], *pkg;
        int count = 0;
        if (strlen(s) > 100000) { seterr(err, ecap, "Daftar renderer terlalu panjang"); return -1; }
        while ((s = next_pkg(s, tok, sizeof tok)) != NULL) {
            if (split_rd(tok, &pkg) < 0 || !valid_pkg(pkg, strlen(pkg))) { seterr(err, ecap, "Format renderer: paket=nama"); return -1; }
            if (++count > 500) { seterr(err, ecap, "Maksimal 500 renderer"); return -1; }
        }
    }
    if (m & TC_M_WM) {
        if ((c->wm_w == 0) != (c->wm_h == 0)) { seterr(err, ecap, "Ukuran layar harus lengkap (WxH)"); return -1; }
        if (c->wm_w && (c->wm_w < 320 || c->wm_w > 4400 || c->wm_h < 320 || c->wm_h > 4400)) { seterr(err, ecap, "Ukuran layar 320-4400"); return -1; }
        if (c->density && (c->density < 120 || c->density > 800)) { seterr(err, ecap, "DPI harus 120-800"); return -1; }
    }
    return 0;
}

/* ───────────── Renderer efektif per paket ───────────── */
/* entri eksplisit di c->renderers menang; game terpilih tanpa entri: ANGLE hanya jika --angle, selain itu tidak diubah */
static int eff_renderer(const TcConfig *c, const char *pkg) {
    char tok[256], *p;
    const char *s = c->renderers ? c->renderers : "";
    while ((s = next_pkg(s, tok, sizeof tok)) != NULL) {
        int r = split_rd(tok, &p);
        if (r >= 0 && !strcmp(p, pkg)) return r;
    }
    if (in_csv(c->games, pkg)) return c->use_angle ? TC_R_ANGLE : -1;
    return -1;
}

typedef void (*PkgFn)(const char *pkg, int r, void *ctx);

static void each_pkg(const TcConfig *c, PkgFn fn, void *ctx) {
    char tok[256], *p;
    const char *s;
    for (s = c->games ? c->games : ""; (s = next_pkg(s, tok, sizeof tok)) != NULL;)
        fn(tok, eff_renderer(c, tok), ctx);
    for (s = c->renderers ? c->renderers : ""; (s = next_pkg(s, tok, sizeof tok)) != NULL;) {
        if (split_rd(tok, &p) >= 0 && !in_csv(c->games, p)) fn(p, eff_renderer(c, p), ctx);
    }
}

typedef struct { Buf *b; unsigned mask; int values; int n; } ListCtx;

static void list_cb(const char *pkg, int r, void *v) {
    ListCtx *x = (ListCtx *)v;
    if (r < 0 || !((1u << r) & x->mask)) return;
    if (x->b) bf(x->b, "%s%s", x->n ? "," : "", x->values ? tc_renderer_name(r) : pkg);
    x->n++;
}

static void emit_setting_list(Buf *b, const TcConfig *c, const char *key, unsigned mask, int values) {
    ListCtx cnt = {NULL, mask, values, 0}, w;
    each_pkg(c, list_cb, &cnt);
    if (!cnt.n) { bf(b, "sdel global '%s'\n", key); return; }
    w.b = b; w.mask = mask; w.values = values; w.n = 0;
    bf(b, "sput global '%s' '", key);
    each_pkg(c, list_cb, &w);
    bf(b, "'\n");
}

/* ───────────── Builder modul ───────────── */
static void emit_sections(Buf *b, const Section *sec, size_t ns) {
    size_t i, j;
    for (i = 0; i < ns; i++) {
        bf(b, "\n# [%zu] %s\n", i + 1, sec[i].title);
        for (j = 0; j < sec[i].n; j++) {
            const Tweak *t = &sec[i].t[j];
            if (t->ns[0] == '@') bf(b, "sput global '%s' '%s'\n", t->key, t->val);
            else bf(b, "dput '%s' '%s' '%s'\n", t->ns, t->key, t->val);
        }
    }
}

static void emit_flat(Buf *b, const Tweak *t, size_t n) {
    size_t i;
    for (i = 0; i < n; i++) bf(b, "dput '%s' '%s' '%s'\n", t[i].ns, t[i].key, t[i].val);
}

static void mod_begin(Buf *b, const Module *m, int sync) {
    bf(b, "\necho \"== %s ==\"\nBK=" BK_DIR "tweak_tuner.%s.bak\n[ -f \"$BK\" ] || : > \"$BK\"\n", m->label, m->name);
    if (sync) bf(b, "device_config set_sync_disabled_for_tests persistent\n");
}

static void emit_overlay(Buf *b, const TcConfig *c) {
    char tok[256];
    const char *s;
    int fps_bat = c->fps < 60 ? c->fps : 60, any = 0;
    for (s = c->games ? c->games : ""; (s = next_pkg(s, tok, sizeof tok)) != NULL; any = 1)
        bf(b, "dput 'game_overlay' '%s' 'mode=2,downscaleFactor=%.2f,fps=%d:mode=3,downscaleFactor=%.2f,fps=%d'\n",
           tok, c->scale, c->fps, c->scale, fps_bat);
    if (!any) { bf(b, "echo \"(tidak ada game dipilih)\"\n"); return; }
    bf(b, "sleep 1\n");
    for (s = c->games; (s = next_pkg(s, tok, sizeof tok)) != NULL;)
        bf(b, "bk @game '%s' set\ncmd game set --mode performance --fps %d '%s' </dev/null >/dev/null 2>&1 && echo \"MODE perf %s\"\n",
           tok, c->fps, tok, tok);
}

static void emit_fps(Buf *b, const TcConfig *c) {
    bf(b, "dput 'surfaceflinger' 'game_default_frame_rate_override' '%d'\n", c->fps);
    bf(b, "dput 'surfaceflinger' 'refresh_rate' '%d'\n", c->fps);
    bf(b, "sput system 'peak_refresh_rate' '%d.0'\n", c->fps);
    bf(b, "sput system 'min_refresh_rate' '%d.0'\n", c->fps);
}

static void emit_wm(Buf *b, const TcConfig *c) {
    if (!c->wm_w && !c->density) { bf(b, "echo \"(ukuran/DPI tidak diisi)\"\n"); return; }
    if (c->wm_w) bf(b, "bk @wm size reset\nwm size %dx%d </dev/null\n", c->wm_w, c->wm_h);
    if (c->density) bf(b, "bk @wm density reset\nwm density %d </dev/null\n", c->density);
}

static void emit_renderer(Buf *b, const TcConfig *c) {
    ListCtx cnt = {NULL, ~0u, 0, 0};
    each_pkg(c, list_cb, &cnt);
    if (!cnt.n) { bf(b, "echo \"(tidak ada pilihan renderer)\"\n"); return; }
    emit_setting_list(b, c, "game_driver_opt_in_apps", 1u << TC_R_GAMEDRV, 0);
    emit_setting_list(b, c, "updatable_driver_production_opt_in_apps", 1u << TC_R_GAMEDRV, 0);
    emit_setting_list(b, c, "game_driver_prerelease_opt_in_apps", 1u << TC_R_PRERELEASE, 0);
    emit_setting_list(b, c, "updatable_driver_prerelease_opt_in_apps", 1u << TC_R_PRERELEASE, 0);
    emit_setting_list(b, c, "game_driver_opt_out_apps", 1u << TC_R_NATIVE, 0);
    emit_setting_list(b, c, "updatable_driver_production_opt_out_apps", 1u << TC_R_NATIVE, 0);
    emit_setting_list(b, c, "angle_gl_driver_selection_pkgs", (1u << TC_R_ANGLE) | (1u << TC_R_NATIVE), 0);
    emit_setting_list(b, c, "angle_gl_driver_selection_values", (1u << TC_R_ANGLE) | (1u << TC_R_NATIVE), 1);
    bf(b, "echo \"Mulai ulang game agar renderer berlaku\"\n");
}

int tc_build_revert(unsigned modules, char *out, size_t cap) {
    Buf b = {out, cap, 0, 0};
    unsigned m = modules ? (modules & TC_M_ALL) : TC_M_ALL;
    size_t i;
    if (!out || cap < 4096) return -1;
    out[0] = 0;
    bf(&b, "#!/system/bin/sh\n%s", RV);
    for (i = 0; i < N(MODS); i++)
        if (m & MODS[i].bit) bf(&b, "rv \"" BK_DIR "tweak_tuner.%s.bak\" \"%s\"\n", MODS[i].name, MODS[i].label);
    if (!modules) bf(&b, "rv \"" BK_LEGACY "\" \"Backup lama (v1.5)\" q\nrm -f " BK_PROFILE "\n");
    bf(&b, "if cat " BK_DIR "tweak_tuner*.bak 2>/dev/null | grep -q '^[^@]'; then echo \"Masih ada tweak aktif dari modul lain\"; "
           "else device_config set_sync_disabled_for_tests none; fi\n"
           "%s"
           "echo \"Selesai. Reboot bila perlu.\n@mnrdnn\"\n",
       modules ? "ls " BK_DIR "tweak_tuner*.bak >/dev/null 2>&1 || rm -f " BK_DIR "tweak_tuner.profile\n"
               : "rm -f " BK_DIR "tweak_tuner.profile\n");     /* reset semua = kembali ke Standar */
    return b.over ? -1 : 0;
}

int tc_build_apply(const TcConfig *c, char *out, size_t cap, char *err, size_t ecap) {
    Buf b = {out, cap, 0, 0};
    unsigned m;
    size_t i;
    if (!out || cap < 4096) { seterr(err, ecap, "Buffer terlalu kecil"); return -1; }
    if (tc_validate(c, err, ecap) != 0) return -1;
    if (c->profile == TC_PROFILE_STANDARD) {          /* standar = kembali ke bawaan */
        if (tc_build_revert(c->modules, out, cap) != 0) { seterr(err, ecap, "Output melebihi buffer"); return -1; }
        return 0;
    }
    m = eff_mask(c);
    out[0] = 0;
    bf(&b, "%s", PRE);
    for (i = 0; i < N(MODS); i++) {
        if (!(m & MODS[i].bit)) continue;
        switch (MODS[i].bit) {
        case TC_M_TWEAK:
            mod_begin(&b, &MODS[i], 1);
            if (c->profile == TC_PROFILE_GAMING) emit_sections(&b, GAMING, N(GAMING));
            else emit_sections(&b, DAILY, N(DAILY));
            break;
        case TC_M_OVERLAY:  mod_begin(&b, &MODS[i], 1); emit_overlay(&b, c); break;
        case TC_M_FPS:      mod_begin(&b, &MODS[i], 1); emit_fps(&b, c); break;
        case TC_M_DISPLAY:  mod_begin(&b, &MODS[i], 1); emit_flat(&b, M_DISPLAY, N(M_DISPLAY)); break;
        case TC_M_WM:       mod_begin(&b, &MODS[i], 0); emit_wm(&b, c); break;
        case TC_M_RENDERER: mod_begin(&b, &MODS[i], 0); emit_renderer(&b, c); break;
        }
    }
    if (!c->modules)        /* penanda profil aktif hanya saat preset penuh diterapkan */
        bf(&b, "\necho %s > " BK_PROFILE "\n", c->profile == TC_PROFILE_DAILY ? "daily" : "gaming");
    if (!c->modules)       /* profil penuh = profil aktif; per-modul tidak mengubah penanda */
        bf(&b, "echo %s > " BK_DIR "tweak_tuner.profile\n", c->profile == TC_PROFILE_GAMING ? "gaming" : "daily");
    bf(&b, "\necho \"Selesai. Jangan Reboot.\n@mnrdnn\"\n");
    if (b.over) { seterr(err, ecap, "Output melebihi buffer"); return -1; }
    return 0;
}

/* ───────────── Deteksi renderer ───────────── */
int tc_build_detect(char *out, size_t cap) {
    int n = snprintf(out, cap, "%s", DETECT);
    return (n < 0 || (size_t)n >= cap) ? -1 : 0;
}

int tc_build_status(char *out, size_t cap) {
    int n = snprintf(out, cap, "%s", STATUS);
    return (n < 0 || (size_t)n >= cap) ? -1 : 0;
}

int tc_build_monitor(char *out, size_t cap) {
    int n = snprintf(out, cap, "%s", MONITOR);
    return (n < 0 || (size_t)n >= cap) ? -1 : 0;
}

unsigned tc_parse_renderers(const char *o) {
    unsigned m = (1u << TC_R_DEFAULT) | (1u << TC_R_NATIVE);
    const char *line = o ? o : "";
    while (*line) {
        char name[32];
        int v;
        if (!strncmp(line, "RD ", 3) && sscanf(line + 3, "%31[a-z]=%d", name, &v) == 2 && v == 1) {
            if (!strcmp(name, "angle")) m |= 1u << TC_R_ANGLE;
            else if (!strcmp(name, "gamedriver")) m |= 1u << TC_R_GAMEDRV;
            else if (!strcmp(name, "prerelease")) m |= 1u << TC_R_PRERELEASE;
            else if (!strcmp(name, "vulkan")) m |= TC_DETECT_VULKAN;
        }
        line = strchr(line, '\n');
        if (!line) break;
        line++;
    }
    return m;
}


/* ───────────── CLI (dipakai tweak_cli dan halaman Terminal) ───────────── */
#define TC_VERSION "1.9"

static const char HELP[] =
    "tweakcore " TC_VERSION " - perintah:\n"
    "  apply gaming|daily|standard [--modules tweak,overlay,fps,display,wm,renderer]\n"
    "        [--fps N] [--scale X] [--games a.b,c.d] [--renderer a.b=angle,c.d=native]\n"
    "        [--size WxH] [--dpi N] [--angle] [--dry]\n"
    "  revert [modul,modul] [--dry]   kembalikan dari backup (kosong = semua)\n"
    "  detect [--dry]                 deteksi renderer, GPU, versi GL/Vulkan\n"
    "  status [--dry]                 profil aktif + modul aktif\n"
    "  monitor [--dry]                script pemantau FPS\n"
    "  help | version\n"
    "renderer: default native angle gamedriver prerelease\n"
    "--dry hanya mencetak script. Awali dengan 'sh ' untuk perintah shell biasa.\n";

static int parse_modules(const char *csv, unsigned *m) {
    char tok[32];
    unsigned r = 0;
    const char *p = csv;
    while (*p) {
        size_t n = 0;
        while (*p == ',') p++;
        if (!*p) break;
        while (*p && *p != ',') { if (n + 1 < sizeof tok) tok[n] = *p; n++; p++; }
        if (n >= sizeof tok) return -1;
        tok[n] = 0;
        n = tc_module_from_name(tok);
        if (!n) return -1;
        r |= (unsigned)n;
    }
    if (!r) return -1;
    *m = r;
    return 0;
}

int tc_cli(int argc, char **argv, char *out, size_t cap, char *err, size_t ecap, int *kind) {
    TcConfig c;
    int i, first = 1, dry = 0, rc;
    unsigned rmod = 0;
    enum { APPLY, REVERT, DETECT, STATUS_C, MONITOR_C } cmd;
    *kind = 0;
    if (!out || cap < 4096) { seterr(err, ecap, "Buffer terlalu kecil"); return -1; }
    out[0] = 0;
    if (argc < 1 || !strcmp(argv[0], "help") || !strcmp(argv[0], "--help") || !strcmp(argv[0], "-h")) {
        snprintf(out, cap, "%s", HELP); *kind = 1; return 0;
    }
    if (!strcmp(argv[0], "version")) { snprintf(out, cap, "tweakcore " TC_VERSION "\n"); *kind = 1; return 0; }
    tc_defaults(&c);
    if (!strcmp(argv[0], "apply")) {
        cmd = APPLY;
        if (argc < 2) { seterr(err, ecap, "apply butuh gaming|daily|standard"); return -1; }
        if (!strcmp(argv[1], "gaming")) c.profile = TC_PROFILE_GAMING;
        else if (!strcmp(argv[1], "daily")) c.profile = TC_PROFILE_DAILY;
        else if (!strcmp(argv[1], "standard")) c.profile = TC_PROFILE_STANDARD;
        else { seterr(err, ecap, "profil: gaming | daily | standard"); return -1; }
        first = 2;
    } else if (!strcmp(argv[0], "revert")) {
        cmd = REVERT;
        if (argc >= 2 && strncmp(argv[1], "--", 2)) {
            if (parse_modules(argv[1], &rmod) != 0) { seterr(err, ecap, "modul tidak dikenal"); return -1; }
            first = 2;
        }
    } else if (!strcmp(argv[0], "detect")) cmd = DETECT;
    else if (!strcmp(argv[0], "status")) cmd = STATUS_C;
    else if (!strcmp(argv[0], "monitor")) cmd = MONITOR_C;
    else { seterr(err, ecap, "perintah tidak dikenal (ketik help)"); return -1; }

    for (i = first; i < argc; i++) {
        const char *a = argv[i];
        int has = i + 1 < argc;
        if (!strcmp(a, "--dry")) dry = 1;
        else if (cmd != APPLY) { seterr(err, ecap, "opsi tidak dikenal"); return -1; }
        else if (!strcmp(a, "--angle")) c.use_angle = 1;
        else if (!has) { seterr(err, ecap, "opsi butuh nilai"); return -1; }
        else if (!strcmp(a, "--fps")) c.fps = atoi(argv[++i]);
        else if (!strcmp(a, "--scale")) c.scale = atof(argv[++i]);
        else if (!strcmp(a, "--games")) c.games = argv[++i];
        else if (!strcmp(a, "--renderer")) c.renderers = argv[++i];
        else if (!strcmp(a, "--dpi")) c.density = atoi(argv[++i]);
        else if (!strcmp(a, "--modules")) {
            if (parse_modules(argv[++i], &c.modules) != 0) { seterr(err, ecap, "modul tidak dikenal"); return -1; }
        } else if (!strcmp(a, "--size")) {
            if (sscanf(argv[++i], "%dx%d", &c.wm_w, &c.wm_h) != 2) { seterr(err, ecap, "format --size WxH"); return -1; }
        } else { seterr(err, ecap, "opsi tidak dikenal"); return -1; }
    }
    switch (cmd) {
    case APPLY:     rc = tc_build_apply(&c, out, cap, err, ecap); break;
    case REVERT:    rc = tc_build_revert(rmod, out, cap); if (rc) seterr(err, ecap, "Output melebihi buffer"); break;
    case DETECT:    rc = tc_build_detect(out, cap); break;
    case STATUS_C:  rc = tc_build_status(out, cap); break;
    default:        rc = tc_build_monitor(out, cap); break;
    }
    if (rc) return -1;
    *kind = dry ? 1 : 0;
    return 0;
}

int tc_cli_line(const char *line, char *out, size_t cap, char *err, size_t ecap, int *kind) {
    char buf[4096], *argv[64], *p, *w, q;
    int argc = 0;
    if (!line || strlen(line) >= sizeof buf) { seterr(err, ecap, "Perintah terlalu panjang"); return -1; }
    strcpy(buf, line);
    p = buf;
    while (*p) {
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
        if (!*p) break;
        if (argc >= 63) { seterr(err, ecap, "Terlalu banyak argumen"); return -1; }
        argv[argc++] = w = p;
        q = 0;
        while (*p) {
            if (q) { if (*p == q) { q = 0; p++; } else *w++ = *p++; }
            else if (*p == '"' || *p == '\'') q = *p++;
            else if (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') { p++; break; }
            else *w++ = *p++;
        }
        *w = 0;
    }
    return tc_cli(argc, argv, out, cap, err, ecap, kind);
}
