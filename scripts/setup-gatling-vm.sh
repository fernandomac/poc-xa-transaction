#!/usr/bin/env bash
# Setup script for the dedicated Gatling load-generator VM (Ubuntu 26).
# Installs Java 25 (Temurin) and Maven 3.9.9 only — no Docker needed.
#
# Usage:
#   chmod +x scripts/setup-gatling-vm.sh
#   ./scripts/setup-gatling-vm.sh

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
    ca-certificates curl gnupg lsb-release git wget

# ─── Java 25 (Eclipse Temurin) ───────────────────────────────────────────────
log "Installing Java 25 (Eclipse Temurin)..."

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | sudo gpg --dearmor -o "$ADOPTIUM_KEY"
sudo chmod a+r "$ADOPTIUM_KEY"

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
log "Java installed: $(java -version 2>&1 | head -1)"

# ─── Maven ────────────────────────────────────────────────────────────────────
log "Installing Maven ${MAVEN_VERSION}..."
sudo mkdir -p /opt/maven
curl -fsSL "$MAVEN_ARCHIVE" | sudo tar -xzC /opt/maven --strip-components=1
sudo ln -sf /opt/maven/bin/mvn /usr/local/bin/mvn
log "Maven installed: $(mvn -version | head -1)"

# ─── Shell environment ────────────────────────────────────────────────────────
log "Writing environment variables to ~/.bashrc..."
cat >> ~/.bashrc << EOF

# Java / Maven (added by setup-gatling-vm.sh)
export JAVA_HOME=${JAVA_HOME_PATH}
export M2_HOME=/opt/maven
export PATH=\$PATH:\$M2_HOME/bin
EOF

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "┌─────────────────────────────────────────────────────────────┐"
echo "│              Gatling VM setup complete                       │"
echo "├─────────────────────────────────────────────────────────────┤"
printf "│  Java   : %-50s│\n" "$(java -version 2>&1 | awk -F'"' 'NR==1{print $2}')"
printf "│  Maven  : %-50s│\n" "$(mvn -version 2>/dev/null | awk 'NR==1{print $3}')"
echo "├─────────────────────────────────────────────────────────────┤"
echo "│  Next steps:                                                 │"
echo "│    git clone <repo> && cd <repo>                             │"
echo "│    # Get app VM internal IP from GCP console                 │"
echo "│    mvn gatling:test -f load-tests/pom.xml \\                  │"
echo "│      -Dgatling.baseUrl=http://<APP-VM-INTERNAL-IP>:8080 \\   │"
echo "│      -Dgatling.peakRps=1000                                  │"
echo "└─────────────────────────────────────────────────────────────┘"
