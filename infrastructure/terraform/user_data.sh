#!/bin/bash
# EC2 bootstrap — runs once on first boot as root
# Logs to /var/log/expense-bootstrap.log
set -euo pipefail
exec >> /var/log/expense-bootstrap.log 2>&1

echo "==> [1/5] Updating OS packages..."
apt-get update -y
apt-get upgrade -y

echo "==> [2/5] Installing Docker Engine..."
apt-get install -y ca-certificates curl gnupg iptables-persistent

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | tee /etc/apt/sources.list.d/docker.list > /dev/null

apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

usermod -aG docker ubuntu
systemctl enable docker
systemctl start docker

echo "==> [3/5] Opening firewall ports 80 and 443..."
iptables -I INPUT -p tcp --dport 80  -j ACCEPT
iptables -I INPUT -p tcp --dport 443 -j ACCEPT
netfilter-persistent save

echo "==> [4/5] Cloning app repos..."
mkdir -p /opt/expense-app
chown ubuntu:ubuntu /opt/expense-app

sudo -u ubuntu git clone \
  https://github.com/PratapVarmaDandu/receipt-tracker-backend.git \
  /opt/expense-app/receipt-tracker-backend

sudo -u ubuntu git clone \
  https://github.com/PratapVarmaDandu/receipt-tracker-frontend.git \
  /opt/expense-app/receipt-tracker-frontend

# Sync docker-compose file from backend repo to app root
cp /opt/expense-app/receipt-tracker-backend/docker-compose.oracle.yml \
   /opt/expense-app/docker-compose.oracle.yml
chown ubuntu:ubuntu /opt/expense-app/docker-compose.oracle.yml

echo "==> [5/5] Installing Certbot for SSL..."
apt-get install -y certbot
mkdir -p /var/www/certbot
chown -R ubuntu:ubuntu /var/www/certbot

echo ""
echo "==> Bootstrap complete!"
echo "    SSH in and create /opt/expense-app/.env, then run:"
echo "    docker compose -f /opt/expense-app/docker-compose.oracle.yml up -d"
