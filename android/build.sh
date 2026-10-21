#!/bin/sh
# Builds a signed debug APK without Gradle: aapt2 + javac + d8 + apksigner.
set -eu
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build
KS=${HOMECTL_KEYSTORE:-$HOME/.config/homectl/debug.keystore}
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/classes
$BT/aapt2 compile --dir app/src/main/res -o $OUT/compiled
$BT/aapt2 link -o $OUT/base.apk -I "$JAR" --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 0.1 $OUT/compiled/*.flat
javac --release 17 -XDstringConcat=inline -cp "$JAR" -d $OUT/classes $(find app/src/main/java -name '*.java')
$BT/d8 --release --lib "$JAR" --min-api 26 --output "$PWD/$OUT" $(find $OUT/classes -name '*.class')
(cd $OUT && zip -q -u base.apk classes.dex)
$BT/zipalign -f 4 $OUT/base.apk $OUT/aligned.apk
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias homectl \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=homectl" >/dev/null 2>&1
fi
$BT/apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out $OUT/homectl.apk $OUT/aligned.apk
echo "built $OUT/homectl.apk"
