SRC=app/src/main/cpp
test:
	gcc -Wall -Wextra -std=c11 -o /tmp/tt_test $(SRC)/tweak_core.c tests/test_core.c && /tmp/tt_test
	for f in status monitor gaming renderer overlay daily standard revert_wm revert detect; do sh -n /tmp/tt_$$f.sh && echo "sh -n $$f ok"; done
# CLI untuk Android (ubah NDK & API sesuai kebutuhan):
#   make cli NDK=$$HOME/Android/Sdk/ndk/27.0.12077973
cli:
	$(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang -O2 \
	  -o tweak $(SRC)/tweak_cli.c $(SRC)/tweak_core.c
