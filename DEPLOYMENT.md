# 🚀 Soil Health Monitoring System - Deployment Guide

## Quick Deploy with Docker (Recommended)

### 1. Prerequisites
- Docker 20.x or higher
- Docker Compose 2.x or higher
- 4GB RAM minimum
- 10GB disk space

### 2. Clone and Configure

```bash
# Clone repository
git clone https://github.com/yourusername/soil-health-monitor.git
cd soil-health-monitor

# Create environment file
cat > .env << 'EOF'
# Database Configuration
DB_USER=ishm
DB_PASSWORD=your_secure_password_here

# JWT Security (IMPORTANT: Change this!)
JWT_SECRET=your-super-secret-jwt-key-minimum-256-bits-please-change-this-now

# Data.gov.in Integration (Optional)
DATA_GOV_API_KEY=your_api_key_here
SOIL_HEALTH_RESOURCE_ID=your_resource_id
DATA_GOV_API_ENABLED=false

# CORS Configuration
CORS_ALLOWED_ORIGINS=http://localhost:8080,https://yourdomain.com

# Logging
LOG_LEVEL_APP=INFO
EOF

# Secure the environment file
chmod 600 .env
```

### 3. Deploy

```bash
# Start services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f app

# Wait for application to be ready (check health)
curl http://localhost:8080/health
```

### 4. Access Application

- **Main Application**: http://localhost:8080
- **Health Check**: http://localhost:8080/health
- **API Base**: http://localhost:8080/api

### 5. Test Authentication

```bash
# Register new user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testfarmer",
    "password": "password123",
    "postalCode": "110001",
    "fullName": "Test Farmer",
    "phone": "9876543210"
  }'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testfarmer",
    "password": "password123"
  }'

# Save the token from response
export TOKEN="your_jwt_token_here"

# Access protected endpoint
curl http://localhost:8080/api/dashboard/summary \
  -H "Authorization: Bearer $TOKEN"
```

## Manual Deployment (Without Docker)

### 1. Install Dependencies

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y openjdk-17-jdk postgresql-14 postgresql-14-postgis-3

# macOS (using Homebrew)
brew install openjdk@17 postgresql postgis

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### 2. Setup Database

```bash
# Start PostgreSQL
sudo systemctl start postgresql

# Create database and user
sudo -u postgres psql << EOF
CREATE DATABASE ishm;
CREATE USER ishm WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE ishm TO ishm;
\c ishm
CREATE EXTENSION IF NOT EXISTS postgis;
EOF

# Import schema
psql -U ishm -d ishm -f schema-enhanced.sql
```

### 3. Build Application

```bash
# Build JAR
./gradlew clean build

# The JAR will be in build/libs/
ls -lh build/libs/*.jar
```

### 4. Run Application

```bash
# Set environment variables
export DB_URL="jdbc:postgresql://localhost:5432/ishm"
export DB_USER="ishm"
export DB_PASSWORD="your_password"
export JWT_SECRET="your-super-secret-jwt-key-change-this"

# Run application
java -jar build/libs/soil-health-monitor-1.0.0.jar
```

## Production Deployment

### Option 1: Docker with Nginx

```bash
# Start with production profile
docker-compose --profile production up -d

# This includes:
# - Application (port 8080)
# - PostgreSQL (port 5432)
# - Nginx reverse proxy (ports 80, 443)
```

### Option 2: Systemd Service

```bash
# Create service file
sudo tee /etc/systemd/system/soil-health.service << 'EOF'
[Unit]
Description=Soil Health Monitoring System
After=postgresql.service

[Service]
Type=simple
User=appuser
WorkingDirectory=/opt/soil-health
ExecStart=/usr/bin/java -jar /opt/soil-health/soil-health-monitor-1.0.0.jar
Restart=always
RestartSec=10

Environment="DB_URL=jdbc:postgresql://localhost:5432/ishm"
Environment="DB_USER=ishm"
Environment="DB_PASSWORD=your_password"
Environment="JWT_SECRET=your_jwt_secret"

[Install]
WantedBy=multi-user.target
EOF

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable soil-health
sudo systemctl start soil-health

# Check status
sudo systemctl status soil-health

# View logs
sudo journalctl -u soil-health -f
```

### Option 3: Kubernetes

```yaml
# k8s-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: soil-health-app
spec:
  replicas: 3
  selector:
    matchLabels:
      app: soil-health
  template:
    metadata:
      labels:
        app: soil-health
    spec:
      containers:
      - name: app
        image: soil-health-monitor:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: DB_URL
          valueFrom:
            secretKeyRef:
              name: soil-health-secrets
              key: db-url
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: soil-health-secrets
              key: jwt-secret
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: soil-health-service
spec:
  selector:
    app: soil-health
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
```

Deploy:
```bash
kubectl apply -f k8s-deployment.yaml
kubectl get pods -l app=soil-health
```

## Monitoring Setup (Optional)

### Enable Prometheus & Grafana

```bash
# Start with monitoring profile
docker-compose --profile monitoring up -d

# Access services
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000 (admin/admin)
```

### Configure Grafana Dashboard

1. Login to Grafana (http://localhost:3000)
2. Add Prometheus data source: http://prometheus:9090
3. Import dashboard from `grafana-dashboard.json`
4. View metrics:
    - Request rates
    - Error rates
    - Database connections
    - JVM metrics

## SSL/TLS Configuration

### Using Let's Encrypt with Certbot

```bash
# Install Certbot
sudo apt install -y certbot python3-certbot-nginx

# Generate certificate
sudo certbot --nginx -d yourdomain.com -d www.yourdomain.com

# Auto-renewal
sudo certbot renew --dry-run
```

### Nginx Configuration with SSL

```nginx
# /etc/nginx/sites-available/soil-health
server {
    listen 80;
    server_name yourdomain.com www.yourdomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name yourdomain.com www.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## Backup Strategy

### Database Backup

```bash
# Create backup script
cat > backup-db.sh << 'EOF'
#!/bin/bash
BACKUP_DIR="/var/backups/soil-health"
DATE=$(date +%Y%m%d_%H%M%S)
mkdir -p $BACKUP_DIR

# Backup database
pg_dump -U ishm ishm | gzip > $BACKUP_DIR/ishm_$DATE.sql.gz

# Keep only last 30 days
find $BACKUP_DIR -name "ishm_*.sql.gz" -mtime +30 -delete

echo "Backup completed: ishm_$DATE.sql.gz"
EOF

chmod +x backup-db.sh

# Schedule with cron (daily at 2 AM)
(crontab -l 2>/dev/null; echo "0 2 * * * /path/to/backup-db.sh") | crontab -
```

### Application Backup

```bash
# Backup configuration and data
tar -czf soil-health-backup-$(date +%Y%m%d).tar.gz \
  /opt/soil-health \
  /etc/systemd/system/soil-health.service \
  .env
```

## Troubleshooting

### Check Application Logs

```bash
# Docker
docker-compose logs -f app

# Systemd
sudo journalctl -u soil-health -n 100 -f

# Direct JAR
tail -f logs/application.log
```

### Database Connection Issues

```bash
# Test connection
psql -U ishm -h localhost -d ishm -c "SELECT version();"

# Check PostGIS
psql -U ishm -d ishm -c "SELECT PostGIS_version();"

# View active connections
psql -U postgres -c "SELECT * FROM pg_stat_activity WHERE datname='ishm';"
```

### Memory Issues

```bash
# Increase JVM memory
export JAVA_OPTS="-Xms1g -Xmx2g"
java $JAVA_OPTS -jar app.jar

# Monitor memory
docker stats soil-health-app
```

### Performance Tuning

```bash
# Database tuning
sudo -u postgres psql ishm << 'EOF'
-- Increase work_mem for complex queries
ALTER SYSTEM SET work_mem = '256MB';

-- Increase shared_buffers
ALTER SYSTEM SET shared_buffers = '512MB';

-- Enable parallel queries
ALTER SYSTEM SET max_parallel_workers_per_gather = 4;

SELECT pg_reload_conf();
EOF
```

## Maintenance

### Update Application

```bash
# Pull latest code
git pull origin main

# Rebuild and deploy
docker-compose build app
docker-compose up -d app

# Or for manual deployment
./gradlew clean build
systemctl restart soil-health
```

### Database Maintenance

```bash
# Vacuum database
psql -U ishm -d ishm -c "VACUUM ANALYZE;"

# Rebuild indexes
psql -U ishm -d ishm -c "REINDEX DATABASE ishm;"

# Update statistics
psql -U ishm -d ishm -c "ANALYZE;"
```

## Support & Resources

- **Documentation**: Check README.md for detailed API docs
- **Issues**: Report bugs on GitHub Issues
- **Email**: support@soilhealth.gov.in
- **Community**: Join our discussion forum

---

**Happy Farming! 🌾**