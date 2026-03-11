#!/usr/bin/env bash
# Provision a remote Ubuntu machine as an Android eval worker.
# Run on the remote machine: bash provision.sh
set -euo pipefail

ANDROID_SDK_ROOT="${HOME}/android-sdk"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
PINNED_EMULATOR_ZIP="emulator-linux_x64-10696886.zip"
PINNED_EMULATOR_VERSION="32.1.15"
API_LEVEL="33"
BUILD_TOOLS="33.0.0"
SYSTEM_IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
PLATFORM="platforms;android-${API_LEVEL}"
AVD_NAME="AndroidWorldAvd"
AVD_NAME_2="AndroidWorldAvd2"
AVD_DEVICE="pixel_6"

log() { echo "[provision] $*"; }

# ─── 1. System packages ──────────────────────────────────────────────
log "Installing system packages..."
sudo apt-get update -qq
sudo apt-get install -y -qq \
  curl wget unzip software-properties-common autossh tmux \
  libdrm2 libxkbcommon0 libgbm1 libasound2 libnss3 libxcursor1 \
  libpulse0 libxshmfence1 libdbus-glib-1-2 libgl1-mesa-glx \
  libxcb-xinerama0 libxrender1 xvfb

# ─── 2. JDK 17 ───────────────────────────────────────────────────────
if java -version 2>&1 | grep -q 'version "17'; then
  log "JDK 17 already installed."
else
  log "Installing JDK 17..."
  sudo add-apt-repository -y ppa:openjdk-r/ppa
  sudo apt-get update -qq
  sudo apt-get install -y -qq openjdk-17-jdk
  sudo update-alternatives --set java /usr/lib/jvm/java-17-openjdk-amd64/bin/java 2>/dev/null || true
  sudo update-alternatives --set javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac 2>/dev/null || true
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
log "Java: $(java -version 2>&1 | head -1)"

# ─── 3. Python 3.11 ──────────────────────────────────────────────────
if python3.11 --version 2>/dev/null; then
  log "Python 3.11 already installed."
else
  log "Installing Python 3.11 via deadsnakes PPA..."
  sudo add-apt-repository -y ppa:deadsnakes/ppa
  sudo apt-get update -qq
  sudo apt-get install -y -qq python3.11 python3.11-venv python3.11-dev
fi
log "Python: $(python3.11 --version)"

# ─── 4. KVM access ───────────────────────────────────────────────────
if ! groups | grep -q kvm; then
  log "Adding user to kvm group..."
  sudo addgroup --quiet kvm 2>/dev/null || true
  sudo usermod -aG kvm "$(whoami)"
  # Also fix /dev/kvm permissions for current session
  sudo chmod 666 /dev/kvm
  log "WARNING: kvm group added — may need re-login for full effect."
else
  log "Already in kvm group."
fi
# Ensure /dev/kvm is accessible now
if [[ ! -r /dev/kvm ]] || [[ ! -w /dev/kvm ]]; then
  sudo chmod 666 /dev/kvm
fi

# ─── 5. Android SDK ──────────────────────────────────────────────────
mkdir -p "${ANDROID_SDK_ROOT}"

if [[ ! -f "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
  log "Installing Android command-line tools..."
  cd /tmp
  wget -q "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}" -O cmdline-tools.zip
  unzip -qo cmdline-tools.zip -d cmdline-tools-tmp
  mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
  rm -rf "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  mv cmdline-tools-tmp/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  rm -rf cmdline-tools.zip cmdline-tools-tmp
fi

export ANDROID_SDK_ROOT
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/emulator:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

log "Accepting licenses..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true

log "Installing SDK packages..."
sdkmanager --install \
  "platform-tools" \
  "${PLATFORM}" \
  "build-tools;${BUILD_TOOLS}" \
  "${SYSTEM_IMAGE}"

EMULATOR_BIN="${ANDROID_SDK_ROOT}/emulator/emulator"
current_emulator_version=""
if [[ -x "${EMULATOR_BIN}" ]]; then
  current_emulator_version="$("${EMULATOR_BIN}" -version 2>/dev/null | head -1 || true)"
fi

if [[ "${current_emulator_version}" == *"${PINNED_EMULATOR_VERSION}"* ]]; then
  log "Pinned emulator ${PINNED_EMULATOR_VERSION} already installed."
else
  log "Installing pinned emulator ${PINNED_EMULATOR_VERSION} for Ubuntu 18.04 compatibility..."
  cd /tmp
  wget -q "https://dl.google.com/android/repository/${PINNED_EMULATOR_ZIP}" -O emulator.zip
  rm -rf emulator-tmp
  unzip -qo emulator.zip -d emulator-tmp
  rm -rf "${ANDROID_SDK_ROOT}/emulator"
  mv emulator-tmp/emulator "${ANDROID_SDK_ROOT}/emulator"
  rm -rf emulator.zip emulator-tmp
fi

log "adb: $(adb version | head -1)"
log "emulator: $(emulator -version 2>&1 | head -1)"

# ─── 6. Create AVDs ──────────────────────────────────────────────────
for avd in "${AVD_NAME}" "${AVD_NAME_2}"; do
  if emulator -list-avds | grep -Fxq "${avd}"; then
    log "AVD ${avd} already exists."
  else
    log "Creating AVD: ${avd}..."
    echo "no" | avdmanager create avd \
      --force \
      --name "${avd}" \
      --device "${AVD_DEVICE}" \
      --package "${SYSTEM_IMAGE}"
    log "AVD ${avd} created."
  fi
done

# ─── 7. Write environment profile ────────────────────────────────────
PROFILE_FILE="${HOME}/.android-agent-env"
cat > "${PROFILE_FILE}" <<ENVEOF
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}
export ANDROID_HOME=${ANDROID_SDK_ROOT}
export PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/emulator:${ANDROID_SDK_ROOT}/platform-tools:\$PATH"
ENVEOF

# Add to .bashrc if not already there
if ! grep -q 'android-agent-env' "${HOME}/.bashrc" 2>/dev/null; then
  echo 'source ~/.android-agent-env' >> "${HOME}/.bashrc"
fi

log "Environment written to ${PROFILE_FILE}"
log "=== Provision complete ==="
log ""
log "Next steps:"
log "  1. Clone the repo"
log "  2. Run: source ~/.android-agent-env"
log "  3. Set up eval venv"
log "  4. Configure .env"
