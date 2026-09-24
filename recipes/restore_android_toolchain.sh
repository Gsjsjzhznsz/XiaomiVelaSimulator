#!/bin/bash
# restore_android_toolchain.sh — rebuild /home/z/dl/android + JDK after sandbox reset.
# Idempotent & resumable: run repeatedly until "TOOLCHAIN OK".
set -u
DL=/home/z/dl/android
TP=/home/z/ov/tools-prefix
JVM=$TP/usr/lib/jvm/java-21-openjdk-amd64
mkdir -p $DL $TP/usr /tmp/jdk-deb
step() { echo "== $1 =="; }

# ---- 1. JDK via apt download ----
if [ ! -x $JVM/bin/javac ]; then
  step "JDK debs"
  for p in openjdk-21-jdk-headless openjdk-21-jre-headless libjpeg62-turbo; do
    if ! ls /tmp/jdk-deb/${p}_*.deb >/dev/null 2>&1; then
      (cd /tmp/jdk-deb && apt-get download $p 2>&1 | tail -1)
    fi
  done
  for f in /tmp/jdk-deb/*.deb; do [ -f "$f" ] && dpkg -x "$f" $TP; done
  [ -x $JVM/bin/javac ] && echo "JDK restored" || echo "JDK INCOMPLETE (rerun)"
else
  echo "JDK ok"
fi
export PATH=$JVM/bin:$PATH

# ---- 2. build-tools (aapt2, zipalign, apksigner) ----
if [ ! -x $DL/android-14/aapt2 ]; then
  step "build-tools r34"
  if [ ! -f /tmp/bt34.zip ]; then
    curl -s -L -m 500 -o /tmp/bt34.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip \
      || curl -s -L -m 500 -o /tmp/bt34.zip https://dl.google.com/android/repository/build-tools_r34-rc2-linux.zip
  fi
  if [ -s /tmp/bt34.zip ]; then
    rm -rf /tmp/bt && mkdir /tmp/bt && unzip -q /tmp/bt34.zip -d /tmp/bt
    # zip contains android-14/ (or similar) subdir
    inner=$(find /tmp/bt -maxdepth 1 -type d ! -path /tmp/bt | head -1)
    rm -rf $DL/android-14 && mv "$inner" $DL/android-14
    echo "build-tools -> $DL/android-14"
  else
    echo "BT DOWNLOAD FAIL"
  fi
else
  echo "build-tools ok"
fi

# ---- 3. platform android.jar ----
if [ ! -f $DL/android-34/android.jar ]; then
  step "platform-34"
  if [ ! -f /tmp/p34.zip ]; then
    curl -s -L -m 500 -o /tmp/p34.zip https://dl.google.com/android/repository/platform-34_r02.zip \
      || curl -s -L -m 500 -o /tmp/p34.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
  fi
  if [ -s /tmp/p34.zip ]; then
    rm -rf /tmp/p34 && mkdir /tmp/p34 && unzip -q /tmp/p34.zip -d /tmp/p34
    jar=$(find /tmp/p34 -name android.jar | grep -E "android-34/" | head -1)
    [ -z "$jar" ] && jar=$(find /tmp/p34 -name android.jar | head -1)
    mkdir -p $DL/android-34 && cp "$jar" $DL/android-34/android.jar
    echo "android.jar -> $DL/android-34/"
  else
    echo "PLATFORM DOWNLOAD FAIL"
  fi
else
  echo "android.jar ok"
fi

# ---- 4. r8 (d8) ----
if [ ! -f $DL/r8.jar ]; then
  step "r8 8.3.37"
  curl -s -L -m 500 -o $DL/r8.jar https://dl.google.com/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar
  [ -s $DL/r8.jar ] && echo "r8 ok" || echo "R8 FAIL"
else
  echo "r8 ok"
fi

# ---- 5. commons-compress + xz ----
if [ ! -f $DL/commons-compress.jar ] || [ ! -f $DL/xz.jar ]; then
  step "commons-compress + xz"
  curl -s -L -m 300 -o $DL/commons-compress.jar https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar
  curl -s -L -m 300 -o $DL/xz.jar https://repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar
  echo "compress jars fetched"
else
  echo "compress jars ok"
fi

# ---- verify ----
step "verify"
ok=1
for f in $JVM/bin/javac $DL/android-14/aapt2 $DL/android-14/zipalign $DL/android-14/apksigner $DL/android-34/android.jar $DL/r8.jar $DL/commons-compress.jar $DL/xz.jar; do
  if [ -e "$f" ]; then echo "  OK   $f"; else echo "  MISS $f"; ok=0; fi
done
[ $ok = 1 ] && echo "TOOLCHAIN OK" || echo "TOOLCHAIN INCOMPLETE (rerun this script)"
