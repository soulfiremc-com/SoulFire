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
RECOMMENDED_ACCESS_MODE=""

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

is_valid_domain() {
  local domain="$1"
  [[ "$domain" =~ ^([a-zA-Z0-9]([-a-zA-Z0-9]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$ ]]
}

is_valid_email() {
  local email="$1"
  [[ "$email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]
}

is_valid_ipv4() {
  local ip="$1"
  local IFS=.
  local -a octets
  read -r -a octets <<< "$ip"

  if [[ ${#octets[@]} -ne 4 ]]; then
    return 1
  fi

  for octet in "${octets[@]}"; do
    if ! [[ "$octet" =~ ^[0-9]+$ ]] || ((octet < 0 || octet > 255)); then
      return 1
    fi
  done

  return 0
}

is_known_access_mode() {
  case "$1" in
    cloudflared|traefik|traefik-ip|http) return 0 ;;
    *)                                   return 1 ;;
  esac
}

app_container_id() {
  docker compose -f "$COMPOSE_FILE" ps -q app 2>/dev/null
}

app_container_state() {
  local container_id
  container_id=$(app_container_id)

  if [[ -z "$container_id" ]]; then
    echo "not found"
    return 0
  fi

  docker inspect --format '{{.State.Status}}' "$container_id" 2>/dev/null || echo "unknown"
}

app_container_health() {
  local container_id
  container_id=$(app_container_id)

  if [[ -z "$container_id" ]]; then
    echo "not found"
    return 0
  fi

  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id" 2>/dev/null || echo "unknown"
}

access_mode_name() {
  case "$1" in
    cloudflared) echo "Cloudflared Tunnel" ;;
    traefik)     echo "Traefik + Domain HTTPS" ;;
    traefik-ip)  echo "Traefik + IP HTTPS" ;;
    http)        echo "HTTP Only" ;;
    *)           echo "Unknown" ;;
  esac
}

access_mode_summary() {
  case "$1" in
    cloudflared) echo "Public HTTPS through Cloudflare Tunnel without opening ports 80/443 on your server." ;;
    traefik)     echo "Public HTTPS on your own domain using Traefik and a Let's Encrypt certificate." ;;
    traefik-ip)  echo "Public HTTPS directly on your server's public IPv4 using Traefik and Let's Encrypt." ;;
    http)        echo "Plain HTTP only. Suitable for local testing or when another reverse proxy already handles HTTPS." ;;
    *)           echo "Unknown access method." ;;
  esac
}

access_mode_requirements() {
  case "$1" in
    cloudflared) echo "Requirements: Cloudflare account, browser access to the Cloudflare dashboard, and an existing tunnel token." ;;
    traefik)     echo "Requirements: a domain pointing to this server, ports 80/443 reachable from the internet, and an email for Let's Encrypt." ;;
    traefik-ip)  echo "Requirements: public IPv4 address, ports 80/443 reachable from the internet, and an email for Let's Encrypt." ;;
    http)        echo "Requirements: port ${SF_PORT} reachable to your clients, or another reverse proxy in front of SoulFire." ;;
    *)           echo "Requirements unavailable." ;;
  esac
}

access_mode_caveats() {
  case "$1" in
    cloudflared) echo "Caveats: this script cannot create the tunnel for you. You must complete the Cloudflare-side setup in a browser first." ;;
    traefik)     echo "Caveats: DNS must already point at this server and certificate issuance can fail if ports 80/443 are blocked." ;;
    traefik-ip)  echo "Caveats: IP-based certificates are more niche and less predictable than domain-based HTTPS." ;;
    http)        echo "Caveats: some clients may reject or degrade plain HTTP connections, especially browsers or HTTPS-enforcing clients." ;;
    *)           echo "Caveats unavailable." ;;
  esac
}

selected_access_url() {
  case "$1" in
    cloudflared) echo "the URL configured in your Cloudflare tunnel" ;;
    traefik)     echo "https://${DOMAIN}" ;;
    traefik-ip)  echo "https://${PUBLIC_IP}" ;;
    http)        echo "http://<server-ip>:${SF_PORT}" ;;
    *)           echo "unknown" ;;
  esac
}

load_existing_config() {
  SSL_MODE=""
  DOMAIN=""
  EMAIL=""
  PUBLIC_IP=""
  TUNNEL_TOKEN=""

  if [[ -f "$ENV_FILE" ]]; then
    # shellcheck source=/dev/null
    . "$ENV_FILE"
  fi

  if [[ -n "$TUNNEL_TOKEN" ]]; then
    SSL_MODE="cloudflared"
  elif [[ -n "$DOMAIN" ]]; then
    SSL_MODE="traefik"
  elif [[ -n "$PUBLIC_IP" ]]; then
    SSL_MODE="traefik-ip"
  else
    SSL_MODE="http"
  fi
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
      - "--certificatesresolvers.myresolver.acme.httpchallenge=true"
      - "--certificatesresolvers.myresolver.acme.httpchallenge.entrypoint=web"
      - "--certificatesresolvers.myresolver.acme.httpchallenge.delay=5"
      - "--certificatesresolvers.myresolver.acme.tlschallenge=true"
      - "--certificatesresolvers.myresolver.acme.tlschallenge.delay=5"
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
  if ! is_known_access_mode "$SSL_MODE"; then
    msg_error "Cannot write compose file: invalid access method '${SSL_MODE:-<empty>}'"
    return 1
  fi

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

  if ! is_known_access_mode "$SSL_MODE"; then
    msg_error "Cannot write environment file: invalid access method '${SSL_MODE:-<empty>}'"
    return 1
  fi

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

show_before_you_start() {
  tui_msgbox "Before You Start" \
    "This installer can set up Docker, generate a Docker Compose stack, and start SoulFire.\n\nSome access methods still require work outside this script:\n  - Cloudflared needs a Cloudflare account, browser access, and an existing tunnel/token\n  - Domain HTTPS needs DNS pointed to this server\n  - HTTPS certificate issuance requires public reachability for validation"

  tui_msgbox "HTTPS and Let's Encrypt" \
    "HTTPS encrypts the connection between SoulFire and its clients.\n\nLet's Encrypt is the certificate authority used by the Traefik options. It issues the certificate that lets clients trust your server instead of showing security warnings.\n\nIf validation cannot reach your server on ports 80/443, certificate issuance will fail."
}

show_access_method_overview() {
  tui_msgbox "Access Methods" \
    "Choose how clients should reach SoulFire:\n\n  - Cloudflared Tunnel: easiest public HTTPS if you already use Cloudflare\n  - Traefik + Domain HTTPS: best general-purpose production setup\n  - Traefik + IP HTTPS: HTTPS without a domain, but more niche\n  - HTTP Only: for local testing, LAN use, or when another proxy already handles HTTPS"
}

show_cloudflared_info() {
  tui_msgbox "Cloudflared Tunnel" \
    "Use this when you already use Cloudflare and want public HTTPS without opening ports 80/443.\n\nRequires:\n  - Cloudflare account\n  - Browser access to the Cloudflare dashboard\n  - A tunnel created in Cloudflare Zero Trust\n  - A valid tunnel token\n\nThis script only runs the connector. It does not create the tunnel for you."
}

show_traefik_domain_info() {
  tui_msgbox "Traefik + Domain HTTPS" \
    "This is the most standard public deployment option.\n\nTraefik listens on ports 80 and 443 and requests a Let's Encrypt certificate for your domain.\n\nRequires:\n  - A domain you control\n  - DNS already pointing to this server\n  - Ports 80 and 443 reachable from the internet\n  - Browser or DNS-panel access to manage your domain"
}

show_traefik_ip_info() {
  tui_msgbox "Traefik + IP HTTPS" \
    "This option requests a certificate for the server's public IPv4 address instead of a domain.\n\nUse it when you need HTTPS but do not have a domain.\n\nCaveats:\n  - More niche than domain-based HTTPS\n  - Depends on public IPv4 reachability\n  - Ports 80 and 443 must be reachable from the internet\n  - Some environments are less thoroughly tested than standard domain HTTPS"
}

show_http_info() {
  tui_msgbox "HTTP Only" \
    "HTTP mode does not use encryption.\n\nUse it only when:\n  - you are testing locally\n  - clients connect over a trusted LAN\n  - another reverse proxy already provides HTTPS in front of SoulFire\n\nCaveats:\n  - credentials and traffic are sent in cleartext\n  - some clients may refuse or warn on plain HTTP\n  - browser-based flows and HTTPS-enforcing environments can break"
}

show_access_method_details() {
  show_cloudflared_info
  show_traefik_domain_info
  show_traefik_ip_info
  show_http_info
}

recommend_access_method() {
  RECOMMENDED_ACCESS_MODE=""

  if tui_yesno "Recommendation Wizard" \
    "Is this mainly for local testing, LAN use, or behind an existing HTTPS reverse proxy?"; then
    RECOMMENDED_ACCESS_MODE="http"
    return 0
  fi

  if tui_yesno "Recommendation Wizard" \
    "Do you already use Cloudflare and want to expose SoulFire through Cloudflare Tunnel?"; then
    RECOMMENDED_ACCESS_MODE="cloudflared"
    return 0
  fi

  if tui_yesno "Recommendation Wizard" \
    "Do you have a domain name that you can point to this server?"; then
    RECOMMENDED_ACCESS_MODE="traefik"
    return 0
  fi

  if tui_yesno "Recommendation Wizard" \
    "Can this server accept inbound traffic on ports 80 and 443 from the public internet?"; then
    RECOMMENDED_ACCESS_MODE="traefik-ip"
    return 0
  fi

  RECOMMENDED_ACCESS_MODE="http"
  return 0
}

confirm_access_selection() {
  local access_mode="$1"
  if tui_yesno "Use This Access Method?" \
    "Selected: $(access_mode_name "$access_mode")\n\n$(access_mode_summary "$access_mode")\n\n$(access_mode_requirements "$access_mode")\n\n$(access_mode_caveats "$access_mode")\n\nUse this method?"; then
    return 0
  fi

  return 1
}

confirm_configuration_summary() {
  local access_mode="$1"
  local details=""

  if ! is_known_access_mode "$access_mode"; then
    tui_msgbox "Configuration Error" \
      "The installer selected an invalid access method: ${access_mode:-<empty>}\n\nPlease go back and choose the access method again."
    return 1
  fi

  case "$access_mode" in
    cloudflared)
      details="Tunnel token: configured"
      ;;
    traefik)
      details="Domain: ${DOMAIN}\nLet's Encrypt email: ${EMAIL}\nPorts required: 80 and 443"
      ;;
    traefik-ip)
      details="Public IP: ${PUBLIC_IP}\nLet's Encrypt email: ${EMAIL}\nPorts required: 80 and 443"
      ;;
    http)
      details="Published port: ${SF_PORT}\nTLS: disabled"
      ;;
  esac

  tui_yesno "Confirm Configuration" \
    "Please review the configuration before continuing.\n\nAccess method: $(access_mode_name "$access_mode")\nAccess URL: $(selected_access_url "$access_mode")\n\n${details}\n\n$(access_mode_caveats "$access_mode")\n\nProceed with this configuration?"
}

show_ssl_menu() {
  local choice recommended
  SSL_MODE=""

  while true; do
    show_access_method_overview

    if tui_yesno "Guided Recommendation" \
      "Would you like the installer to recommend an access method based on your setup?"; then
      recommend_access_method
      recommended="$RECOMMENDED_ACCESS_MODE"

      if ! is_known_access_mode "$recommended"; then
        tui_msgbox "Recommendation Error" \
          "The installer produced an invalid access method: ${recommended:-<empty>}\n\nPlease choose the access method manually."
      elif confirm_access_selection "$recommended"; then
        SSL_MODE="$recommended"
      fi
    fi

    while [[ -z "$SSL_MODE" ]]; do
      choice=$(tui_menu "Access Method" "How should SoulFire be exposed?" \
        "cloudflared"  "Cloudflared Tunnel" \
        "traefik"      "Traefik + Domain HTTPS" \
        "traefik-ip"   "Traefik + IP HTTPS" \
        "http"         "HTTP Only" \
        "details"      "Explain the options and caveats" \
        "cancel"       "Cancel setup") || {
          msg_info "Setup cancelled"
          exit 0
        }

      case "$choice" in
        cloudflared|traefik|traefik-ip|http)
          if confirm_access_selection "$choice"; then
            SSL_MODE="$choice"
          fi
          ;;
        details) show_access_method_details ;;
        cancel)
          msg_info "Setup cancelled"
          exit 0
          ;;
      esac
    done

    case "$SSL_MODE" in
      cloudflared) prompt_cloudflared ;;
      traefik)     prompt_traefik_domain ;;
      traefik-ip)  prompt_traefik_ip ;;
      http)
        if ! confirm_http_warning; then
          SSL_MODE=""
          continue
        fi
        ;;
    esac

    if confirm_configuration_summary "$SSL_MODE"; then
      return 0
    fi

    SSL_MODE=""
  done
}

prompt_cloudflared() {
  show_cloudflared_info

  TUNNEL_TOKEN=$(tui_inputbox "Cloudflared Tunnel" \
    "Enter your Cloudflare Tunnel token.\n\nGet it from the Cloudflare Zero Trust dashboard after creating a tunnel in your browser:\nhttps://one.dash.cloudflare.com" \
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
  show_traefik_domain_info

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

  if ! is_valid_domain "$DOMAIN"; then
    msg_error "Domain format looks invalid: $DOMAIN"
    prompt_traefik_domain
    return
  fi

  EMAIL=$(tui_inputbox "Let's Encrypt Email" \
    "Enter your email for Let's Encrypt certificate notifications.\n\nLet's Encrypt uses this address for expiry and validation notices." \
    "") || {
    msg_info "Setup cancelled"
    exit 0
  }

  if [[ -z "$EMAIL" ]]; then
    msg_error "Email cannot be empty"
    prompt_traefik_domain
    return
  fi

  if ! is_valid_email "$EMAIL"; then
    msg_error "Email format looks invalid: $EMAIL"
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
    "Traefik + IP HTTPS uses Let's Encrypt to issue certificates for your server's public IPv4 address instead of a domain.\n\nRequirements:\n- Traefik v3.6.7+ (handled automatically)\n- Ports 80 and 443 open to the internet\n- Certificates are short-lived (~6 days, auto-renewed)\n\nCaveats:\n- More niche than domain-based HTTPS\n- Depends on public IPv4 reachability\n- Some environments are less thoroughly tested than standard domain HTTPS"

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

  if ! is_valid_ipv4 "$PUBLIC_IP"; then
    msg_error "Public IPv4 format looks invalid: $PUBLIC_IP"
    prompt_traefik_ip
    return
  fi

  EMAIL=$(tui_inputbox "Let's Encrypt Email" \
    "Enter your email for Let's Encrypt certificate notifications.\n\nLet's Encrypt uses this address for expiry and validation notices." \
    "") || {
    msg_info "Setup cancelled"
    exit 0
  }

  if [[ -z "$EMAIL" ]]; then
    msg_error "Email cannot be empty"
    prompt_traefik_ip
    return
  fi

  if ! is_valid_email "$EMAIL"; then
    msg_error "Email format looks invalid: $EMAIL"
    prompt_traefik_ip
    return
  fi

  if ! check_port 80 || ! check_port 443; then
    tui_msgbox "Port Conflict" "Ports 80 and/or 443 are already in use.\nTraefik needs these ports to be available.\n\nPlease stop the conflicting service and try again."
    exit 1
  fi
}

confirm_http_warning() {
  show_http_info

  if ! tui_yesno "Security Warning" \
    "HTTP mode serves SoulFire WITHOUT encryption.\n\nAll traffic including credentials will be sent in cleartext and can be intercepted.\n\nSome clients may not work correctly over plain HTTP, especially browser-based flows or environments that expect HTTPS by default.\n\nThis is not recommended for public production use.\n\nContinue with HTTP only?"; then
    return 1
  fi

  if ! check_port "$SF_PORT"; then
    tui_msgbox "Port Conflict" "Port $SF_PORT is already in use.\n\nPlease stop the conflicting service and try again."
    exit 1
  fi

  return 0
}

# --- Container helpers ---

is_installed() {
  [[ -f "$COMPOSE_FILE" ]]
}

expected_tls_identifier() {
  case "$SSL_MODE" in
    traefik)    echo "$DOMAIN" ;;
    traefik-ip) echo "$PUBLIC_IP" ;;
    *)          return 1 ;;
  esac
}

traefik_cert_issued() {
  local identifier
  identifier=$(expected_tls_identifier) || return 1

  docker compose -f "$COMPOSE_FILE" exec -T traefik sh -lc \
    "test -s /letsencrypt/acme.json && grep -Fq \"$identifier\" /letsencrypt/acme.json" \
    >/dev/null 2>&1
}

traefik_default_cert_served() {
  local identifier cert_details
  identifier=$(expected_tls_identifier) || return 1

  if ! command -v openssl >/dev/null 2>&1; then
    return 1
  fi

  cert_details=$(
    printf '' | openssl s_client -connect 127.0.0.1:443 -servername "$identifier" 2>/dev/null \
      | openssl x509 -noout -subject -issuer 2>/dev/null
  ) || return 1

  [[ "$cert_details" == *"TRAEFIK DEFAULT CERT"* ]]
}

wait_for_tls_certificate() {
  local identifier attempts=0 max_attempts=120
  identifier=$(expected_tls_identifier) || return 0

  while true; do
    if traefik_cert_issued && ! traefik_default_cert_served; then
      echo ""
      msg_ok "Traefik obtained a trusted certificate for ${identifier}"
      return 0
    fi

    if [ "$attempts" -ge "$max_attempts" ]; then
      echo ""
      msg_warn "Traefik did not obtain a usable certificate for ${identifier} within ${max_attempts}s"
      return 1
    fi

    attempts=$((attempts + 1))
    echo -ne "\r\e[34m[INFO]\e[0m Waiting for Traefik certificate... (${attempts}s/${max_attempts}s) [identifier: ${identifier}]"
    sleep 1
  done
}

verify_tls_access_ready() {
  case "$SSL_MODE" in
    traefik|traefik-ip)
      msg_info "Verifying Traefik certificate issuance..."
      if ! wait_for_tls_certificate; then
        tui_msgbox "Certificate Not Ready" \
          "Traefik did not obtain a usable certificate for $(expected_tls_identifier).\n\nThe installer will stop here because browsers would otherwise see Traefik's default self-signed certificate.\n\nUseful commands:\n  docker compose -f $COMPOSE_FILE logs traefik\n  docker compose -f $COMPOSE_FILE exec -T traefik cat /letsencrypt/acme.json"
        return 1
      fi
      ;;
  esac

  return 0
}

wait_for_healthy() {
  local attempts=0
  local max_attempts=180
  local state=""
  local health=""
  while true; do
    if [ "$attempts" -ge "$max_attempts" ]; then
      echo ""
      msg_warn "Container did not become ready within ${max_attempts}s"
      return 1
    fi

    state="$(app_container_state)"
    health="$(app_container_health)"

    if [[ "$state" = "running" && ( "$health" = "healthy" || "$health" = "none" ) ]]; then
      echo ""
      msg_ok "SoulFire is ready"
      return 0
    fi

    attempts=$((attempts + 1))
    echo -ne "\r\e[34m[INFO]\e[0m Waiting for SoulFire to become ready... (${attempts}s/${max_attempts}s) [state: ${state}, health: ${health}]"
    sleep 1
  done
}

# --- Fresh install flow ---

show_welcome() {
  tui_msgbox "SoulFire Dedicated Server Setup" \
    "Welcome to the SoulFire Dedicated Server installer!\n\nThis script will:\n  1. Update your system packages\n  2. Install Docker (if needed)\n  3. Help you choose an access method\n  4. Deploy SoulFire via Docker Compose\n\nInstall directory: /opt/soulfire/\n\nAfter installation, the management menu will open automatically."
}

show_startup_troubleshooting() {
  local details=""

  case "$SSL_MODE" in
    cloudflared)
      details="Likely causes:\n  - SoulFire is still starting\n  - the Cloudflare tunnel token is invalid\n  - Cloudflared is connected but the app is not healthy yet"
      ;;
    traefik)
      details="Likely causes:\n  - SoulFire is still starting\n  - DNS does not point to this server yet\n  - ports 80/443 are blocked by a firewall or router"
      ;;
    traefik-ip)
      details="Likely causes:\n  - SoulFire is still starting\n  - the public IP is wrong or changed\n  - ports 80/443 are blocked by a firewall or router"
      ;;
    http)
      details="Likely causes:\n  - SoulFire is still starting\n  - port ${SF_PORT} is blocked or already in use\n  - another service is interfering with the container"
      ;;
  esac
  
  tui_msgbox "Startup Taking Longer Than Expected" \
    "SoulFire is taking longer than expected to become ready.\n\n${details}\n\nUseful commands:\n  docker compose -f $COMPOSE_FILE ps\n  docker compose -f $COMPOSE_FILE logs app\n  docker compose -f $COMPOSE_FILE logs --tail 100"
}

show_post_install_guidance() {
  local access_url="$1"

  tui_msgbox "Next Steps" \
    "SoulFire is now running.\n\nAccess: ${access_url}\n\nRecommended next steps:\n  1. Open the management menu next\n  2. Attach to the SoulFire console\n  3. Run: generate-token api\n  4. Change the default root email if needed\n  5. Use logs/status if something is not reachable yet"
}

do_fresh_install() {
  show_welcome
  show_before_you_start

  if tui_yesno "System Update" "Update system packages before installing?\n\n(Recommended for fresh servers)"; then
    update_system
  fi

  install_docker
  show_ssl_menu

  if ! is_known_access_mode "$SSL_MODE"; then
    msg_error "Installer aborted: invalid access method '${SSL_MODE:-<empty>}'"
    exit 1
  fi

  write_compose_file || exit 1
  write_env_file || exit 1

  msg_info "Starting SoulFire..."
  docker compose -f "$COMPOSE_FILE" pull
  docker compose -f "$COMPOSE_FILE" up -d

  msg_info "Waiting for SoulFire to start..."
  if ! wait_for_healthy; then
    show_startup_troubleshooting
  fi
  verify_tls_access_ready || exit 1

  local access_url
  access_url=$(selected_access_url "$SSL_MODE")

  show_post_install_guidance "$access_url"

  msg_ok "SoulFire installed successfully"
  show_manage_menu
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
  msg_info "Waiting for SoulFire to become ready..."
  if ! wait_for_healthy; then
    show_startup_troubleshooting
  fi
  verify_tls_access_ready || return 1
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
  msg_info "Waiting for SoulFire to become ready..."
  if ! wait_for_healthy; then
    show_startup_troubleshooting
  fi
  verify_tls_access_ready || return 1
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
  load_existing_config
  echo "Access method: $(access_mode_name "$SSL_MODE")"
  echo "Expected access URL: $(selected_access_url "$SSL_MODE")"
  echo "Container state: $(app_container_state)"
  echo "Container health: $(app_container_health)"
  echo ""
  docker compose -f "$COMPOSE_FILE" ps
  echo ""
  read -rp "Press Enter to continue..."
}

show_manage_menu() {
  while true; do
    load_existing_config
    local choice
    choice=$(tui_menu "SoulFire Management" "SoulFire is installed at $INSTALL_DIR\n\nAccess method: $(access_mode_name "$SSL_MODE")\nAccess URL: $(selected_access_url "$SSL_MODE")\nContainer: $(app_container_state) / $(app_container_health)" \
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
