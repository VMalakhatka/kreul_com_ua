#!/usr/bin/env bash
set -euo pipefail

# ===== Defaults (можно переопределять через ENV перед запуском) =====
IMAGE_NAME=${IMAGE_NAME:-kreul-api:latest}
ROLLBACK_IMAGE=${ROLLBACK_IMAGE:-kreul-api:rollback}
CONTAINER_NAME=${CONTAINER_NAME:-kreul-api}
ENV_LOCAL_FILE=${ENV_LOCAL_FILE:-.env.prod}
REMOTE_HOST=${REMOTE_HOST:-kreul}
REMOTE_DIR=${REMOTE_DIR:-/home/vmalakhatka/kreul-api}
TAR_NAME=${TAR_NAME:-kreul-api.tar.gz}
EXTERNAL_HEALTH=${EXTERNAL_HEALTH:-https://api.kreul.com.ua/healthz}
INTERNAL_HEALTH=${INTERNAL_HEALTH:-http://127.0.0.1:8080/healthz}
JAVA_OPTS=${JAVA_OPTS:-"-XX:+UseSerialGC -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -Xss256k"}

# ===== Checks =====
if [[ ! -f "$ENV_LOCAL_FILE" ]]; then
  echo "ERROR: $ENV_LOCAL_FILE not found near the script."
  exit 1
fi

printf '%s\n' ">> buildx: create/use builder"
docker buildx create --use --name kreulbuilder >/dev/null 2>&1 || true

printf '%s\n' ">> build image: $IMAGE_NAME (linux/amd64)"
docker buildx build --platform linux/amd64 -t "$IMAGE_NAME" --load .

printf '%s\n' ">> save image to: $TAR_NAME"
docker save "$IMAGE_NAME" | gzip > "$TAR_NAME"

printf '%s\n' ">> ensure remote dir: $REMOTE_HOST:$REMOTE_DIR"
#ssh "$REMOTE_HOST" "mkdir -p $REMOTE_DIR"

# ===== Added: backup current production env and image =====
printf '%s\n' ">> backup current production on remote"
ssh "$REMOTE_HOST" bash -s -- "$REMOTE_DIR" "$CONTAINER_NAME" "$IMAGE_NAME" "$ROLLBACK_IMAGE" <<'REMOTE_BACKUP'
set -euo pipefail
REMOTE_DIR=$1
CONTAINER_NAME=$2
IMAGE_NAME=$3
ROLLBACK_IMAGE=$4

if [[ -f "$REMOTE_DIR/.env.prod" ]]; then
  cp -f "$REMOTE_DIR/.env.prod" "$REMOTE_DIR/.env.prod.prev"
  echo "Saved env backup: $REMOTE_DIR/.env.prod.prev"
else
  echo "No previous .env.prod found"
fi

# Prefer the image of the currently running production container.
# This is safer than assuming that :latest is the version currently running.
if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  CURRENT_IMAGE_ID=$(docker inspect -f '{{.Image}}' "$CONTAINER_NAME")
  docker tag "$CURRENT_IMAGE_ID" "$ROLLBACK_IMAGE"
  echo "Saved rollback image from current container: $ROLLBACK_IMAGE"
elif docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
  docker tag "$IMAGE_NAME" "$ROLLBACK_IMAGE"
  echo "Saved rollback image from $IMAGE_NAME: $ROLLBACK_IMAGE"
else
  echo "No previous Docker image found for rollback"
fi
REMOTE_BACKUP

printf '%s\n' ">> upload env and image"
scp -q "$ENV_LOCAL_FILE" "$REMOTE_HOST:$REMOTE_DIR/.env.prod"
scp -q "$TAR_NAME"       "$REMOTE_HOST:$REMOTE_DIR/"

printf '%s\n' ">> deploy on remote"
set +e
ssh -t "$REMOTE_HOST" bash -lc "set -euo pipefail
cd $REMOTE_DIR

run_container() {
  image=\"\$1\"
  env_file=\"\$2\"

  docker run -d --name $CONTAINER_NAME \\
    --network host \\
    --env-file \"\$env_file\" \\
    --restart unless-stopped \\
    --ulimit nproc=8192:8192 \\
    --ulimit nofile=65535:65535 \\
    --security-opt seccomp=unconfined \\
    -e JAVA_TOOL_OPTIONS=\"$JAVA_OPTS\" \\
    -v /mnt/backup/backups_kreul/synck_logs:/mnt/backup/backups_kreul/synck_logs \\
    \"\$image\"
}

wait_health() {
  for i in {1..20}; do
    code=\$(curl -s -o /dev/null -w '%{http_code}' $INTERNAL_HEALTH || true)
    if [ \"\$code\" = '200' ]; then
      echo 'OK: health 200'
      return 0
    fi
    sleep 1
  done
  return 1
}

rollback() {
  echo '-> rollback to previous image and env'
  docker logs --tail 60 $CONTAINER_NAME 2>/dev/null || true
  docker rm -f $CONTAINER_NAME 2>/dev/null || true

  if ! docker image inspect $ROLLBACK_IMAGE >/dev/null 2>&1; then
    echo 'ERROR: rollback image $ROLLBACK_IMAGE not found'
    return 1
  fi

  rollback_env=$REMOTE_DIR/.env.prod
  if [ -f $REMOTE_DIR/.env.prod.prev ]; then
    rollback_env=$REMOTE_DIR/.env.prod.prev
  fi

  if ! run_container $ROLLBACK_IMAGE \"\$rollback_env\"; then
    echo 'ERROR: rollback container could not be started'
    return 1
  fi

  if wait_health; then
    echo 'ROLLBACK SUCCESSFUL'
    docker logs --tail 60 $CONTAINER_NAME || true
    return 0
  fi

  echo 'ERROR: rollback container failed health check'
  docker logs --tail 60 $CONTAINER_NAME || true
  return 1
}

echo '-> docker load image'
docker load -i $TAR_NAME
echo '-> prune dangling images'
docker image prune -f >/dev/null || true
echo '-> stop/remove old container'
docker rm -f $CONTAINER_NAME 2>/dev/null || true
echo '-> run container'
if ! run_container $IMAGE_NAME $REMOTE_DIR/.env.prod; then
  echo 'ERROR: new container could not be started'
  if rollback; then
    exit 20
  fi
  exit 21
fi

echo '-> wait health (internal)'
if ! wait_health; then
  echo 'ERROR: new container failed health check'
  if rollback; then
    exit 20
  fi
  exit 21
fi

echo '-> last container logs (tail 60)'
docker logs --tail 60 $CONTAINER_NAME || true
"
remote_status=$?
set -e

if [[ $remote_status -eq 20 ]]; then
  echo "ERROR: new version failed; previous version was restored automatically."
  exit 1
elif [[ $remote_status -eq 21 ]]; then
  echo "ERROR: new version failed and rollback also failed."
  exit 1
elif [[ $remote_status -ne 0 ]]; then
  echo "ERROR: deployment failed with remote status $remote_status."
  exit "$remote_status"
fi

printf '%s\n' ">> external health check: $EXTERNAL_HEALTH"
curl -i "$EXTERNAL_HEALTH" || true

printf '\n%s\n' "OK. Logs:  ssh $REMOTE_HOST 'docker logs -f $CONTAINER_NAME'"
