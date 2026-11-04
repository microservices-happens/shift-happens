#!/usr/bin/env bash
# Point Testcontainers at whatever Docker the `docker` CLI uses (Docker Desktop, Colima, ...).
# Testcontainers does not read `docker context`, so with Colima it finds no Docker on its own.
#
# Usage (macOS/Linux), then run Maven in the same shell:
#   source scripts/docker-env.sh
#   ./mvnw -B -f services/identity-service/pom.xml verify
# Windows with Docker Desktop needs nothing.

host="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"
if [ -z "$host" ]; then
    echo "docker-env: no active Docker context; is Docker or Colima running?" >&2
    return 1 2>/dev/null || exit 1
fi
export DOCKER_HOST="$host"

# Colima's socket lives on the Mac, but containers (Ryuk) must mount the path inside the VM.
case "$host" in
    *colima*) export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ;;
    *) unset TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE ;;
esac

echo "docker-env: DOCKER_HOST=$DOCKER_HOST"
