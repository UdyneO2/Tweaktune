package com.tweak.tuner;

interface IShellService {
    void destroy() = 16777114;          // wajib untuk Shizuku UserService
    String exec(String script) = 2;     // jalankan script via stdin `sh` (uid shell)
}
