#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
jdk=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fPIC -shared \
  -I"$jdk/include" -I"$jdk/include/linux" \
  "$repo/app/src/main/cpp/ovmsink_compat.c" -o "$out/libovmsink.so"
"$jdk/bin/javac" -d "$out" "$repo"/app/src/main/java/com/miauto/app/wfd/*.java \
  "$repo/tests/native/ovmsink/SinkNative.java"
"$jdk/bin/java" -Djava.library.path="$out" -cp "$out" com.ucarsink.sink.natives.SinkNative
