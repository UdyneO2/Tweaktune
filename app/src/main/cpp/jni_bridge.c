#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "tweak_core.h"

static char g_err[256];
#define OUTCAP (512 * 1024)

JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_buildApply(JNIEnv *env, jclass cls, jint profile, jint modules, jint fps, jdouble scale,
                                       jboolean angle, jint w, jint h, jint dpi, jstring games, jstring renderers) {
    TcConfig c;
    const char *g = "", *rd = "";
    char *buf;
    jstring r = NULL;
    (void)cls;
    tc_defaults(&c);
    if (games) g = (*env)->GetStringUTFChars(env, games, NULL);
    if (renderers) rd = (*env)->GetStringUTFChars(env, renderers, NULL);
    c.profile = (TcProfile)profile; c.modules = (unsigned)modules; c.fps = fps; c.scale = scale;
    c.use_angle = angle ? 1 : 0; c.wm_w = w; c.wm_h = h; c.density = dpi; c.games = g; c.renderers = rd;
    g_err[0] = 0;
    buf = (char *)malloc(OUTCAP);
    if (buf && tc_build_apply(&c, buf, OUTCAP, g_err, sizeof g_err) == 0) r = (*env)->NewStringUTF(env, buf);
    else if (!buf) snprintf(g_err, sizeof g_err, "Memori habis");
    free(buf);
    if (games) (*env)->ReleaseStringUTFChars(env, games, g);
    if (renderers) (*env)->ReleaseStringUTFChars(env, renderers, rd);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_buildRevert(JNIEnv *env, jclass cls, jint modules) {
    char buf[16384];
    (void)cls;
    if (tc_build_revert((unsigned)modules, buf, sizeof buf) != 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_buildDetect(JNIEnv *env, jclass cls) {
    char buf[8192];
    (void)cls;
    if (tc_build_detect(buf, sizeof buf) != 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

/* "S<script>" = jalankan, "T<teks>" = tampilkan, "E<pesan>" = galat */
JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_cli(JNIEnv *env, jclass cls, jstring line) {
    char err[256] = "", *buf = (char *)malloc(OUTCAP), *res;
    const char *l;
    int kind = 0, rc;
    jstring r;
    (void)cls;
    if (!buf) return (*env)->NewStringUTF(env, "EMemori habis");
    l = (*env)->GetStringUTFChars(env, line, NULL);
    rc = tc_cli_line(l, buf, OUTCAP, err, sizeof err, &kind);
    (*env)->ReleaseStringUTFChars(env, line, l);
    res = (char *)malloc(OUTCAP + 300);
    if (!res) { free(buf); return (*env)->NewStringUTF(env, "EMemori habis"); }
    if (rc) snprintf(res, OUTCAP + 300, "E%s", err);
    else snprintf(res, OUTCAP + 300, "%c%s", kind ? 'T' : 'S', buf);
    r = (*env)->NewStringUTF(env, res);
    free(buf); free(res);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_buildStatus(JNIEnv *env, jclass cls) {
    char buf[8192];
    (void)cls;
    if (tc_build_status(buf, sizeof buf) != 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_buildMonitor(JNIEnv *env, jclass cls) {
    char buf[8192];
    (void)cls;
    if (tc_build_monitor(buf, sizeof buf) != 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_tweak_tuner_Native_parseRenderers(JNIEnv *env, jclass cls, jstring out) {
    const char *s;
    unsigned m;
    (void)cls;
    if (!out) return 3;
    s = (*env)->GetStringUTFChars(env, out, NULL);
    m = tc_parse_renderers(s);
    (*env)->ReleaseStringUTFChars(env, out, s);
    return (jint)m;
}

JNIEXPORT jstring JNICALL
Java_com_tweak_tuner_Native_lastError(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, g_err);
}
