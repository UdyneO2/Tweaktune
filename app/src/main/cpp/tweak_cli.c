/* CLI: dipakai lewat adb shell / Shizuku rish / root. Ketik "tweak help" untuk daftar perintah. */
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include "tweak_core.h"

static int run_sh(const char *script) {
    FILE *p = popen("sh", "w");
    if (!p) { perror("popen"); return 1; }
    fputs(script, p);
    return pclose(p) == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    char err[256] = "", *buf = malloc(512 * 1024);
    int kind = 0, rc;
    if (!buf) return 1;
    rc = tc_cli(argc - 1, argv + 1, buf, 512 * 1024, err, sizeof err, &kind);
    if (rc) { fprintf(stderr, "Error: %s\n", err); free(buf); return 1; }
    rc = kind ? (fputs(buf, stdout), 0) : run_sh(buf);
    free(buf);
    return rc;
}
