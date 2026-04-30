#!/usr/bin/env bash
# Setup script for GCP VM running Ubuntu 26.
# Installs: Docker Engine, Java 25 (Temurin), Maven 3.9.9
#
# Usage:
#   chmod +x scripts/setup-gcp-vm.sh
#   ./scripts/setup-gcp-vm.sh
#
# After the script completes, log out and back in (or run `newgrp docker`)
# so Docker group membership takes effect without sudo.

set -euo pipefail

MAVEN_VERSION="3.9.9"
MAVEN_ARCHIVE="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
ADOPTIUM_KEY="/etc/apt/keyrings/adoptium.gpg"
ADOPTIUM_LIST="/etc/apt/sources.list.d/adoptium.list"

log() { echo ">>> $*"; }

# ─── Base packages ────────────────────────────────────────────────────────────
log "Updating apt and installing base packages..."
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg lsb-release git wget unzip

# ─── Docker Engine ────────────────────────────────────────────────────────────
log "Installing Docker Engine..."
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
sudo systemctl enable --now docker
log "Docker installed: $(docker --version)"
log "Docker Compose installed: $(docker compose version)"

# ─── Java 25 (Eclipse Temurin) ───────────────────────────────────────────────
log "Installing Java 25 (Eclipse Temurin)..."

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | sudo gpg --dearmor -o "$ADOPTIUM_KEY"
sudo chmod a+r "$ADOPTIUM_KEY"

# Adoptium may not yet publish packages for the newest Ubuntu codename.
# Fall back to noble (24.04 LTS) if the current codename isn't available.
CODENAME=$(lsb_release -cs)
if ! curl -fsI "https://packages.adoptium.net/artifactory/deb/dists/${CODENAME}/" \
        > /dev/null 2>&1; then
    log "Adoptium repo has no packages for '${CODENAME}'; using 'noble' (24.04 LTS)..."
    CODENAME="noble"
fi

echo "deb [signed-by=${ADOPTIUM_KEY}] \
https://packages.adoptium.net/artifactory/deb ${CODENAME} main" \
    | sudo tee "$ADOPTIUM_LIST" > /dev/null

sudo apt-get update -qq
sudo apt-get install -y temurin-25-jdk

JAVA_HOME_PATH="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
log "Java installed: $(java -version 2>&1 | head -1) — JAVA_HOME=${JAVA_HOME_PATH}"

# ─── Maven ────────────────────────────────────────────────────────────────────
log "Installing Maven ${MAVEN_VERSION}..."
sudo mkdir -p /opt/maven
curl -fsSL "$MAVEN_ARCHIVE" | sudo tar -xzC /opt/maven --strip-components=1
sudo ln -sf /opt/maven/bin/mvn /usr/local/bin/mvn
log "Maven installed: $(mvn -version | head -1)"

# ─── Shell environment ────────────────────────────────────────────────────────
log "Writing environment variables to ~/.bashrc..."
cat >> ~/.bashrc << EOF

# Java / Maven (added by setup-gcp-vm.sh)
export JAVA_HOME=${JAVA_HOME_PATH}
export M2_HOME=/opt/maven
export PATH=\$PATH:\$M2_HOME/bin
EOF

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "┌─────────────────────────────────────────────────────────────┐"
echo "│                  Installation complete                       │"
echo "├─────────────────────────────────────────────────────────────┤"
printf "│  Java   : %-50s│\n" "$(java -version 2>&1 | awk -F'"' 'NR==1{print $2}')"
printf "│  Maven  : %-50s│\n" "$(mvn -version 2>/dev/null | awk 'NR==1{print $3}')"
printf "│  Docker : %-50s│\n" "$(docker --version | awk '{print $3}' | tr -d ',')"
echo "├─────────────────────────────────────────────────────────────┤"
echo "│  Next steps:                                                 │"
echo "│    newgrp docker          # activate docker group now        │"
echo "│    git clone <repo> && cd <repo>                             │"
echo "│    docker compose -f docker-compose.gcp.yml up -d --build   │"
echo "└─────────────────────────────────────────────────────────────┘"
