#ifndef TWEAK_CORE_H
#define TWEAK_CORE_H
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Mode: gaming = preset game, daily = preset harian, standard = kembali ke bawaan sistem (reset semua) */
typedef enum { TC_PROFILE_GAMING = 1, TC_PROFILE_DAILY = 2, TC_PROFILE_STANDARD = 3 } TcProfile;

/* Modul: masing-masing punya script + backup sendiri, jadi bisa dijalankan / di-reset terpisah */
enum {
    TC_M_TWEAK    = 1,   /* tweak device_config sesuai mode            */
    TC_M_OVERLAY  = 2,   /* game_overlay (fps + skala) + mode performa */
    TC_M_FPS      = 4,   /* kunci refresh rate / frame rate override   */
    TC_M_DISPLAY  = 8,   /* flag display_manager (refresh tidak turun) */
    TC_M_WM       = 16,  /* wm size + wm density                       */
    TC_M_RENDERER = 32,  /* renderer per game (ANGLE / native / driver)*/
    TC_M_ALL      = 63
};

/* Renderer per game */
enum { TC_R_DEFAULT = 0, TC_R_NATIVE = 1, TC_R_ANGLE = 2, TC_R_GAMEDRV = 3, TC_R_PRERELEASE = 4, TC_R_COUNT = 5 };
#define TC_DETECT_VULKAN (1u << 8)   /* info saja: GPU mendukung Vulkan */

typedef struct {
    TcProfile profile;
    unsigned modules;   /* 0 = bawaan sesuai mode (tc_default_modules)           */
    int fps;            /* 30..240                                               */
    double scale;       /* 0.30..1.00 downscaleFactor                            */
    int use_angle;      /* game terpilih tanpa entri renderer -> ANGLE (CLI saja) */
    int wm_w, wm_h;     /* 0 = jangan ubah resolusi layar                        */
    int density;        /* 0 = jangan ubah DPI                                   */
    const char *games;     /* "pkg1,pkg2,..."                                    */
    const char *renderers; /* "pkg=angle,pkg2=native,..." (default|native|angle|gamedriver|prerelease) */
} TcConfig;

void tc_defaults(TcConfig *c);
unsigned tc_default_modules(TcProfile p);
/* 0 = valid, -1 = tidak valid (pesan di err) */
int tc_validate(const TcConfig *c, char *err, size_t errcap);
/* Script shell apply untuk modul terpilih. Mode standard = script reset. 0 = ok */
int tc_build_apply(const TcConfig *c, char *out, size_t cap, char *err, size_t errcap);
/* Script reset dari backup. modules 0 = semua (termasuk backup lama v1.5). 0 = ok */
int tc_build_revert(unsigned modules, char *out, size_t cap);
/* Script deteksi renderer; keluarannya diparse tc_parse_renderers */
int tc_build_detect(char *out, size_t cap);
/* Script status: "PROFILE gaming|daily|standard" + "MOD <modul>=0|1" (modul yang punya backup = aktif) */
int tc_build_status(char *out, size_t cap);
/* Script pemantau FPS (latar belakang): tulis "paket|API|fps|GPU" tiap detik ke /data/local/tmp/tt_fps.out */
int tc_build_monitor(char *out, size_t cap);
/* Bit (1<<TC_R_x) = tersedia (DEFAULT dan NATIVE selalu ada), TC_DETECT_VULKAN = info */
unsigned tc_parse_renderers(const char *detect_output);

/* CLI: "apply gaming --fps 120 ...", "revert wm", "detect", "status", "help". kind: 0 = script shell (jalankan), 1 = teks (cetak). 0 = ok */
int tc_cli(int argc, char **argv, char *out, size_t cap, char *err, size_t ecap, int *kind);
int tc_cli_line(const char *line, char *out, size_t cap, char *err, size_t ecap, int *kind);

const char *tc_renderer_name(int r);       /* "default", "native", ... */
int tc_renderer_from_name(const char *s);  /* -1 jika tidak dikenal    */
unsigned tc_module_from_name(const char *s); /* 0 jika tidak dikenal   */

#ifdef __cplusplus
}
#endif
#endif
