#!/bin/bash
set +e

INSTALL_DIR="/opt/soulfire"
COMPOSE_FILE="$INSTALL_DIR/docker-compose.yml"
ENV_FILE="$INSTALL_DIR/.env"
SF_IMAGE="ghcr.io/alexprogrammerde/soulfire"
SF_PORT=38765

TUI_CMD=""
DISTRO_ID=""
DISTRO_ID_LIKE=""
PKG_MGR=""

SSL_MODE=""
TUNNEL_TOKEN=""
DOMAIN=""
EMAIL=""
PUBLIC_IP=""

# --- Output helpers ---

msg_info() { echo -e "\e[34m[INFO]\e[0m $1"; }
msg_ok() { echo -e "\e[32m[OK]\e[0m $1"; }
msg_error() { echo -e "\e[31m[ERROR]\e[0m $1"; }
msg_warn() { echo -e "\e[33m[WARN]\e[0m $1"; }

# --- Precondition checks ---

check_root() {
  if [[ $EUID -ne 0 ]]; then
    msg_error "This script must be run as root. Use: sudo bash $0"
    exit 1
  fi
}

# --- Distro detection ---

detect_distro() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck source=/dev/null
    . /etc/os-release
    DISTRO_ID="${ID:-unknown}"
    DISTRO_ID_LIKE="${ID_LIKE:-}"
  else
    msg_error "Cannot detect distribution (/etc/os-release not found)"
    exit 1
  fi
}

detect_pkg_manager() {
  case "$DISTRO_ID" in
    ubuntu|debian|raspbian|linuxmint|pop)
      PKG_MGR="apt"
      ;;
    fedora)
      PKG_MGR="dnf"
      ;;
    centos|rhel|rocky|alma|ol)
      if command -v dnf &>/dev/null; then
        PKG_MGR="dnf"
      else
        PKG_MGR="yum"
      fi
      ;;
    arch|manjaro|endeavouros)
      PKG_MGR="pacman"
      ;;
    opensuse*|sles)
      PKG_MGR="zypper"
      ;;
    *)
      if [[ "$DISTRO_ID_LIKE" == *debian* ]] || [[ "$DISTRO_ID_LIKE" == *ubuntu* ]]; then
        PKG_MGR="apt"
      elif [[ "$DISTRO_ID_LIKE" == *fedora* ]] || [[ "$DISTRO_ID_LIKE" == *rhel* ]]; then
        PKG_MGR="dnf"
      elif [[ "$DISTRO_ID_LIKE" == *arch* ]]; then
        PKG_MGR="pacman"
      elif [[ "$DISTRO_ID_LIKE" == *suse* ]]; then
        PKG_MGR="zypper"
      else
        msg_error "Unsupported distribution: $DISTRO_ID"
        msg_error "Please install Docker manually and re-run this script."
        exit 1
      fi
      ;;
  esac
}

# --- TUI detection and wrappers ---

install_whiptail() {
  msg_info "Installing whiptail..."
  case "$PKG_MGR" in
    apt)    apt-get install -y whiptail &>/dev/null ;;
    dnf)    dnf install -y newt &>/dev/null ;;
    yum)    yum install -y newt &>/dev/null ;;
    pacman) pacman -S --noconfirm libnewt &>/dev/null ;;
    zypper) zypper install -y newt &>/dev/null ;;
  esac
  return 0
}

check_tui() {
  if command -v whiptail &>/dev/null; then
    TUI_CMD="whiptail"
  elif command -v dialog &>/dev/null; then
    TUI_CMD="dialog"
  else
    install_whiptail || true
    if command -v whiptail &>/dev/null; then
      TUI_CMD="whiptail"
    else
      TUI_CMD="none"
      msg_warn "No TUI available (whiptail/dialog), using basic prompts"
    fi
  fi
}

tui_msgbox() {
  local title="$1" text="$2"
  if [[ "$TUI_CMD" != "none" ]]; then
    $TUI_CMD --title "$title" --msgbox "$text" 14 72
  else
    echo ""
    echo "=== $title ==="
    echo "$text"
    echo ""
    read -rp "Press Enter to continue..."
  fi
}

tui_yesno() {
  local title="$1" text="$2"
  if [[ "$TUI_CMD" != "none" ]]; then
    if $TUI_CMD --title "$title" --yesno "$text" 14 72; then
      return 0
    else
      return 1
    fi
  else
    echo ""
    echo "=== $title ==="
    echo "$text"
    read -rp "[y/N]: " answer
    [[ "$answer" =~ ^[Yy] ]]
  fi
}

tui_inputbox() {
  local title="$1" text="$2" default="${3:-}"
  if [[ "$TUI_CMD" != "none" ]]; then
    local result
    result=$($TUI_CMD --title "$title" --inputbox "$text" 10 72 "$default" 3>&1 1>&2 2>&3) || return 1
    echo "$result"
  else
    echo "" >&2
    echo "=== $title ===" >&2
    echo "$text" >&2
    read -rp "[$default]: " answer
    echo "${answer:-$default}"
  fi
}

tui_menu() {
  local title="$1" text="$2"
  shift 2
  if [[ "$TUI_CMD" != "none" ]]; then
    local result
    result=$($TUI_CMD --title "$title" --menu "$text" 18 72 10 "$@" 3>&1 1>&2 2>&3) || return 1
    echo "$result"
  else
    echo "" >&2
    echo "=== $title ===" >&2
    echo "$text" >&2
    local i=1
    local -a tags=()
    while [[ $# -gt 0 ]]; do
      tags+=("$1")
      echo "  $i) $1 - $2" >&2
      shift 2
      ((i++))
    done
    read -rp "Choice [1-${#tags[@]}]: " choice
    if [[ "$choice" -ge 1 && "$choice" -le ${#tags[@]} ]] 2>/dev/null; then
      echo "${tags[$((choice-1))]}"
    else
      return 1
    fi
  fi
}

# --- System setup ---

update_system() {
  msg_info "Updating system packages..."
  case "$PKG_MGR" in
    apt)    apt-get update -y && apt-get upgrade -y ;;
    dnf)    dnf upgrade -y ;;
    yum)    yum update -y ;;
    pacman) pacman -Syu --noconfirm ;;
    zypper) zypper refresh && zypper update -y ;;
  esac
  msg_ok "System packages updated"
}

docker_distro_id() {
  case "$DISTRO_ID" in
    linuxmint|pop) echo "ubuntu" ;;
    raspbian)      echo "debian" ;;
    *)             echo "$DISTRO_ID" ;;
  esac
}

docker_codename() {
  # shellcheck source=/dev/null
  . /etc/os-release
  case "$DISTRO_ID" in
    linuxmint)
      if [[ -n "${UBUNTU_CODENAME:-}" ]]; then
        echo "$UBUNTU_CODENAME"
      else
        echo "${VERSION_CODENAME:-}"
      fi
      ;;
    *)
      echo "${VERSION_CODENAME:-}"
      ;;
  esac
}

install_docker() {
  if command -v docker &>/dev/null && docker compose version &>/dev/null; then
    msg_ok "Docker and Docker Compose already installed"
    return 0
  fi

  msg_info "Installing Docker..."
  local docker_distro codename

  case "$PKG_MGR" in
    apt)
      apt-get install -y ca-certificates curl gnupg
      install -m 0755 -d /etc/apt/keyrings
      docker_distro=$(docker_distro_id)
      curl -fsSL "https://download.docker.com/linux/${docker_distro}/gpg" -o /etc/apt/keyrings/docker.asc
      chmod a+r /etc/apt/keyrings/docker.asc
      codename=$(docker_codename)
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${docker_distro} ${codename} stable" > /etc/apt/sources.list.d/docker.list
      apt-get update -y
      apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
    dnf)
      dnf install -y dnf-plugins-core
      dnf config-manager --add-repo "https://download.docker.com/linux/fedora/docker-ce.repo" || true
      dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
    yum)
      yum install -y yum-utils
      yum-config-manager --add-repo "https://download.docker.com/linux/centos/docker-ce.repo"
      yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
    pacman)
      pacman -S --noconfirm docker docker-compose
      ;;
    zypper)
      zypper install -y docker docker-compose
      ;;
  esac

  systemctl enable docker
  systemctl start docker
  msg_ok "Docker installed and started"
}

# --- Network helpers ---

detect_public_ip() {
  local ip=""
  for svc in "https://api.ipify.org" "https://ifconfig.me" "https://icanhazip.com" "https://ipinfo.io/ip"; do
    ip=$(curl -fsSL --connect-timeout 5 "$svc" 2>/dev/null | tr -d '[:space:]') || continue
    if [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "$ip"
      return 0
    fi
  done
  return 1
}

check_port() {
  local port="$1"
  if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
    return 1
  fi
  return 0
}

# --- Compose template generators ---

generate_cloudflared_compose() {
  cat <<'COMPOSE'
services:
  app:
    image: ghcr.io/alexprogrammerde/soulfire
    restart: always
    stdin_open: true
    tty: true
    volumes:
      - app_data:/soulfire/data

  cloudflared:
    image: cloudflare/cloudflared
    restart: always
    command: tunnel run
    environment:
      TUNNEL_TOKEN: ${TUNNEL_TOKEN}

volumes:
  app_data:
    driver: local
COMPOSE
}

generate_traefik_domain_compose() {
  cat <<'COMPOSE'
services:
  app:
    image: ghcr.io/alexprogrammerde/soulfire
    restart: always
    stdin_open: true
    tty: true
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.soulfire.rule=Host(`${DOMAIN}`)"
      - "traefik.http.services.soulfire.loadbalancer.server.port=38765"
      - "traefik.http.routers.soulfire.entrypoints=websecure"
      - "traefik.http.routers.soulfire.tls.certresolver=myresolver"
    volumes:
      - app_data:/soulfire/data

  traefik:
    image: traefik
    restart: always
    command:
      - "--api.insecure=true"
      - "--providers.docker=true"
      - "--providers.docker.exposedbydefault=false"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.websecure.address=:443"
      - "--certificatesresolvers.myresolver.acme.tlschallenge=true"
      - "--certificatesresolvers.myresolver.acme.email=${EMAIL}"
      - "--certificatesresolvers.myresolver.acme.storage=/letsencrypt/acme.json"
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock:ro"
      - "letsencrypt:/letsencrypt"

volumes:
  app_data:
    driver: local
  letsencrypt:
    driver: local
COMPOSE
}

generate_traefik_ip_compose() {
  cat <<'COMPOSE'
services:
  app:
    image: ghcr.io/alexprogrammerde/soulfire
    restart: always
    stdin_open: true
    tty: true
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.soulfire.rule=Host(`${PUBLIC_IP}`)"
      - "traefik.http.services.soulfire.loadbalancer.server.port=38765"
      - "traefik.http.routers.soulfire.entrypoints=websecure"
      - "traefik.http.routers.soulfire.tls.certresolver=myresolver"
    volumes:
      - app_data:/soulfire/data

  traefik:
    image: traefik:v3
    restart: always
    command:
      - "--providers.docker=true"
      - "--providers.docker.exposedbydefault=false"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.websecure.address=:443"
      - "--certificatesresolvers.myresolver.acme.tlschallenge=true"
      - "--certificatesresolvers.myresolver.acme.email=${EMAIL}"
      - "--certificatesresolvers.myresolver.acme.storage=/letsencrypt/acme.json"
      - "--certificatesresolvers.myresolver.acme.certificatesduration=160"
      - "--certificatesresolvers.myresolver.acme.profile=shortlived"
      - "--certificatesresolvers.myresolver.acme.disablecommonname=true"
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock:ro"
      - "letsencrypt:/letsencrypt"

volumes:
  app_data:
    driver: local
  letsencrypt:
    driver: local
COMPOSE
}

generate_http_compose() {
  cat <<'COMPOSE'
services:
  app:
    image: ghcr.io/alexprogrammerde/soulfire
    restart: always
    stdin_open: true
    tty: true
    ports:
      - "38765:38765"
    volumes:
      - app_data:/soulfire/data

volumes:
  app_data:
    driver: local
COMPOSE
}

# --- Compose file writing ---

write_compose_file() {
  mkdir -p "$INSTALL_DIR"
  case "$SSL_MODE" in
    cloudflared) generate_cloudflared_compose > "$COMPOSE_FILE" ;;
    traefik)     generate_traefik_domain_compose > "$COMPOSE_FILE" ;;
    traefik-ip)  generate_traefik_ip_compose > "$COMPOSE_FILE" ;;
    http)        generate_http_compose > "$COMPOSE_FILE" ;;
  esac
  msg_ok "Generated $COMPOSE_FILE"
}

write_env_file() {
  local env_content=""
  case "$SSL_MODE" in
    cloudflared)
      env_content="TUNNEL_TOKEN=${TUNNEL_TOKEN}"
      ;;
    traefik)
      env_content="DOMAIN=${DOMAIN}
EMAIL=${EMAIL}"
      ;;
    traefik-ip)
      env_content="PUBLIC_IP=${PUBLIC_IP}
EMAIL=${EMAIL}"
      ;;
    http)
      env_content=""
      ;;
  esac
  echo "$env_content" > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  msg_ok "Generated $ENV_FILE"
}

# --- SSL menu and prompts ---

show_ssl_menu() {
  local choice
  choice=$(tui_menu "SSL Configuration" "How should SoulFire be exposed?" \
    "cloudflared"  "Cloudflared Tunnel (Recommended)" \
    "traefik"      "Traefik + Domain SSL (Let's Encrypt)" \
    "traefik-ip"   "Traefik + IP SSL (Let's Encrypt)" \
    "http"         "HTTP Only (Insecure)") || {
    msg_info "Setup cancelled"
    exit 0
  }

  SSL_MODE="$choice"

  case "$SSL_MODE" in
    cloudflared) prompt_cloudflared ;;
    traefik)     prompt_traefik_domain ;;
    traefik-ip)  prompt_traefik_ip ;;
    http)        confirm_http_warning ;;
  esac
}

prompt_cloudflared() {
  TUNNEL_TOKEN=$(tui_inputbox "Cloudflared Tunnel" \
    "Enter your Cloudflare Tunnel token.\n\nGet one from the Cloudflare Zero Trust dashboard:\nhttps://one.dash.cloudflare.com" \
    "") || {
    msg_info "Setup cancelled"
    exit 0
  }

  if [[ -z "$TUNNEL_TOKEN" ]]; then
    msg_error "Tunnel token cannot be empty"
    prompt_cloudflared
  fi
}

prompt_traefik_domain() {
  DOMAIN=$(tui_inputbox "Domain Configuration" \
    "Enter the domain pointing to this server.\n\nMake sure DNS is configured before proceeding." \
    "") || {
    msg_info "Setup cancelled"
    exit 0
  }

  if [[ -z "$DOMAIN" ]]; then
    msg_error "Domain cannot be empty"
    prompt_traefik_domain
    return
  fi

  EMAIL=$(tui_inputbox "Let's Encrypt Email" \
    "Enter your email for Let's Encrypt certificate notifications." \
    "") || {
    msg_info "Setup cancelled"
    exit 0
  }

  if [[ -z "$EMAIL" ]]; then
    msg_error "Email cannot be empty"
    prompt_traefik_domain
    return
  fi

  if ! check_port 80 || ! check_port 443; then
    tui_msgbox "Port Conflict" "Ports 80 and/or 443 are already in use.\nTraefik needs these ports to be available.\n\nPlease stop the conflicting service and try again."
    exit 1
  fi
}

prompt_traefik_ip() {
  tui_msgbox "IP SSL Information" \
    "IP SSL uses Let's Encrypt to issue certificates for your server's\npublic IP address (no domain needed).\n\nRequirements:\n- Traefik v3.6.7+ (handled automatically)\n- Ports 80 and 443 open to the internet\n- Certificates are short-lived (~6 days, auto-renewed)"

  msg_info "Detecting public IP address..."
  local detected_ip
  detected_ip=$(detect_public_ip) || detected_ip=""

  if [[ -n "$detected_ip" ]]; then
    PUBLIC_IP=$(tui_inputbox "Public IP Address" \
      "Detected public IP: $detected_ip\n\nPress Enter to use this IP or type a different one." \
      "$detected_ip") || {
      msg_info "Setup cancelled"
      exit 0
    }
  else
    PUBLIC_IP=$(tui_inputbox "Public IP Address" \
      "Could not auto-detect your public IP.\n\nPlease enter your server's public IPv4 address." \
      "") || {
      msg_info "Setup cancelled"
      exit 0
    }
  fi

  if [[ -z "$PUBLIC_IP" ]]; then
    msg_error "IP address cannot be empty"
    prompt_traefik_ip
    return
  fi

  EMAIL=$(tui_inputbox "Let's Encrypt Email" \
    "Enter your email for Let's Encrypt certificate notifications." \
    "") || {
    msg_info "Setup cancelled"
    exit 0
  }

  if [[ -z "$EMAIL" ]]; then
    msg_error "Email cannot be empty"
    prompt_traefik_ip
    return
  fi

  if ! check_port 80 || ! check_port 443; then
    tui_msgbox "Port Conflict" "Ports 80 and/or 443 are already in use.\nTraefik needs these ports to be available.\n\nPlease stop the conflicting service and try again."
    exit 1
  fi
}

confirm_http_warning() {
  if ! tui_yesno "Security Warning" \
    "HTTP mode serves SoulFire WITHOUT encryption.\n\nAll traffic including credentials will be sent in cleartext\nand can be intercepted with Man-in-the-Middle attacks.\n\nThis is NOT recommended for production use.\n\nContinue with HTTP only?"; then
    show_ssl_menu
  fi

  if ! check_port "$SF_PORT"; then
    tui_msgbox "Port Conflict" "Port $SF_PORT is already in use.\n\nPlease stop the conflicting service and try again."
    exit 1
  fi
}

# --- Container helpers ---

is_installed() {
  [[ -f "$COMPOSE_FILE" ]]
}

wait_for_healthy() {
  local attempts=0
  local max_attempts=60
  local state=""
  while true; do
    if [ "$attempts" -ge "$max_attempts" ]; then
      echo ""
      msg_warn "Container did not start within ${max_attempts}s"
      return 1
    fi
    state="$(docker compose -f "$COMPOSE_FILE" ps --format '{{.State}}' app 2>/dev/null)" || state="unknown"
    if [ "$state" = "running" ]; then
      echo ""
      msg_ok "SoulFire is running"
      return 0
    fi
    attempts=$((attempts + 1))
    echo -ne "\r\e[34m[INFO]\e[0m Waiting for container to start... (${attempts}s/${max_attempts}s) [state: ${state}]"
    sleep 1
  done
}

# --- Fresh install flow ---

show_welcome() {
  tui_msgbox "SoulFire Dedicated Server Setup" \
    "Welcome to the SoulFire Dedicated Server installer!\n\nThis script will:\n  1. Update your system packages\n  2. Install Docker (if needed)\n  3. Configure SSL/HTTPS\n  4. Deploy SoulFire via Docker Compose\n\nInstall directory: /opt/soulfire/\n\nRe-run this script anytime to manage your installation."
}

do_fresh_install() {
  show_welcome

  if tui_yesno "System Update" "Update system packages before installing?\n\n(Recommended for fresh servers)"; then
    update_system
  fi

  install_docker
  show_ssl_menu
  write_compose_file
  write_env_file

  msg_info "Starting SoulFire..."
  docker compose -f "$COMPOSE_FILE" pull
  docker compose -f "$COMPOSE_FILE" up -d

  msg_info "Waiting for SoulFire to start..."
  if ! wait_for_healthy; then
    tui_msgbox "Slow Start" \
      "SoulFire is taking longer than expected to start.\nThis is normal on first run while Java initializes.\n\nCheck status with:\n  docker compose -f $COMPOSE_FILE ps\n  docker compose -f $COMPOSE_FILE logs app"
  fi

  local access_url
  case "$SSL_MODE" in
    cloudflared) access_url="the URL configured in your Cloudflare tunnel" ;;
    traefik)     access_url="https://${DOMAIN}" ;;
    traefik-ip)  access_url="https://${PUBLIC_IP}" ;;
    http)        access_url="http://<server-ip>:${SF_PORT}" ;;
  esac

  tui_msgbox "Installation Complete" \
    "SoulFire is now running!\n\nAccess: ${access_url}\n\nTo generate an access token, run:\n  sudo bash $0\n  -> Attach to SoulFire console\n  -> Run: generate-token api\n\nRe-run this script to manage your installation."

  msg_ok "SoulFire installed successfully"
}

# --- Management menu ---

do_attach() {
  local state
  state=$(docker compose -f "$COMPOSE_FILE" ps --format '{{.State}}' app 2>/dev/null) || true
  if [[ "$state" != "running" ]]; then
    msg_warn "Container is not running yet (state: ${state:-not found})"
    msg_info "Waiting for SoulFire to start..."
    if ! wait_for_healthy; then
      true  # timed out, check state below
    fi
    state=$(docker compose -f "$COMPOSE_FILE" ps --format '{{.State}}' app 2>/dev/null) || true
    if [[ "$state" != "running" ]]; then
      tui_msgbox "Container Not Ready" \
        "The SoulFire container failed to start.\n\nCurrent state: ${state:-not found}\n\nCheck logs for details:\n  docker compose -f $COMPOSE_FILE logs app"
      return
    fi
  fi
  msg_info "Attaching to SoulFire console (detach with Ctrl+P, Ctrl+Q)..."
  docker compose -f "$COMPOSE_FILE" attach app || true
}

do_logs() {
  msg_info "Showing logs (Ctrl+C to stop)..."
  docker compose -f "$COMPOSE_FILE" logs -f --tail 100 || true
}

do_update() {
  msg_info "Pulling latest images..."
  docker compose -f "$COMPOSE_FILE" pull
  msg_info "Recreating containers..."
  docker compose -f "$COMPOSE_FILE" up -d
  msg_ok "SoulFire updated successfully"
}

do_reconfigure() {
  show_ssl_menu
  msg_info "Stopping current containers..."
  docker compose -f "$COMPOSE_FILE" down
  write_compose_file
  write_env_file
  msg_info "Starting with new configuration..."
  docker compose -f "$COMPOSE_FILE" up -d
  msg_ok "SoulFire reconfigured successfully"
}

do_uninstall() {
  if ! tui_yesno "Confirm Uninstall" \
    "This will stop all containers and remove all SoulFire data.\n\nAre you sure?"; then
    return
  fi

  if ! tui_yesno "Final Confirmation" \
    "ALL SOULFIRE DATA WILL BE PERMANENTLY DELETED.\n\nThis cannot be undone. Proceed?"; then
    return
  fi

  msg_info "Stopping containers..."
  docker compose -f "$COMPOSE_FILE" down -v
  msg_info "Removing $INSTALL_DIR..."
  rm -rf "$INSTALL_DIR"
  msg_ok "SoulFire has been completely removed"
}

do_status() {
  docker compose -f "$COMPOSE_FILE" ps
  echo ""
  read -rp "Press Enter to continue..."
}

show_manage_menu() {
  while true; do
    local choice
    choice=$(tui_menu "SoulFire Management" "SoulFire is installed at $INSTALL_DIR" \
      "attach"      "Attach to SoulFire console" \
      "logs"        "View container logs" \
      "status"      "Show container status" \
      "update"      "Update SoulFire (pull latest)" \
      "reconfigure" "Change SSL configuration" \
      "uninstall"   "Remove SoulFire completely" \
      "exit"        "Exit") || break

    case "$choice" in
      attach)      do_attach ;;
      logs)        do_logs ;;
      status)      do_status ;;
      update)      do_update ;;
      reconfigure) do_reconfigure ;;
      uninstall)   do_uninstall; break ;;
      exit|"")     break ;;
    esac
  done
}

# --- Main ---

main() {
  check_root
  detect_distro
  detect_pkg_manager
  check_tui

  if is_installed; then
    show_manage_menu
  else
    do_fresh_install
  fi
}

main "$@"
