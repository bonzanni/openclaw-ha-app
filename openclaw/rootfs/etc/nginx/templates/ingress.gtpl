server {
    listen {{ .interface }}:{{ .port }} default_server;

    # Proxy settings (shared)
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

    # Init page: served on first visit to inject gatewayUrl + token
    location = /__init__ {
        internal;
        alias /app/www/index.html;
    }

    # Root: if no gatewayUrl param, serve init page; otherwise proxy to gateway
    location = / {
        if ($arg_gatewayUrl = '') {
            rewrite ^ /__init__ last;
        }
        proxy_pass http://127.0.0.1:18789;
    }

    # Everything else: proxy to gateway
    location / {
        proxy_pass http://127.0.0.1:18789;
    }

    # Terminal (ttyd)
    {{ if .terminal_enabled }}
    location /terminal/ {
        proxy_pass http://127.0.0.1:7681/terminal/;
    }
    {{ end }}
}
