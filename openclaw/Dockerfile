# ==============================================================================
# Stage 1: Install OpenClaw from npm using official Node.js image
# ==============================================================================
ARG OPENCLAW_VERSION=2026.4.11

FROM node:24-bookworm-slim AS openclaw-build

ARG OPENCLAW_VERSION

# Install OpenClaw globally — this gives us the openclaw CLI + all dependencies
RUN npm install -g "openclaw@${OPENCLAW_VERSION}" mcporter \
    && npm cache clean --force

# ==============================================================================
# Stage 2: Runtime container on HA base image
# ==============================================================================
FROM ghcr.io/home-assistant/base-debian:trixie

# Copy Node.js runtime from build stage
COPY --from=openclaw-build /usr/local/bin/node /usr/local/bin/node
COPY --from=openclaw-build /usr/local/lib/node_modules /usr/local/lib/node_modules

# Create symlinks (npm/npx are symlinks in the build stage — recreate them)
RUN ln -sf /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && ln -sf /usr/local/lib/node_modules/openclaw/openclaw.mjs /usr/local/bin/openclaw \
    && chmod +x /usr/local/bin/openclaw \
    && ln -sf /usr/local/lib/node_modules/mcporter/dist/cli.js /usr/local/bin/mcporter \
    && chmod +x /usr/local/bin/mcporter

# Install runtime dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        nginx \
        curl \
        openssl \
        procps \
        jq \
        git \
        micro \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /etc/nginx/sites-enabled /etc/nginx/sites-available /etc/nginx/conf.d \
    && rm -rf /usr/share/nginx/html \
    && mkdir -p /etc/nginx/servers

# Install ttyd (not packaged in Debian)
ARG TTYD_VERSION=1.7.7
RUN ARCH="$(dpkg --print-architecture)" \
    && if [ "$ARCH" = "amd64" ]; then TTYD_ARCH="x86_64"; \
       elif [ "$ARCH" = "arm64" ]; then TTYD_ARCH="aarch64"; \
       else echo "Unsupported arch: $ARCH"; exit 1; fi \
    && curl -fsSL "https://github.com/tsl0922/ttyd/releases/download/${TTYD_VERSION}/ttyd.${TTYD_ARCH}" \
        -o /usr/bin/ttyd \
    && chmod +x /usr/bin/ttyd

# Install bundled plugin runtime deps (OpenClaw needs these at startup)
RUN mkdir -p /tmp/openclaw-init \
    && cd /tmp/openclaw-init \
    && OPENCLAW_STATE_DIR=/tmp/openclaw-init node /usr/local/lib/node_modules/openclaw/openclaw.mjs doctor --fix --non-interactive 2>&1 || true \
    && rm -rf /tmp/openclaw-init

# Create persistent data directories
RUN mkdir -p /data/openclaw

# Copy S6 service definitions, nginx templates, and landing page
COPY rootfs /

# Ensure all S6 scripts are executable (Windows builds lose execute bits)
RUN chmod +x /etc/s6-overlay/scripts/*.sh \
    && find /etc/s6-overlay/s6-rc.d -name "run" -o -name "finish" -o -name "up" | xargs chmod +x
