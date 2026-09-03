# 🐳 Docker Installation

Nexus Cache Orchestrator can be deployed quickly using Docker Compose. The stack automatically starts **Nexus Core**, **Redis**, and **MongoDB** with persistent storage and health checks.

## Requirements

Make sure you have the following installed:

- Docker Desktop
- Docker Compose
- A VNC client for the initial setup

Verify your Docker installation:

```bash
docker --version
docker compose version
```

---

## 🚀 Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/mustafabinguldev/nexus-cache-orchestrator.git
cd nexus-cache-orchestrator
```

### 2. Configure environment variables

Create a `.env` file in the project root:

```env
MONGO_ROOT_USER=nexus
MONGO_ROOT_PASSWORD=your_secure_password
VNC_PASSWORD=your_secure_vnc_password
```

> For production environments, always use strong and unique passwords.

### 3. Build and start

```bash
docker compose up -d --build
```

Docker will build Nexus Cache Orchestrator and start:

- `nexus-core`
- `nexus-redis`
- `nexus-mongo`

Check the status:

```bash
docker compose ps
```

---

## 🖥️ Initial Setup

Nexus Core provides an interactive setup wizard through a VNC connection.

Connect your VNC client to:

```text
localhost:5900
```

Use the password configured with:

```env
VNC_PASSWORD=your_secure_vnc_password
```

During the setup wizard, use the following internal Docker service addresses:

### MongoDB

```text
Host: mongo
Port: 27017
```

Example connection URI:

```text
mongodb://nexus:nexus_pw@mongo:27017/nexus_core_db?authSource=admin
```

### Redis

```text
Host: redis
Port: 6379
```

> Do not use `localhost` for Redis or MongoDB from inside the Nexus Core container. Docker Compose services communicate using their service names.

---

## 🌐 Web Dashboard

After completing the initial setup, open:

```text
http://localhost:8080
```

The web administration dashboard runs on port `8080`.

---

## 🔌 Ports

| Service | Port | Description |
|---|---:|---|
| Web Dashboard | `8080` | Nexus administration dashboard |
| VNC | `5900` | Initial setup environment |
| Redis | `6379` | Redis cache |
| MongoDB | `27017` | MongoDB database |

---

## 📜 Useful Commands

### View container status

```bash
docker compose ps
```

### View all logs

```bash
docker compose logs -f
```

### View Nexus Core logs

```bash
docker compose logs -f nexus-core
```

### Stop all services

```bash
docker compose down
```

### Restart the stack

```bash
docker compose restart
```

### Rebuild after an update

```bash
git pull
docker compose up -d --build
```

---

## 💾 Persistent Data

Redis and MongoDB use Docker volumes to preserve data between container restarts.

```bash
docker compose down
```

This stops and removes the containers while keeping your database data.

To completely reset the installation and delete all persistent data:

```bash
docker compose down -v
```

> ⚠️ **Warning:** This permanently deletes Redis and MongoDB data.

---

## 🔐 Security

Before deploying to production:

- Change the default MongoDB credentials.
- Use a strong `VNC_PASSWORD`.
- Avoid exposing Redis and MongoDB publicly unless required.
- Restrict VNC access using a firewall or VPN.
- Do not commit production credentials to Git.
- Protect the web dashboard with HTTPS and proper access controls.