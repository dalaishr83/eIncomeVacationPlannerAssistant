#!/usr/bin/env bash
# ==============================================================================
# Production Deployment Script for Oracle Cloud Infrastructure (OCI)
# Application: Vacation Planner Assistant / Holiday Leave Assistant (Spring Boot)
# Platform: Oracle Linux 8/9 / RHEL / Ubuntu on OCI Compute (VM / Bare Metal)
# ==============================================================================
set -euo pipefail

# --- Configuration Defaults ---
APP_NAME="holiday-leave-assistant"
APP_USER="appuser"
APP_GROUP="appuser"
INSTALL_DIR="/opt/${APP_NAME}"
DATA_DIR="/var/lib/${APP_NAME}/data"
LOG_DIR="/var/log/${APP_NAME}"
CONFIG_DIR="/etc/${APP_NAME}"
SYSTEMD_SERVICE="/etc/systemd/system/${APP_NAME}.service"
APP_PORT="${FLASK_PORT:-8080}"
JAVA_PACKAGE="java-1.8.0-openjdk"

# Colors for log output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# 1. Root Check
if [[ $EUID -ne 0 ]]; then
   log_error "This script must be run as root or with sudo privileges."
   exit 1
fi

log_info "Starting production deployment for ${APP_NAME} on Oracle Cloud Infrastructure..."

# 2. Package Manager & Dependencies Setup
log_info "Checking system dependencies and OpenJDK 8 runtime..."
if command -v dnf &>/dev/null; then
    dnf install -y -q epel-release || true
    dnf install -y -q ${JAVA_PACKAGE}-headless curl tar jq rsync firewalld
elif command -v yum &>/dev/null; then
    yum install -y -q ${JAVA_PACKAGE}-headless curl tar jq rsync firewalld
elif command -v apt-get &>/dev/null; then
    apt-get update -qq
    apt-get install -y -qq openjdk-8-jre-headless curl jq rsync ufw
else
    log_warn "Unknown package manager. Ensure OpenJDK 8 or higher is installed manually."
fi

# 3. Create Dedicated Service User
if ! id -u "${APP_USER}" &>/dev/null; then
    log_info "Creating system user and group: ${APP_USER}"
    useradd --system --no-create-home --user-group --shell /sbin/nologin "${APP_USER}"
fi

# 4. Directory Structure Creation
log_info "Setting up runtime directory structure..."
mkdir -p "${INSTALL_DIR}"
mkdir -p "${DATA_DIR}"/{cron-expression,staging,temp,working,restrictedVacationType}
mkdir -p "${LOG_DIR}"
mkdir -p "${CONFIG_DIR}"

# 5. Build Artifact Resolution & Placement
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_SOURCE=""

if [[ -f "${SCRIPT_DIR}/backend/target/${APP_NAME}-1.0.0.jar" ]]; then
    JAR_SOURCE="${SCRIPT_DIR}/backend/target/${APP_NAME}-1.0.0.jar"
elif [[ -f "${SCRIPT_DIR}/${APP_NAME}-1.0.0.jar" ]]; then
    JAR_SOURCE="${SCRIPT_DIR}/${APP_NAME}-1.0.0.jar"
elif compgen -G "${SCRIPT_DIR}/backend/target/*.jar" > /dev/null; then
    JAR_SOURCE=$(ls -t "${SCRIPT_DIR}"/backend/target/*.jar | grep -v 'original' | head -n 1)
fi

if [[ -z "${JAR_SOURCE}" || ! -f "${JAR_SOURCE}" ]]; then
    log_warn "Pre-built jar not found. Attempting maven build..."
    if command -v mvn &>/dev/null; then
        (cd "${SCRIPT_DIR}/backend" && mvn clean package -DskipTests)
        JAR_SOURCE=$(ls -t "${SCRIPT_DIR}"/backend/target/*.jar | grep -v 'original' | head -n 1)
    else
        log_error "Maven is not installed and no JAR file found to deploy."
        exit 1
    fi
fi

log_info "Deploying application binary: ${JAR_SOURCE} -> ${INSTALL_DIR}/app.jar"
cp -f "${JAR_SOURCE}" "${INSTALL_DIR}/app.jar"

# 6. Initialise Baseline Data & Config Files
if [[ -d "${SCRIPT_DIR}/data" ]]; then
    log_info "Syncing base configuration data templates to ${DATA_DIR}..."
    rsync -a --ignore-existing "${SCRIPT_DIR}/data/" "${DATA_DIR}/" || true
fi

# 7. Environment & Secrets Setup
ENV_FILE="${CONFIG_DIR}/${APP_NAME}.env"
if [[ ! -f "${ENV_FILE}" ]]; then
    log_info "Generating default environment file at ${ENV_FILE}..."
    cat <<EOF > "${ENV_FILE}"
# ------------------------------------------------------------------------------
# Production Environment Variables for Holiday Leave Assistant
# ------------------------------------------------------------------------------
FLASK_PORT=8080
DATA_DIR=${DATA_DIR}
LOG_LEVEL=INFO
REPORT_OUTPUT_DIR=${DATA_DIR}/reports
CRON_TIMEZONE=Asia/Kolkata
CRON_VALIDATION_INTERVAL_SECONDS=3600
PERMANENT_SESSION_LIFETIME=3600

# Authentication
LOGIN_USERNAME=admin
LOGIN_PASSWORD_HASH=

# LLM Integration (OpenAI / WatsonX / Local endpoint)
OPENAI_API_KEY=
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=llama3.2
LLM_TEMPERATURE=0.0
LLM_MAX_TOKENS=1024
WATSONX_PROJECT_ID=

# IBM Box Cloud Sync
BOX_ENABLED=false
BOX_CLIENT_ID=
BOX_CLIENT_SECRET=
BOX_ENTERPRISE_ID=
BOX_FOLDER_ID=
BOX_JWT_PRIVATE_KEY=
BOX_JWT_PRIVATE_KEY_PASSPHRASE=
BOX_JWT_PUBLIC_KEY_ID=

# Slack Notifications
SLACK_ENABLED=false
SLACK_WEBHOOK_URL=
SLACK_ALERT_WEBHOOK_URL=
SLACK_BOT_TOKEN=
SLACK_CHANNEL_ID=
SLACK_PC_LEAVE_CODE=PC

# JVM Options (Tuned for OCI Compute)
JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent -Djava.awt.headless=true"
EOF
    chmod 600 "${ENV_FILE}"
    chown "${APP_USER}:${APP_GROUP}" "${ENV_FILE}"
    log_warn "Please update secrets in ${ENV_FILE} before production use."
fi

# 8. Set File Ownership & Permissions
chown -R "${APP_USER}:${APP_GROUP}" "${INSTALL_DIR}"
chown -R "${APP_USER}:${APP_GROUP}" "${DATA_DIR}"
chown -R "${APP_USER}:${APP_GROUP}" "${LOG_DIR}"
chown -R "${APP_USER}:${APP_GROUP}" "${CONFIG_DIR}"
chmod 750 "${INSTALL_DIR}"
chmod 750 "${DATA_DIR}"
chmod 750 "${LOG_DIR}"
chmod 700 "${CONFIG_DIR}"

# 9. Configure systemd Service
log_info "Configuring systemd service at ${SYSTEMD_SERVICE}..."
cat <<EOF > "${SYSTEMD_SERVICE}"
[Unit]
Description=Holiday Leave & Vacation Planner Assistant Service
After=network.target remote-fs.target

[Service]
Type=simple
User=${APP_USER}
Group=${APP_GROUP}
WorkingDirectory=${INSTALL_DIR}
EnvironmentFile=-${ENV_FILE}
ExecStart=/usr/bin/java \$JAVA_OPTS -jar ${INSTALL_DIR}/app.jar
Restart=always
RestartSec=10
SuccessExitStatus=143

# Sandboxing and Security hardening
ProtectSystem=full
ProtectHome=true
NoNewPrivileges=true
PrivateTmp=true

# Standard Logging
StandardOutput=append:${LOG_DIR}/stdout.log
StandardError=append:${LOG_DIR}/stderr.log

# Resource Limits
LimitNOFILE=65536
LimitNPROC=4096

[Install]
WantedBy=multi-user.target
EOF

# 10. OCI OS Firewall Configuration
log_info "Configuring local firewall for port ${APP_PORT}..."
if command -v firewall-cmd &>/dev/null && systemctl is-active --quiet firewalld; then
    firewall-cmd --permanent --add-port="${APP_PORT}/tcp" || true
    firewall-cmd --reload || true
    log_success "Port ${APP_PORT}/tcp opened in firewalld."
elif command -v ufw &>/dev/null && ufw status | grep -qw "active"; then
    ufw allow "${APP_PORT}/tcp" || true
    log_success "Port ${APP_PORT}/tcp allowed in UFW."
fi

# 11. Start & Enable systemd service
log_info "Reloading systemd and restarting service..."
systemctl daemon-reload
systemctl enable "${APP_NAME}"
systemctl restart "${APP_NAME}"

# 12. Health Check Validation
log_info "Performing application health check on port ${APP_PORT}..."
HEALTH_SUCCESS=false
for i in {1..20}; do
    if curl -s -f -o /dev/null "http://127.0.0.1:${APP_PORT}/login" || curl -s -f -o /dev/null "http://127.0.0.1:${APP_PORT}/"; then
        HEALTH_SUCCESS=true
        break
    fi
    sleep 2
done

if [[ "${HEALTH_SUCCESS}" == "true" ]]; then
    log_success "Deployment completed successfully! Service is active and responding on port ${APP_PORT}."
    echo ""
    echo "=========================================================================="
    echo " Status: Active & Running"
    echo " Service: systemctl status ${APP_NAME}"
    echo " Config:  ${ENV_FILE}"
    echo " Logs:    journalctl -u ${APP_NAME} -f OR ${LOG_DIR}/"
    echo " Data:    ${DATA_DIR}"
    echo " OCI Note: Ensure port ${APP_PORT} is open in your OCI VCN Ingress Security Rules."
    echo "=========================================================================="
else
    log_error "Service started but not responding to HTTP requests yet. Check logs using: journalctl -u ${APP_NAME} -n 50 --no-pager"
    exit 1
fi
