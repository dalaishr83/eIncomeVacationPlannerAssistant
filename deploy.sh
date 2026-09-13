#!/usr/bin/env bash
# ==============================================================================
# Production Deployment Script for Oracle Cloud Infrastructure (OCI)
# Application: Vacation Planner Assistant / Holiday Leave Assistant (Spring Boot)
# Platform: Oracle Linux 8/9 / RHEL / Ubuntu on OCI Compute
# ==============================================================================

set -euo pipefail

# ------------------------------------------------------------------------------
# Configuration Defaults
# ------------------------------------------------------------------------------

APP_NAME="holiday-leave-assistant"
APP_USER="appuser"
APP_GROUP="appuser"

INSTALL_DIR="/opt/${APP_NAME}"
DATA_DIR="/var/lib/${APP_NAME}/data"
LOG_DIR="/var/log/${APP_NAME}"
CONFIG_DIR="/etc/${APP_NAME}"

SYSTEMD_SERVICE="/etc/systemd/system/${APP_NAME}.service"

# Spring Boot application port
APP_PORT="${APP_PORT:-8080}"

# Oracle Linux 9 / RHEL Java 8 package
JAVA_PACKAGE="java-1.8.0-openjdk"

# ------------------------------------------------------------------------------
# Logging Colours
# ------------------------------------------------------------------------------

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $(date '+%Y-%m-%d %H:%M:%S') - $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $(date '+%Y-%m-%d %H:%M:%S') - $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') - $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') - $1" >&2
}

# ------------------------------------------------------------------------------
# 1. Root Check
# ------------------------------------------------------------------------------

if [[ $EUID -ne 0 ]]; then
    log_error "This script must be run as root or with sudo privileges."
    exit 1
fi

log_info "Starting production deployment for ${APP_NAME} on Oracle Cloud Infrastructure..."

# ------------------------------------------------------------------------------
# 2. Package Manager & Dependencies
# ------------------------------------------------------------------------------
#
# IMPORTANT OCI / Oracle Linux 9:
#
# The default DNF configuration contains several enabled repositories:
#   - ol9_UEKR8
#   - ol9_addons
#   - ol9_appstream
#   - ol9_baseos_latest
#   - ol9_ksplice
#   - ol9_oci_included
#
# Dependency resolution across all enabled repositories was found to hang
# on this OCI instance.
#
# The required packages were successfully installed using only:
#   - ol9_baseos_latest
#   - ol9_appstream
#
# Therefore deployment intentionally restricts this transaction to these
# repositories. Do NOT remove or globally disable any OCI repositories.
# ------------------------------------------------------------------------------

log_info "Checking system dependencies and OpenJDK 8 runtime..."

if command -v dnf &>/dev/null; then

    log_info "Installing dependencies using Oracle Linux BaseOS + AppStream repositories..."

    dnf --disablerepo='*' \
        --enablerepo=ol9_baseos_latest \
        --enablerepo=ol9_appstream \
        install -y \
        "${JAVA_PACKAGE}-headless" \
        curl \
        tar \
        jq \
        rsync \
        firewalld

elif command -v yum &>/dev/null; then

    log_info "Installing dependencies using YUM..."

    yum install -y \
        "${JAVA_PACKAGE}-headless" \
        curl \
        tar \
        jq \
        rsync \
        firewalld

elif command -v apt-get &>/dev/null; then

    log_info "Installing dependencies using APT..."

    apt-get update -qq

    apt-get install -y -qq \
        openjdk-8-jre-headless \
        curl \
        tar \
        jq \
        rsync \
        ufw

else

    log_warn "Unknown package manager."
    log_warn "Ensure Java 8+, curl, tar, jq and rsync are installed manually."

fi

# ------------------------------------------------------------------------------
# 3. Verify Java Runtime
# ------------------------------------------------------------------------------

if command -v java &>/dev/null; then
    log_info "Detected Java runtime:"
    java -version 2>&1 | head -n 1
else
    log_error "Java runtime was not found after dependency installation."
    exit 1
fi

# ------------------------------------------------------------------------------
# 4. Create Dedicated Service User
# ------------------------------------------------------------------------------

if ! id -u "${APP_USER}" &>/dev/null; then
    log_info "Creating system user and group: ${APP_USER}"

    useradd \
        --system \
        --no-create-home \
        --user-group \
        --shell /sbin/nologin \
        "${APP_USER}"
else
    log_info "Service user ${APP_USER} already exists."
fi

# ------------------------------------------------------------------------------
# 5. Directory Structure
# ------------------------------------------------------------------------------

log_info "Setting up runtime directory structure..."

mkdir -p "${INSTALL_DIR}"

mkdir -p \
    "${DATA_DIR}/cron-expression" \
    "${DATA_DIR}/staging" \
    "${DATA_DIR}/temp" \
    "${DATA_DIR}/working" \
    "${DATA_DIR}/restrictedVacationType" \
    "${DATA_DIR}/reports"

mkdir -p "${LOG_DIR}"
mkdir -p "${CONFIG_DIR}"

# ------------------------------------------------------------------------------
# 6. Resolve Deployment Script Directory
# ------------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log_info "Deployment source directory: ${SCRIPT_DIR}"

# ------------------------------------------------------------------------------
# 7. Locate Existing JAR
# ------------------------------------------------------------------------------

JAR_SOURCE=""

# Preferred known application artifact
if [[ -f "${SCRIPT_DIR}/backend/target/${APP_NAME}-1.0.0.jar" ]]; then

    JAR_SOURCE="${SCRIPT_DIR}/backend/target/${APP_NAME}-1.0.0.jar"

elif [[ -f "${SCRIPT_DIR}/${APP_NAME}-1.0.0.jar" ]]; then

    JAR_SOURCE="${SCRIPT_DIR}/${APP_NAME}-1.0.0.jar"

# Any JAR under backend/target
elif compgen -G "${SCRIPT_DIR}/backend/target/*.jar" > /dev/null; then

    JAR_SOURCE="$(
        find "${SCRIPT_DIR}/backend/target" \
            -maxdepth 1 \
            -type f \
            -name '*.jar' \
            ! -name '*original*.jar' \
            -printf '%T@ %p\n' |
        sort -nr |
        awk '{$1=""; sub(/^ /,""); print; exit}'
    )"

# Any JAR in repository root
elif compgen -G "${SCRIPT_DIR}/*.jar" > /dev/null; then

    JAR_SOURCE="$(
        find "${SCRIPT_DIR}" \
            -maxdepth 1 \
            -type f \
            -name '*.jar' \
            ! -name '*original*.jar' \
            -printf '%T@ %p\n' |
        sort -nr |
        awk '{$1=""; sub(/^ /,""); print; exit}'
    )"

fi

# ------------------------------------------------------------------------------
# 8. Build JAR If Required
# ------------------------------------------------------------------------------

if [[ -z "${JAR_SOURCE}" || ! -f "${JAR_SOURCE}" ]]; then

    log_warn "Pre-built JAR was not found."

    # Resolve a Maven executable: prefer system mvn, fall back to the wrapper
    MVN_CMD=""
    if command -v mvn &>/dev/null; then
        MVN_CMD="mvn"
    elif [[ -x "${SCRIPT_DIR}/backend/mvnw" ]]; then
        MVN_CMD="${SCRIPT_DIR}/backend/mvnw"
        log_info "System Maven not found - using bundled Maven wrapper (mvnw)."
    fi

    if [[ -n "${MVN_CMD}" ]]; then

        if [[ -d "${SCRIPT_DIR}/backend" ]]; then

            log_info "Building Spring Boot application with: ${MVN_CMD}"

            (
                cd "${SCRIPT_DIR}/backend"
                "${MVN_CMD}" clean package -DskipTests
            )

            JAR_SOURCE="$(
                find "${SCRIPT_DIR}/backend/target" \
                    -maxdepth 1 \
                    -type f \
                    -name '*.jar' \
                    ! -name '*original*.jar' \
                    -printf '%T@ %p\n' |
                sort -nr |
                awk '{$1=""; sub(/^ /,""); print; exit}'
            )"

        else

            log_error "backend directory not found."
            exit 1

        fi

    else

        log_error "Maven is not installed and no JAR file was found."
        log_error "Build the application first and place the JAR in backend/target/."
        exit 1

    fi

fi

if [[ -z "${JAR_SOURCE}" || ! -f "${JAR_SOURCE}" ]]; then
    log_error "Unable to resolve a deployable JAR file."
    exit 1
fi

log_info "Deploying application binary:"
log_info "  Source: ${JAR_SOURCE}"
log_info "  Target: ${INSTALL_DIR}/app.jar"

cp -f "${JAR_SOURCE}" "${INSTALL_DIR}/app.jar"

# ------------------------------------------------------------------------------
# 9. Initialise Baseline Data & Configuration
# ------------------------------------------------------------------------------

if [[ -d "${SCRIPT_DIR}/data" ]]; then

    log_info "Syncing base configuration/data templates to ${DATA_DIR}..."

    rsync -a \
        --ignore-existing \
        "${SCRIPT_DIR}/data/" \
        "${DATA_DIR}/" || true

fi

# ------------------------------------------------------------------------------
# 10. Environment & Secrets Configuration
# ------------------------------------------------------------------------------

ENV_FILE="${CONFIG_DIR}/${APP_NAME}.env"

if [[ ! -f "${ENV_FILE}" ]]; then

    log_info "Generating default environment file at ${ENV_FILE}..."

    cat <<EOF > "${ENV_FILE}"
# ------------------------------------------------------------------------------
# Production Environment Variables
# Application: Vacation Planner Assistant
# ------------------------------------------------------------------------------

# Spring Boot
SERVER_PORT=8080

# Application data
DATA_DIR=${DATA_DIR}
LOG_LEVEL=INFO
REPORT_OUTPUT_DIR=${DATA_DIR}/reports

# Scheduler
CRON_TIMEZONE=Asia/Kolkata
CRON_VALIDATION_INTERVAL_SECONDS=3600

# Session
PERMANENT_SESSION_LIFETIME=3600

# ------------------------------------------------------------------------------
# Authentication
# ------------------------------------------------------------------------------

LOGIN_USERNAME=admin
LOGIN_PASSWORD_HASH=

# ------------------------------------------------------------------------------
# LLM Integration
# ------------------------------------------------------------------------------

OPENAI_API_KEY=
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=llama3.2
LLM_TEMPERATURE=0.0
LLM_MAX_TOKENS=1024
WATSONX_PROJECT_ID=

# ------------------------------------------------------------------------------
# IBM Box Cloud Sync
# ------------------------------------------------------------------------------

BOX_ENABLED=false
BOX_CLIENT_ID=
BOX_CLIENT_SECRET=
BOX_ENTERPRISE_ID=
BOX_FOLDER_ID=
BOX_JWT_PRIVATE_KEY=
BOX_JWT_PRIVATE_KEY_PASSPHRASE=
BOX_JWT_PUBLIC_KEY_ID=

# ------------------------------------------------------------------------------
# Slack Notifications
# ------------------------------------------------------------------------------

SLACK_ENABLED=false
SLACK_WEBHOOK_URL=
SLACK_ALERT_WEBHOOK_URL=
SLACK_BOT_TOKEN=
SLACK_CHANNEL_ID=
SLACK_PC_LEAVE_CODE=PC

# ------------------------------------------------------------------------------
# JVM Options
# ------------------------------------------------------------------------------

JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent -Djava.awt.headless=true"
EOF

    chmod 600 "${ENV_FILE}"
    chown "${APP_USER}:${APP_GROUP}" "${ENV_FILE}"

    log_warn "Please update secrets in ${ENV_FILE} before production use."

else

    log_info "Existing environment file found. Preserving it:"
    log_info "${ENV_FILE}"

fi

# ------------------------------------------------------------------------------
# 11. File Ownership & Permissions
# ------------------------------------------------------------------------------

log_info "Applying application ownership and permissions..."

chown -R "${APP_USER}:${APP_GROUP}" "${INSTALL_DIR}"
chown -R "${APP_USER}:${APP_GROUP}" "${DATA_DIR}"
chown -R "${APP_USER}:${APP_GROUP}" "${LOG_DIR}"
chown -R "${APP_USER}:${APP_GROUP}" "${CONFIG_DIR}"

chmod 750 "${INSTALL_DIR}"
chmod 750 "${DATA_DIR}"
chmod 750 "${LOG_DIR}"
chmod 700 "${CONFIG_DIR}"

chmod 640 "${INSTALL_DIR}/app.jar"

# ------------------------------------------------------------------------------
# 12. Configure systemd Service
# ------------------------------------------------------------------------------

log_info "Configuring systemd service at ${SYSTEMD_SERVICE}..."

cat <<EOF > "${SYSTEMD_SERVICE}"
[Unit]
Description=Vacation Planner Assistant Service
After=network.target remote-fs.target

[Service]
Type=simple

User=${APP_USER}
Group=${APP_GROUP}

WorkingDirectory=${INSTALL_DIR}

EnvironmentFile=-${ENV_FILE}

# Keep JVM options explicit here.
# This avoids relying on shell-style expansion of JAVA_OPTS by systemd.
ExecStart=/usr/bin/java \
-Xms512m \
-Xmx2048m \
-XX:+UseG1GC \
-XX:+ExplicitGCInvokesConcurrent \
-Djava.awt.headless=true \
-jar ${INSTALL_DIR}/app.jar

Restart=always
RestartSec=10
SuccessExitStatus=143

# Security hardening
ProtectSystem=full
ProtectHome=true
NoNewPrivileges=true
PrivateTmp=true

# Application logging
StandardOutput=append:${LOG_DIR}/stdout.log
StandardError=append:${LOG_DIR}/stderr.log

# Resource limits
LimitNOFILE=65536
LimitNPROC=4096

[Install]
WantedBy=multi-user.target
EOF

chmod 644 "${SYSTEMD_SERVICE}"

# ------------------------------------------------------------------------------
# 13. OCI OS Firewall Configuration
# ------------------------------------------------------------------------------

log_info "Configuring local firewall for port ${APP_PORT}..."

if command -v firewall-cmd &>/dev/null; then

    if systemctl is-active --quiet firewalld; then

        firewall-cmd \
            --permanent \
            --add-port="${APP_PORT}/tcp" || true

        firewall-cmd --reload || true

        log_success "Port ${APP_PORT}/tcp opened in firewalld."

    else

        log_warn "firewalld is installed but not currently active."
        log_warn "Skipping firewalld port configuration."

    fi

elif command -v ufw &>/dev/null; then

    if ufw status | grep -qw "active"; then

        ufw allow "${APP_PORT}/tcp" || true

        log_success "Port ${APP_PORT}/tcp allowed in UFW."

    fi

else

    log_warn "No supported OS firewall command detected."

fi

# ------------------------------------------------------------------------------
# 14. Reload, Enable & Start systemd Service
# ------------------------------------------------------------------------------

log_info "Reloading systemd configuration..."

systemctl daemon-reload

log_info "Enabling ${APP_NAME} service..."

systemctl enable "${APP_NAME}"

log_info "Restarting ${APP_NAME} service..."

systemctl restart "${APP_NAME}"

# ------------------------------------------------------------------------------
# 15. Initial Service Status
# ------------------------------------------------------------------------------

sleep 2

if systemctl is-active --quiet "${APP_NAME}"; then

    log_success "systemd service is active."

else

    log_error "systemd service failed to start."
    systemctl status "${APP_NAME}" --no-pager || true

    log_error "Recent service logs:"
    journalctl -u "${APP_NAME}" -n 50 --no-pager || true

    exit 1

fi

# ------------------------------------------------------------------------------
# 16. Health Check
# ------------------------------------------------------------------------------

log_info "Performing application health check on port ${APP_PORT}..."

HEALTH_SUCCESS=false

for i in {1..30}; do

    if curl \
        --silent \
        --show-error \
        --fail \
        --connect-timeout 2 \
        --max-time 5 \
        -o /dev/null \
        "http://127.0.0.1:${APP_PORT}/login"; then

        HEALTH_SUCCESS=true
        break

    elif curl \
        --silent \
        --show-error \
        --fail \
        --connect-timeout 2 \
        --max-time 5 \
        -o /dev/null \
        "http://127.0.0.1:${APP_PORT}/"; then

        HEALTH_SUCCESS=true
        break

    fi

    sleep 2

done

# ------------------------------------------------------------------------------
# 17. Deployment Result
# ------------------------------------------------------------------------------

if [[ "${HEALTH_SUCCESS}" == "true" ]]; then

    log_success "Deployment completed successfully!"
    log_success "Application is responding on port ${APP_PORT}."

    echo ""
    echo "=========================================================================="
    echo " Status : Active & Running"
    echo " Service: systemctl status ${APP_NAME}"
    echo " Config : ${ENV_FILE}"
    echo " Logs   : journalctl -u ${APP_NAME} -f"
    echo "         ${LOG_DIR}/stdout.log"
    echo "         ${LOG_DIR}/stderr.log"
    echo " Data   : ${DATA_DIR}"
    echo " Port   : ${APP_PORT}"
    echo ""
    echo " OCI:"
    echo " Ensure TCP/${APP_PORT} is allowed in the OCI VCN"
    echo " Security List / Network Security Group ingress rules."
    echo "=========================================================================="
    echo ""

else

    log_error "Service started but application is not responding on port ${APP_PORT}."

    echo ""
    echo "Check service status:"
    echo "  systemctl status ${APP_NAME} --no-pager"
    echo ""
    echo "Check recent logs:"
    echo "  journalctl -u ${APP_NAME} -n 100 --no-pager"
    echo ""
    echo "Check application log:"
    echo "  tail -100 ${LOG_DIR}/stderr.log"
    echo ""

    exit 1

fi
