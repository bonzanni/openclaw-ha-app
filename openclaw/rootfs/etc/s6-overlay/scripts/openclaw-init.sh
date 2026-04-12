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

declare TOKEN_DIR="/data/openclaw"
declare TOKEN_FILE="${TOKEN_DIR}/gateway_token"
declare GATEWAY_TOKEN

mkdir -p "${TOKEN_DIR}"

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
# 3. Resolve HA external URL for allowedOrigins
# --------------------------------------------------------------------------

declare HA_URL=""
if bashio::supervisor.ping 2>/dev/null; then
    HA_URL=$(curl -fsS \
        -H "Authorization: Bearer ${SUPERVISOR_TOKEN}" \
        "http://supervisor/core/api/config" \
        | jq -r '.external_url // .internal_url // empty' 2>/dev/null) || true
fi

# Strip trailing slash
HA_URL="${HA_URL%/}"

bashio::log.info "HA URL for allowedOrigins: ${HA_URL:-not available}"

# --------------------------------------------------------------------------
# 4. Write openclaw.json (first boot only)
# --------------------------------------------------------------------------

declare CONFIG_DIR="/config/.openclaw"
declare CONFIG_FILE="${CONFIG_DIR}/openclaw.json"

mkdir -p "${CONFIG_DIR}"

if [ ! -f "${CONFIG_FILE}" ]; then
    bashio::log.info "First boot — generating openclaw.json..."

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
                allowedOrigins: $origins,
                dangerouslyDisableDeviceAuth: true,
                allowInsecureAuth: true
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
        declare TMP_FILE
        TMP_FILE=$(mktemp)
        jq --arg url "${HA_URL}" \
            '.gateway.controlUi.allowedOrigins = [$url]' \
            "${CONFIG_FILE}" > "${TMP_FILE}" \
            && mv "${TMP_FILE}" "${CONFIG_FILE}"
    fi

    # Ensure chatCompletions stays enabled (integration needs it)
    declare TMP_FILE2
    TMP_FILE2=$(mktemp)
    jq '.gateway.http.endpoints.chatCompletions.enabled = true' \
        "${CONFIG_FILE}" > "${TMP_FILE2}" \
        && mv "${TMP_FILE2}" "${CONFIG_FILE}"
fi

# --------------------------------------------------------------------------
# 5. Write setup page config (terminal visibility)
# --------------------------------------------------------------------------

if bashio::config.true 'enable_terminal'; then
    echo '{"terminal_enabled":true}' > /data/openclaw/setup_config.json
else
    echo '{"terminal_enabled":false}' > /data/openclaw/setup_config.json
fi

# --------------------------------------------------------------------------
# 6. Configure mcporter for HA MCP (regenerated every boot)
# --------------------------------------------------------------------------

if bashio::config.true 'enable_ha_mcp'; then
    declare MCPORTER_DIR="/config/.mcporter"
    declare MCPORTER_CONFIG="${MCPORTER_DIR}/mcporter.json"

    mkdir -p "${MCPORTER_DIR}"

    jq -n --arg token "${SUPERVISOR_TOKEN}" '{
        mcpServers: {
            HA: {
                baseUrl: "http://supervisor/core/api/mcp",
                headers: {
                    Authorization: ("Bearer " + $token)
                }
            }
        },
        imports: []
    }' > "${MCPORTER_CONFIG}"
    chmod 600 "${MCPORTER_CONFIG}"

    bashio::log.info "mcporter configured for HA MCP at http://supervisor/core/api/mcp"
else
    bashio::log.info "HA MCP disabled."
fi

# --------------------------------------------------------------------------
# 7. Write internal env file (regenerated every boot)
# --------------------------------------------------------------------------

declare ENV_FILE="/data/openclaw/env"

{
    echo "OPENCLAW_STATE_DIR=/config/.openclaw"
    echo "OPENCLAW_CONFIG_PATH=/config/.openclaw/openclaw.json"
    echo "OPENCLAW_GATEWAY_TOKEN=${GATEWAY_TOKEN}"
    echo "NODE_OPTIONS=--max-old-space-size=2048"
} > "${ENV_FILE}"
chmod 600 "${ENV_FILE}"

# --------------------------------------------------------------------------
# 8. Render nginx config from tempio template
# --------------------------------------------------------------------------

declare INGRESS_INTERFACE
INGRESS_INTERFACE=$(bashio::addon.ip_address)

declare INGRESS_PORT
INGRESS_PORT=$(bashio::addon.ingress_port)

bashio::log.info "Rendering nginx ingress config (${INGRESS_INTERFACE}:${INGRESS_PORT})..."

bashio::var.json \
    interface "${INGRESS_INTERFACE}" \
    port "^${INGRESS_PORT}" \
    token "${GATEWAY_TOKEN}" \
    terminal_enabled "${ENABLE_TERMINAL}" \
    | tempio \
        -template /etc/nginx/templates/ingress.gtpl \
        -out /etc/nginx/servers/ingress.conf

# Validate nginx config
if ! nginx -t 2>&1; then
    bashio::log.error "nginx config validation failed"
    cat /etc/nginx/servers/ingress.conf
    exit 1
fi
bashio::log.info "nginx config validated."

# --------------------------------------------------------------------------
# Done
# --------------------------------------------------------------------------

bashio::log.info "OpenClaw initialization complete."
