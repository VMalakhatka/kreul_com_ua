#!/usr/bin/env bash
set -euo pipefail

# ===== Defaults (можно переопределять через ENV перед запуском) =====
IMAGE_NAME=${IMAGE_NAME:-kreul-api:latest}
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

printf '%s\n' ">> upload env and image"
scp -q "$ENV_LOCAL_FILE" "$REMOTE_HOST:$REMOTE_DIR/.env.prod"
scp -q "$TAR_NAME"       "$REMOTE_HOST:$REMOTE_DIR/"

printf '%s\n' ">> deploy on remote"
ssh -t "$REMOTE_HOST" bash -lc "set -euo pipefail
cd $REMOTE_DIR
echo '-> docker load image'
docker load -i $TAR_NAME
echo '-> prune dangling images'
docker image prune -f >/dev/null || true
echo '-> stop/remove old container'
docker rm -f $CONTAINER_NAME 2>/dev/null || true
echo '-> run container'
docker run -d --name $CONTAINER_NAME \
  --network host \
  --env-file $REMOTE_DIR/.env.prod \
  --restart unless-stopped \
  --ulimit nproc=8192:8192 \
  --ulimit nofile=65535:65535 \
  --security-opt seccomp=unconfined \
  -e JAVA_TOOL_OPTIONS=\"$JAVA_OPTS\" \
  $IMAGE_NAME
echo '-> wait health (internal)'
for i in {1..20}; do
  code=\$(curl -s -o /dev/null -w '%{http_code}' $INTERNAL_HEALTH || true)
  if [ \"\$code\" = '200' ]; then
    echo 'OK: health 200'
    break
  fi
  sleep 1
done
echo '-> last container logs (tail 60)'
docker logs --tail 60 $CONTAINER_NAME || true
"

printf '%s\n' ">> external health check: $EXTERNAL_HEALTH"
curl -i "$EXTERNAL_HEALTH" || true

printf '\n%s\n' "OK. Logs:  ssh $REMOTE_HOST 'docker logs -f $CONTAINER_NAME'"