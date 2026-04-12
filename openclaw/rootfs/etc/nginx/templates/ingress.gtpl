server {
    listen {{ .interface }}:{{ .port }} default_server;

    # Setup page: redirect (terminal off) or tabbed UI (terminal on)
    location = /__setup {
        default_type text/html;
        add_header Cache-Control "no-store, no-cache, must-revalidate";
        alias /app/www/__setup/index.html;
    }

    # Setup config (terminal enabled flag)
    location = /__setup_config.json {
        default_type application/json;
        add_header Cache-Control "no-store";
        alias /data/openclaw/setup_config.json;
    }

    # Proxy everything to the OpenClaw Gateway (pre-authenticated)
    location / {
        proxy_pass http://127.0.0.1:18789;
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
        proxy_hide_header Content-Security-Policy;
        proxy_hide_header X-Frame-Options;
    }

    # Terminal (ttyd)
    {{ if .terminal_enabled }}
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
