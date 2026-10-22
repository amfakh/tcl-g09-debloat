# Shared helpers for the scripts in tools/: which TV, how to reach it, how to read a package list.
# Source it from another script; it does nothing on its own.

# The TV's address. DHCP can move it, so override per run: TV_HOST=192.168.8.20 tools/debloat.sh
TV_HOST="${TV_HOST:-192.168.8.14}"
TV="$TV_HOST:5555"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Connect to the TV over network ADB; exit with a hint when it is unreachable or not authorised.
tv_connect() {
  adb connect "$TV" >/dev/null 2>&1 || true
  local state
  state="$(adb -s "$TV" get-state 2>/dev/null || true)"
  if [ "$state" != "device" ]; then
    echo "TV $TV is not reachable or not authorised (state: ${state:-none})." >&2
    echo "Turn USB debugging on (Settings > System > Developer options) and accept the" >&2
    echo "\"Allow USB debugging?\" prompt on the TV, or set TV_HOST if its address changed." >&2
    exit 1
  fi
}

# Run one shell command on the TV and print its output without adb's carriage returns.
# -n keeps adb off this script's stdin.
tv() {
  adb -s "$TV" shell -n "$@" | tr -d '\r'
}

# Build and sign a debug APK straight from the SDK tools (no Gradle) and print its path.
# The project dir holds AndroidManifest.xml, src/ and an optional res/; output goes to <dir>/build/.
# Needs JDK 17 and the Android SDK (platforms;android-34, build-tools;34.0.0).
build_apk() {
  # Called as $(build_apk ...), the function runs in a subshell that does not inherit errexit on
  # macOS bash 3.2, so a failed step would still print an APK path.
  set -e
  local src="$1" name="$2"
  local out="$src/build"
  local sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
  local bt="$sdk/build-tools/34.0.0"
  local jar="$sdk/platforms/android-34/android.jar"
  local jdk="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
  # Tool chatter goes to stderr: stdout carries only the APK path for the caller.
  {
  # Stale classes from a renamed source file would otherwise ride along into the dex.
  mkdir -p "$out"
  find "$out" -mindepth 1 -delete
  mkdir -p "$out/classes" "$out/gen"
  local res=()
  if [ -d "$src/res" ]; then
    "$bt/aapt2" compile --dir "$src/res" -o "$out/res.zip"
    res=("$out/res.zip" --java "$out/gen")
  fi
  "$bt/aapt2" link --manifest "$src/AndroidManifest.xml" -I "$jar" ${res[@]+"${res[@]}"} -o "$out/unsigned.apk"
  "$jdk/bin/javac" --release 11 -classpath "$jar" -d "$out/classes" $(find "$src/src" "$out/gen" -name '*.java')
  JAVA_HOME="$jdk" "$bt/d8" --min-api 26 --lib "$jar" --output "$out" $(find "$out/classes" -name '*.class')
  (cd "$out" && zip -q -j unsigned.apk classes.dex)
  "$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
  local ks="$HOME/.android/debug.keystore"
  [ -f "$ks" ] || "$jdk/bin/keytool" -genkeypair -keystore "$ks" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
  JAVA_HOME="$jdk" "$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android \
    --out "$out/$name.apk" "$out/aligned.apk"
  } >&2
  echo "$out/$name.apk"
}

# Print the package names in a list file, one per line, dropping comments and blank lines.
# Every name ends up in a shell command on the TV, so refuse the whole list if any entry is not a
# bare package name.
read_list() {
  local pkgs bad
  pkgs="$(sed -e 's/#.*//' -e 's/\r$//' "$1" | awk 'NF {print $1}')"
  bad="$(printf '%s\n' "$pkgs" | grep -vE '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' || true)"
  if [ -n "$bad" ]; then
    echo "refusing $1, not a package name:" >&2
    printf '  %s\n' $bad >&2
    return 1
  fi
  printf '%s\n' "$pkgs"
}
