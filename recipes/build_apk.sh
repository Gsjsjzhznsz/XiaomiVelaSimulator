#!/bin/bash
# build_apk.sh — compile vela-apk into a signed APK without gradle.
# Uses android.jar (API 34) + build-tools 34 downloaded to /home/z/dl/android.
set -e
B=/home/z/dl/android/android-14          # build-tools dir
AJ=/home/z/dl/android/android-34/android.jar
SRC=/home/z/my-project/vela-apk
OUT=/home/z/my-project/download
WORK=/tmp/apkbuild

rm -rf $WORK && mkdir -p $WORK/compiled $OUT
mkdir -p $WORK/gen $WORK/classes $WORK/dex

echo "== aapt2 compile =="
$B/aapt2 compile --dir $SRC/res -o $WORK/res.zip

echo "== aapt2 link =="
$B/aapt2 link -o $WORK/base.apk \
  -I $AJ \
  --manifest $SRC/AndroidManifest.xml \
  --java $WORK/gen \
  --auto-add-overlay \
  -A $SRC/assets \
  $WORK/res.zip

echo "== javac =="
LD_LIBRARY_PATH=/home/z/ov/tools-prefix/usr/lib/jvm/java-21-openjdk-amd64/lib:/home/z/ov/tools-prefix/usr/lib/jvm/java-21-openjdk-amd64/lib/server javac --release 8 -nowarn -cp $AJ:/home/z/dl/android/commons-compress.jar -d $WORK/classes \
  $(find $SRC/java -name "*.java") \
  $WORK/gen/com/vela/simulator/R.java 2>/dev/null || \
LD_LIBRARY_PATH=/home/z/ov/tools-prefix/usr/lib/jvm/java-21-openjdk-amd64/lib:/home/z/ov/tools-prefix/usr/lib/jvm/java-21-openjdk-amd64/lib/server javac --release 8 -nowarn -cp $AJ:/home/z/dl/android/commons-compress.jar -d $WORK/classes \
  $(find $SRC/java -name "*.java") \
  $(find $WORK/gen -name "R.java")

echo "== d8 (jar inputs; d8 NPEs on many bare .class files) =="
mkdir -p $WORK/dex1 $WORK/dex2
(cd $WORK/classes && jar cf $WORK/app.jar .)
java -cp /home/z/dl/android/r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $AJ --output $WORK/dex1 $WORK/app.jar
java -cp /home/z/dl/android/r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $AJ --output $WORK/dex2 /home/z/dl/android/commons-compress.jar

echo "== package =="
mv $WORK/dex2/classes.dex $WORK/dex2/classes2.dex
(cd $WORK/dex1 && zip -q ../base.apk classes.dex)
(cd $WORK/dex2 && zip -q ../base.apk classes2.dex)

echo "== zipalign =="
$B/zipalign -f 4 $WORK/base.apk $WORK/aligned.apk

echo "== sign =="
KS=$WORK/debug.keystore
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
$B/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 24 --out $OUT/XiaomiVelaSimulator-v2.0-vapp.apk $WORK/aligned.apk

$B/apksigner verify --print-certs $OUT/XiaomiVelaSimulator-v2.0-vapp.apk | head -3
ls -la $OUT/XiaomiVelaSimulator-v2.0-vapp.apk
echo "APK BUILD OK"
