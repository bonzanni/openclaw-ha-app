# OpenClaw HA Add-on — Scope A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-quality Home Assistant add-on that runs the OpenClaw gateway with S6 v3, bashio, AppArmor, ingress, and proper persistence.

**Architecture:** Multi-stage Docker build (Node.js builder + HA base-debian runtime). S6 v3 oneshot init generates config and secrets, longrun services manage gateway/nginx/ttyd. nginx serves a tabbed ingress landing page with pre-authenticated gateway proxy and optional terminal. All user-facing state in `/config/` (addon_config mount), internal runtime state in `/data/`.

**Tech Stack:** Node.js 24, OpenClaw 2026.4.2, S6-overlay v3, bashio, nginx, ttyd, tempio (Go templates), AppArmor

**Pin:** `openclaw@2026.4.2`

**Scope:** This is Scope A only — the core add-on. Integration compatibility (Scope B) and HA MCP (Scope C) are separate plans.

**Reference implementation:** `../honcho-ha-app` — follow its patterns exactly unless noted otherwise.

---

## File Structure

```
openclaw-ha-app/
├── repository.yaml                          # HA add-on store manifest
├── .gitattributes                           # LF enforcement (critical for S6 scripts on Windows)
├── .gitignore
├── openclaw/                                # Add-on directory
│   ├── config.yaml                          # HA add-on manifest + schema
│   ├── Dockerfile                           # Multi-stage: node:24 builder + HA base-debian runtime
│   ├── apparmor.txt                         # AppArmor profile (S6 v3 paths)
│   ├── DOCS.md                              # End-user documentation
│   ├── CHANGELOG.md                         # Version history
│   ├── translations/
│   │   └── en.yaml                          # UI labels for config options
│   └── rootfs/                              # Copied verbatim into container at /
│       ├── app/
│       │   └── www/
│       │       ├── index.html               # Tabbed landing page (Gateway + Terminal)
│       │       └── style.css                # Dark theme matching HA
│       └── etc/
│           ├── nginx/
│           │   └── servers/
│           │       └── ingress.conf.gtpl    # tempio template (Go {{ .var }} syntax)
│           └── s6-overlay/
│               ├── scripts/
│               │   └── openclaw-init.sh     # Oneshot init: token gen, config, nginx render
│               └── s6-rc.d/
│                   ├── user/
│                   │   └── contents.d/
│                   │       ├── openclaw-gateway  # (empty marker)
│                   │       ├── nginx             # (empty marker)
│                   │       └── ttyd              # (empty marker)
│                   ├── openclaw-init/
│                   │   ├── type                  # "oneshot"
│                   │   └── up                    # path to init script
│                   ├── openclaw-gateway/
│                   │   ├── type                  # "longrun"
│                   │   ├── run                   # start gateway
│                   │   ├── finish                # crash handling
│                   │   └── dependencies.d/
│                   │       └── openclaw-init     # (empty marker)
│                   ├── nginx/
│                   │   ├── type                  # "longrun"
│                   │   ├── run                   # start nginx
│                   │   ├── finish                # log exit
│                   │   └── dependencies.d/
│                   │       └── openclaw-init     # (empty marker)
│                   └── ttyd/
│                       ├── type                  # "longrun"
│                       ├── run                   # conditional: sleep infinity when disabled
│                       ├── finish                # exit 256 when disabled
│                       └── dependencies.d/
│                           └── openclaw-init     # (empty marker)
```

---

### Task 1: Repository Scaffold

**Files:**
- Create: `repository.yaml`
- Create: `.gitattributes`
- Create: `.gitignore`

- [ ] **Step 1: Initialize git repo**

```bash
cd /c/Users/nicol/Projects/openclaw-ha-app
git init
```

- [ ] **Step 2: Create `.gitattributes`**

This is critical — S6 scripts with CRLF line endings silently fail. Force LF for all text files.

```
* text=auto eol=lf
*.png binary
*.ico binary
```

- [ ] **Step 3: Create `.gitignore`**

```
__pycache__/
*.pyc
.DS_Store
node_modules/
```

- [ ] **Step 4: Create `repository.yaml`**

```yaml
name: OpenClaw AI Gateway
url: https://github.com/bonzanni/openclaw-ha-app
maintainer: Nicola Bonzanni <nicola@bonzanni.it>
```

- [ ] **Step 5: Create add-on directory structure**

```bash
mkdir -p openclaw/rootfs/app/www
mkdir -p openclaw/rootfs/etc/nginx/servers
mkdir -p openclaw/rootfs/etc/s6-overlay/scripts
mkdir -p openclaw/rootfs/etc/s6-overlay/s6-rc.d/user/contents.d
mkdir -p openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-init
mkdir -p openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-gateway/dependencies.d
mkdir -p openclaw/rootfs/etc/s6-overlay/s6-rc.d/nginx/dependencies.d
mkdir -p openclaw/rootfs/etc/s6-overlay/s6-rc.d/ttyd/dependencies.d
mkdir -p openclaw/translations
```

- [ ] **Step 6: Commit**

```bash
git add .gitattributes .gitignore repository.yaml openclaw/
git commit -m "scaffold: repository structure and directory layout"
```

---

### Task 2: Add-on Manifest (config.yaml)

**Files:**
- Create: `openclaw/config.yaml`

- [ ] **Step 1: Write config.yaml**

```yaml
name: "OpenClaw"
description: "Local-first personal AI gateway connecting 24+ messaging platforms to AI agents"
version: "0.1.0"
slug: "openclaw"
url: "https://github.com/bonzanni/openclaw-ha-app"
arch:
  - amd64
  - aarch64
init: false
startup: application
boot: auto
ingress: true
ingress_port: 8099
ingress_entry: /
panel_icon: "mdi:robot"
panel_title: "OpenClaw"
watchdog: "http://[HOST]:18789/healthz"
backup: cold
apparmor: true
hassio_api: true
homeassistant_api: true
map:
  - addon_config:rw
  - data:rw
ports:
  18789/tcp: null
  18790/tcp: null
ports_description:
  18789/tcp: "Gateway API (enable for companion integration or direct access)"
  18790/tcp: "Bridge Protocol (multi-gateway federation)"
options:
  log_level: "info"
  gateway_port: 18789
  enable_terminal: false
  enable_ha_mcp: true
schema:
  log_level: "list(trace|debug|info|notice|warning|error|fatal)"
  gateway_port: "port"
  enable_terminal: "bool"
  enable_ha_mcp: "bool"
```

- [ ] **Step 2: Commit**

```bash
git add openclaw/config.yaml
git commit -m "feat: add-on manifest with 4-option schema"
```

---

### Task 3: AppArmor Profile

**Files:**
- Create: `openclaw/apparmor.txt`

- [ ] **Step 1: Write AppArmor profile**

Based on honcho pattern but adapted for Node.js + nginx + ttyd.

```
#include <tunables/global>

profile openclaw flags=(attach_disconnected,mediate_deleted) {
  #include <abstractions/base>

  # Capabilities
  file,
  signal (send) set=(kill,term,int,hup,cont),
  capability fowner,
  capability chown,
  capability dac_override,
  capability setuid,
  capability setgid,

  # S6-Overlay (v3 paths)
  /init ix,
  /bin/** ix,
  /usr/bin/** ix,
  /usr/sbin/** ix,
  /run/{s6,s6-rc*,service}/** ix,
  /package/** ix,
  /command/** ix,
  /etc/s6-overlay/** rwix,
  /run/{,**} rwk,
  /dev/tty rw,

  # Bashio
  /usr/lib/bashio/** ix,
  /tmp/** rwk,

  # Node.js and OpenClaw
  /usr/local/bin/node ix,
  /usr/local/bin/openclaw ix,
  /usr/local/lib/node_modules/** r,
  /usr/local/lib/node_modules/**/node_modules/.bin/* ix,

  # nginx
  /usr/sbin/nginx ix,
  /etc/nginx/** r,
  /var/log/nginx/** rw,
  /var/lib/nginx/** rw,

  # ttyd
  /usr/bin/ttyd ix,

  # Persistent user config (addon_config mount)
  /config/** rw,

  # Internal data
  /data/** rw,

  # Network
  network inet stream,
  network inet dgram,
  network inet6 stream,
  network inet6 dgram,
  network unix stream,

  # Proc and sys (read-only)
  /proc/** r,
  /sys/** r,

  # Deny dangerous paths
  deny /var/run/docker.sock rw,
  deny /proc/[0-9]*/environ r,
  deny /proc/sysrq-trigger rw,
  deny network raw,
  deny network packet,
}
```

- [ ] **Step 2: Commit**

```bash
git add openclaw/apparmor.txt
git commit -m "feat: AppArmor profile with S6 v3 paths and security denials"
```

---

### Task 4: Dockerfile

**Files:**
- Create: `openclaw/Dockerfile`

- [ ] **Step 1: Write the multi-stage Dockerfile**

```dockerfile
# ==============================================================================
# Stage 1: Install OpenClaw from npm using official Node.js image
# ==============================================================================
ARG OPENCLAW_VERSION=2026.4.2

FROM node:24-bookworm-slim AS openclaw-build

ARG OPENCLAW_VERSION

# Install OpenClaw globally — this gives us the openclaw CLI + all dependencies
RUN npm install -g "openclaw@${OPENCLAW_VERSION}" \
    && npm cache clean --force

# ==============================================================================
# Stage 2: Runtime container on HA base image
# ==============================================================================
ARG BUILD_FROM
FROM ${BUILD_FROM}

# Copy Node.js binary from build stage
COPY --from=openclaw-build /usr/local/bin/node /usr/local/bin/node

# Copy OpenClaw and all node_modules from build stage
COPY --from=openclaw-build /usr/local/lib/node_modules /usr/local/lib/node_modules

# Create openclaw symlink in PATH
RUN ln -sf /usr/local/lib/node_modules/openclaw/openclaw.mjs /usr/local/bin/openclaw \
    && chmod +x /usr/local/bin/openclaw

# Install runtime dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        nginx \
        ttyd \
        curl \
        openssl \
        procps \
        jq \
    && rm -rf /var/lib/apt/lists/*

# Create persistent data directories
RUN mkdir -p /data/openclaw

# Copy S6 service definitions, nginx templates, and landing page
COPY rootfs /

# Ensure all S6 scripts are executable (Windows builds lose execute bits)
RUN chmod +x /etc/s6-overlay/scripts/*.sh \
    && find /etc/s6-overlay/s6-rc.d -name "run" -o -name "finish" -o -name "up" | xargs chmod +x
```

- [ ] **Step 2: Commit**

```bash
git add openclaw/Dockerfile
git commit -m "feat: multi-stage Dockerfile (node:24 builder + HA base-debian runtime)"
```

---

### Task 5: S6 Service Definitions (Static Files)

**Files:**
- Create: All files under `openclaw/rootfs/etc/s6-overlay/s6-rc.d/`

These are tiny static files — the S6 v3 service graph definition.

- [ ] **Step 1: Write openclaw-init oneshot**

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-init/type`:
```
oneshot
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-init/up`:
```
/etc/s6-overlay/scripts/openclaw-init.sh
```

- [ ] **Step 2: Write openclaw-gateway longrun**

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-gateway/type`:
```
longrun
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-gateway/run`:
```bash
#!/command/with-contenv bashio
# shellcheck shell=bash
# ==============================================================================
# Start OpenClaw Gateway
# ==============================================================================

declare LOG_LEVEL
LOG_LEVEL=$(bashio::config 'log_level')

# Source generated environment (SUPERVISOR_TOKEN, gateway token, etc.)
if [ -f /data/openclaw/env ]; then
    set -a
    # shellcheck source=/dev/null
    source /data/openclaw/env
    set +a
fi

bashio::log.info "Starting OpenClaw Gateway on port 18789..."

exec node /usr/local/lib/node_modules/openclaw/openclaw.mjs gateway run \
    --port 18789 \
    --bind lan \
    --allow-unconfigured
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-gateway/finish`:
```bash
#!/command/with-contenv bashio
# shellcheck shell=bash
# ==============================================================================
# OpenClaw Gateway finish script
# ==============================================================================

declare EXIT_CODE=${1}

if [ "${EXIT_CODE}" -ne 0 ] && [ "${EXIT_CODE}" -ne 256 ]; then
    bashio::log.error "OpenClaw gateway crashed with exit code ${EXIT_CODE}"
    # Signal S6 to bring down the entire container so Supervisor can restart
    exec /run/s6/basedir/bin/halt
fi
```

Dependency marker (empty file):
`openclaw/rootfs/etc/s6-overlay/s6-rc.d/openclaw-gateway/dependencies.d/openclaw-init`:
(empty file)

- [ ] **Step 3: Write nginx longrun**

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/nginx/type`:
```
longrun
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/nginx/run`:
```bash
#!/command/with-contenv bashio
# shellcheck shell=bash
# ==============================================================================
# Start nginx (ingress reverse proxy)
# ==============================================================================

bashio::log.info "Starting nginx..."
exec nginx -g "daemon off;"
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/nginx/finish`:
```bash
#!/command/with-contenv bashio
# shellcheck shell=bash

bashio::log.warning "nginx exited with code ${1}"
```

Dependency marker (empty file):
`openclaw/rootfs/etc/s6-overlay/s6-rc.d/nginx/dependencies.d/openclaw-init`:
(empty file)

- [ ] **Step 4: Write ttyd longrun (conditional)**

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/ttyd/type`:
```
longrun
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/ttyd/run`:
```bash
#!/command/with-contenv bashio
# shellcheck shell=bash
# ==============================================================================
# Start ttyd web terminal (conditional)
# ==============================================================================

if ! bashio::config.true 'enable_terminal'; then
    bashio::log.info "Terminal is disabled. Sleeping."
    exec sleep infinity
fi

bashio::log.info "Starting ttyd web terminal..."
exec ttyd \
    --writable \
    --interface 127.0.0.1 \
    --port 7681 \
    --base-path /terminal \
    bash
```

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/ttyd/finish`:
```bash
#!/command/with-contenv bashio
# shellcheck shell=bash

if ! bashio::config.true 'enable_terminal'; then
    # Exit 256 tells S6 not to restart a disabled service
    exit 256
fi

bashio::log.warning "ttyd exited with code ${1}"
```

Dependency marker (empty file):
`openclaw/rootfs/etc/s6-overlay/s6-rc.d/ttyd/dependencies.d/openclaw-init`:
(empty file)

- [ ] **Step 5: Write user bundle markers**

These are empty files that tell S6 which services to start:

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/user/contents.d/openclaw-gateway`:
(empty file)

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/user/contents.d/nginx`:
(empty file)

`openclaw/rootfs/etc/s6-overlay/s6-rc.d/user/contents.d/ttyd`:
(empty file)

- [ ] **Step 6: Commit**

```bash
git add openclaw/rootfs/etc/s6-overlay/s6-rc.d/
git commit -m "feat: S6 v3 service graph (init, gateway, nginx, ttyd)"
```

---

### Task 6: Init Script

**Files:**
- Create: `openclaw/rootfs/etc/s6-overlay/scripts/openclaw-init.sh`

This is the most critical file — it generates secrets, writes config, and renders the nginx template.

- [ ] **Step 1: Write the init script**

```bash
#!/command/with-contenv bashio
# shellcheck shell=bash
# ==============================================================================
# OpenClaw Init: generate token, write config, render nginx template
# ==============================================================================

# --------------------------------------------------------------------------
# 1. Read HA options
# --------------------------------------------------------------------------

declare LOG_LEVEL
LOG_LEVEL=$(bashio::config 'log_level')

declare ENABLE_TERMINAL
ENABLE_TERMINAL=$(bashio::config 'enable_terminal')

# --------------------------------------------------------------------------
# 2. Generate or load gateway token
# --------------------------------------------------------------------------

declare TOKEN_FILE="/data/openclaw/gateway_token"
declare GATEWAY_TOKEN

if [ -f "${TOKEN_FILE}" ]; then
    GATEWAY_TOKEN=$(cat "${TOKEN_FILE}")
    bashio::log.info "Using existing gateway token."
else
    GATEWAY_TOKEN=$(openssl rand -hex 32)
    echo "${GATEWAY_TOKEN}" > "${TOKEN_FILE}"
    chmod 600 "${TOKEN_FILE}"
    bashio::log.info "Generated new gateway token."
fi

# --------------------------------------------------------------------------
# 3. Resolve HA URL for allowedOrigins
# --------------------------------------------------------------------------

declare HA_URL=""
if bashio::supervisor.ping 2>/dev/null; then
    # Try external_url first, fall back to internal_url
    HA_URL=$(bashio::api.supervisor GET /core/api/config false \
        | jq -r '.external_url // .internal_url // empty' 2>/dev/null) || true
fi

if [ -z "${HA_URL}" ]; then
    bashio::log.warning "Could not determine HA URL for allowedOrigins. Control UI CORS may fail."
fi

# --------------------------------------------------------------------------
# 4. Write openclaw.json (first boot only)
# --------------------------------------------------------------------------

declare CONFIG_DIR="/config/.openclaw"
declare CONFIG_FILE="${CONFIG_DIR}/openclaw.json"

mkdir -p "${CONFIG_DIR}"

if [ ! -f "${CONFIG_FILE}" ]; then
    bashio::log.info "First boot — generating openclaw.json..."

    # Build allowed origins array
    declare ORIGINS="[]"
    if [ -n "${HA_URL}" ]; then
        ORIGINS=$(jq -n --arg url "${HA_URL}" '[$url]')
    fi

    jq -n \
        --arg token "${GATEWAY_TOKEN}" \
        --argjson origins "${ORIGINS}" \
    '{
        gateway: {
            port: 18789,
            mode: "local",
            bind: "lan",
            auth: {
                mode: "token",
                token: $token
            },
            trustedProxies: ["127.0.0.1"],
            controlUi: {
                allowedOrigins: $origins
            },
            http: {
                endpoints: {
                    chatCompletions: {
                        enabled: true
                    }
                }
            }
        },
        update: {
            channel: "stable"
        }
    }' > "${CONFIG_FILE}"

    bashio::log.info "Written ${CONFIG_FILE}"
else
    bashio::log.info "Existing openclaw.json found — patching HA-managed keys only."

    # Patch allowedOrigins (HA URL may have changed)
    if [ -n "${HA_URL}" ]; then
        local TMP_FILE
        TMP_FILE=$(mktemp)
        jq --arg url "${HA_URL}" \
            '.gateway.controlUi.allowedOrigins = [$url]' \
            "${CONFIG_FILE}" > "${TMP_FILE}" \
            && mv "${TMP_FILE}" "${CONFIG_FILE}"
    fi

    # Ensure chatCompletions stays enabled (integration needs it)
    local TMP_FILE2
    TMP_FILE2=$(mktemp)
    jq '.gateway.http.endpoints.chatCompletions.enabled = true' \
        "${CONFIG_FILE}" > "${TMP_FILE2}" \
        && mv "${TMP_FILE2}" "${CONFIG_FILE}"
fi

# --------------------------------------------------------------------------
# 5. Write internal env file (regenerated every boot)
# --------------------------------------------------------------------------

declare ENV_FILE="/data/openclaw/env"

{
    echo "OPENCLAW_STATE_DIR=/config/.openclaw"
    echo "OPENCLAW_CONFIG_PATH=/config/.openclaw/openclaw.json"
    echo "OPENCLAW_GATEWAY_TOKEN=${GATEWAY_TOKEN}"
    echo "SUPERVISOR_TOKEN=${SUPERVISOR_TOKEN}"
    echo "NODE_OPTIONS=--max-old-space-size=2048"
} > "${ENV_FILE}"
chmod 600 "${ENV_FILE}"

# --------------------------------------------------------------------------
# 6. Render nginx config from tempio template
# --------------------------------------------------------------------------

declare INGRESS_INTERFACE
INGRESS_INTERFACE=$(bashio::addon.ip_address)

declare INGRESS_PORT
INGRESS_PORT=$(bashio::addon.ingress_port)

bashio::log.info "Rendering nginx config (${INGRESS_INTERFACE}:${INGRESS_PORT})..."

bashio::var.json \
    interface "${INGRESS_INTERFACE}" \
    port "^${INGRESS_PORT}" \
    token "${GATEWAY_TOKEN}" \
    terminal_enabled "${ENABLE_TERMINAL}" \
    | tempio \
        -template /etc/nginx/servers/ingress.conf.gtpl \
        -out /etc/nginx/servers/ingress.conf

# --------------------------------------------------------------------------
# 7. Write landing page config (terminal visibility)
# --------------------------------------------------------------------------

if bashio::config.true 'enable_terminal'; then
    echo '{"terminal_enabled":true}' > /app/www/config.json
else
    echo '{"terminal_enabled":false}' > /app/www/config.json
fi

# --------------------------------------------------------------------------
# Done
# --------------------------------------------------------------------------

bashio::log.info "OpenClaw initialization complete."
```

- [ ] **Step 2: Commit**

```bash
git add openclaw/rootfs/etc/s6-overlay/scripts/openclaw-init.sh
git commit -m "feat: init script (token gen, config, nginx render)"
```

---

### Task 7: nginx Ingress Template

**Files:**
- Create: `openclaw/rootfs/etc/nginx/servers/ingress.conf.gtpl`

- [ ] **Step 1: Write the tempio template**

Uses Go `text/template` syntax (`{{ .var }}`), NOT `%%var%%`.

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen {{ .interface }}:{{ .port }} default_server;

    # Landing page (static HTML)
    location = / {
        root /app/www;
        index index.html;
    }

    location /static/ {
        alias /app/www/;
        expires 1h;
    }

    location /config.json {
        alias /app/www/config.json;
    }

    # Gateway UI — pre-authenticated via server-side token injection
    location /gateway/ {
        proxy_pass http://127.0.0.1:18789/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Authorization "Bearer {{ .token }}";
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
        proxy_buffering off;
    }

    # Terminal (ttyd) — only proxied when enabled
    {{ if eq .terminal_enabled "true" }}
    location /terminal/ {
        proxy_pass http://127.0.0.1:7681/terminal/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
    }
    {{ end }}
}
```

- [ ] **Step 2: Commit**

```bash
git add openclaw/rootfs/etc/nginx/servers/ingress.conf.gtpl
git commit -m "feat: nginx ingress template with token injection and WS proxy"
```

---

### Task 8: Landing Page

**Files:**
- Create: `openclaw/rootfs/app/www/index.html`
- Create: `openclaw/rootfs/app/www/style.css`

- [ ] **Step 1: Write the HTML landing page**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>OpenClaw</title>
    <link rel="stylesheet" href="/static/style.css">
</head>
<body>
    <nav id="tabs">
        <button class="tab active" data-target="gateway">Gateway</button>
        <button class="tab" data-target="terminal" id="tab-terminal" style="display:none">Terminal</button>
    </nav>
    <div id="content">
        <iframe id="frame-gateway" class="frame active" src="./gateway/"></iframe>
        <iframe id="frame-terminal" class="frame" src="about:blank"></iframe>
    </div>
    <script>
        (function() {
            var tabs = document.querySelectorAll('.tab');
            var frames = document.querySelectorAll('.frame');
            var active = localStorage.getItem('openclaw-tab') || 'gateway';

            function switchTab(name) {
                tabs.forEach(function(t) { t.classList.toggle('active', t.dataset.target === name); });
                frames.forEach(function(f) { f.classList.toggle('active', f.id === 'frame-' + name); });
                // Lazy-load terminal iframe on first switch
                var tf = document.getElementById('frame-terminal');
                if (name === 'terminal' && tf.src === 'about:blank') {
                    tf.src = './terminal/';
                }
                localStorage.setItem('openclaw-tab', name);
            }

            tabs.forEach(function(t) {
                t.addEventListener('click', function() { switchTab(t.dataset.target); });
            });

            // Load config to check terminal visibility
            fetch('/config.json').then(function(r) { return r.json(); }).then(function(cfg) {
                if (cfg.terminal_enabled) {
                    document.getElementById('tab-terminal').style.display = '';
                }
                // Restore last active tab
                if (cfg.terminal_enabled || active === 'gateway') {
                    switchTab(active);
                }
            }).catch(function() {
                // Config not available — just show gateway
                switchTab('gateway');
            });
        })();
    </script>
</body>
</html>
```

- [ ] **Step 2: Write CSS**

```css
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { height: 100%; background: #1c1c1c; color: #e0e0e0; font-family: system-ui, sans-serif; }
#tabs { display: flex; gap: 2px; background: #111; padding: 4px 8px 0; }
.tab {
    padding: 8px 20px;
    background: #2a2a2a;
    color: #aaa;
    border: none;
    border-radius: 6px 6px 0 0;
    cursor: pointer;
    font-size: 14px;
}
.tab.active { background: #1c1c1c; color: #fff; }
.tab:hover { color: #fff; }
#content { position: relative; height: calc(100% - 40px); }
.frame { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: none; display: none; }
.frame.active { display: block; }
```

- [ ] **Step 3: Commit**

```bash
git add openclaw/rootfs/app/www/
git commit -m "feat: tabbed landing page (Gateway + Terminal)"
```

---

### Task 9: Translations

**Files:**
- Create: `openclaw/translations/en.yaml`

- [ ] **Step 1: Write translations**

```yaml
configuration:
  log_level:
    name: Log Level
    description: Gateway and add-on log verbosity
  gateway_port:
    name: Gateway Port
    description: Internal gateway port (default 18789, rarely needs changing)
  enable_terminal:
    name: Enable Web Terminal
    description: Show a terminal tab in the sidebar panel for shell access
  enable_ha_mcp:
    name: Enable HA MCP
    description: Start mcporter daemon for Home Assistant MCP tool access (requires mcp_server integration in HA)
```

- [ ] **Step 2: Commit**

```bash
git add openclaw/translations/
git commit -m "feat: English translations for config options"
```

---

### Task 10: DOCS.md and CHANGELOG.md

**Files:**
- Create: `openclaw/DOCS.md`
- Create: `openclaw/CHANGELOG.md`

- [ ] **Step 1: Write DOCS.md**

```markdown
# OpenClaw Add-on

Local-first personal AI gateway connecting 24+ messaging platforms to AI agents.

## Quick Start

1. Install the add-on
2. Start it — a gateway token is auto-generated
3. Open the sidebar panel to access the Gateway UI
4. Configure your agents, channels, and API keys in the Control UI

## Configuration

All OpenClaw configuration (agents, channels, models, API keys) is managed through
the **Gateway Control UI** in the sidebar panel, or by editing
`/addon_configs/<slug>/.openclaw/openclaw.json` directly via File Editor.

The add-on options below control only the add-on infrastructure:

| Option | Default | Description |
|--------|---------|-------------|
| `log_level` | `info` | Log verbosity (trace/debug/info/notice/warning/error/fatal) |
| `gateway_port` | `18789` | Internal gateway port |
| `enable_terminal` | `false` | Enable web terminal tab in sidebar |
| `enable_ha_mcp` | `true` | Enable HA MCP tools for AI agents |

## Companion Integration

For HA entities and services, install the
[OpenClaw Integration](https://github.com/techartdev/OpenClawHomeAssistantIntegration).

The integration needs direct access to port 18789. Enable it in the add-on's
**Network** settings by setting the Gateway API port.

## HA MCP Tools

To give AI agents control over your smart home:

1. In HA: Settings > Devices & Services > Add Integration > **Model Context Protocol Server**
2. Select the **Assist** API
3. Configure which entities are exposed: Settings > Voice Assistants > Expose

## Data Locations

- **User config**: `/addon_configs/<slug>/.openclaw/openclaw.json`
- **Workspaces**: `/addon_configs/<slug>/` (top-level directories)
- **Internal state**: `/data/openclaw/` (managed by add-on, do not edit)

## Security

- The gateway token is auto-generated and stored internally
- The sidebar panel is protected by HA authentication
- The terminal (when enabled) provides shell access to the container
- AI agent access to HA is controlled by HA's entity exposure settings
```

- [ ] **Step 2: Write CHANGELOG.md**

```markdown
# Changelog

## 0.1.0

- Initial release
- S6 v3 service management
- Ingress with tabbed landing page (Gateway UI + Terminal)
- Auto-generated gateway token
- AppArmor security profile
- Watchdog health monitoring
- Cold backup support
- OpenClaw 2026.4.2
```

- [ ] **Step 3: Commit**

```bash
git add openclaw/DOCS.md openclaw/CHANGELOG.md
git commit -m "docs: add-on documentation and changelog"
```

---

### Task 11: Ingress Gate Test

**This is the Phase 1 GATE.** Before proceeding to polish (Phase 4), we must verify that the OpenClaw Control UI works through the nginx ingress proxy with WebSocket.

- [ ] **Step 1: Build and install the add-on on HA**

```bash
# From HA: Settings > Add-ons > Add-on Store > ⋮ > Repositories
# Add: https://github.com/bonzanni/openclaw-ha-app
# Or for local development, copy the openclaw/ directory to /addons/openclaw/
```

- [ ] **Step 2: Start the add-on and open the sidebar panel**

Expected: The tabbed landing page loads. The Gateway tab shows the OpenClaw Control UI.

- [ ] **Step 3: Verify WebSocket connectivity**

In the browser dev tools (Network > WS tab), check that a WebSocket connection is established through the ingress proxy path:

```
wss://<ha-host>/api/hassio_ingress/<token>/gateway/
```

**If WS works (GATE PASSES):**
- Proceed to Phase 4 (Polish)
- The ingress design is confirmed

**If WS fails (GATE FAILS):**
- Check browser console for errors
- Check if Control UI hardcodes `ws://127.0.0.1:18789` (look in Network tab)
- If hardcoded: add a "Open in new tab" fallback button to the landing page linking to `http://<host-ip>:18789`
- Document this as compatibility mode in DOCS.md

- [ ] **Step 4: Verify health endpoint**

```bash
# From inside HA terminal or SSH:
curl -s http://<addon-ip>:18789/healthz
```

Expected: `{"ok":true,"status":"live"}`

- [ ] **Step 5: Verify cold backup**

1. Settings > System > Backups > Create Backup (include the add-on)
2. Verify the add-on stopped during backup and restarted after
3. Verify `/config/.openclaw/openclaw.json` survived the cycle

- [ ] **Step 6: Record gate result and commit any fixes**

```bash
git add -A
git commit -m "test: ingress gate - [PASS/FAIL] - [notes]"
```

---

### Task 12: Polish and Scope A Exit

Final cleanup before Scope A is complete.

- [ ] **Step 1: Add placeholder icon and logo**

Create `openclaw/icon.png` (128x128) and `openclaw/logo.png` (250x100). Use simple placeholder images for now — can be replaced with proper branding later.

- [ ] **Step 2: Run full Scope A test matrix**

| # | Test | Expected | Result |
|---|---|---|---|
| 1 | Add-on starts under S6, all services up | success | |
| 2 | Restart preserves `/config/.openclaw/openclaw.json` and workspaces | success | |
| 3 | Landing page loads in HA sidebar | success | |
| 4 | Gateway UI loads via nginx `/gateway/` path | success | |
| 5 | Control UI WS connection works behind ingress | **gate** | |
| 6 | Bearer token auth to gateway works in token mode | success | |
| 7 | `/healthz` returns `{"ok":true}` | success | |
| 8 | Watchdog auto-restarts on gateway crash | success | |
| 9 | No `host_network`, no `dangerouslyDisableDeviceAuth` | success | |
| 10 | ttyd disabled by default, hidden when off | success | |
| 11 | Cold backup/restore preserves config | success | |
| 12 | First boot generates token, writes `openclaw.json` | success | |
| 13 | Subsequent boot does NOT overwrite user changes | success | |

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "release: OpenClaw HA add-on v0.1.0 (Scope A complete)"
```
