#!/usr/bin/env bash
set -euo pipefail

# =======================
# Настройки (меняй по надобности)
# =======================
IMAGE_NAME="kreul-api:latest"
CONTAINER_NAME="kreul-api"
ENV_LOCAL_FILE=".env.prod"          # твой prod env рядом со скриптом
REMOTE_HOST="kreul"                 # ssh-алиас или user@host
REMOTE_DIR="~/kreul-api"            # куда складываем на сервере
TAR_NAME="kreul-api.tar.gz"
EXTERNAL_HEALTH="https://api.kreul.com.ua/healthz"
INTERNAL_HEALTH="http://127.0.0.1:8080/healthz"

# JVM тюнинг под старый docker/ядро
JAVA_OPTS="-XX:+UseSerialGC -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -Xss256k"

# =======================
# Подсказки и проверки
# =======================
if [[ ! -f "$ENV_LOCAL_FILE" ]]; then
  echo "❌ Не найден $ENV_LOCAL_FILE. Положи prod-окружение рядом со скриптом."
  exit 1
fi

echo "▶️  Создаю/выбираю buildx builder…"
docker buildx create --use --name kreulbuilder >/dev/null 2>&1 || true

echo "▶️  Собираю образ $IMAGE_NAME для linux/amd64…"
docker buildx build \
  --platform linux/amd64 \
  -t "$IMAGE_NAME" \
  --load \
  .

echo "▶️  Пакую образ в $TAR_NAME…"
docker save "$IMAGE_NAME" | gzip > "$TAR_NAME"

echo "▶️  Готовлю директорию на сервере $REMOTE_HOST:$REMOTE_DIR…"
ssh "$REMOTE_HOST" "mkdir -p $REMOTE_DIR"

echo "▶️  Копирую env и образ…"
scp -q "$ENV_LOCAL_FILE" "$REMOTE_HOST:$REMOTE_DIR/.env.prod"
scp -q "$TAR_NAME"       "$REMOTE_HOST:$REMOTE_DIR/"

echo "▶️  Разворачиваю на сервере…"
ssh -t "$REMOTE_HOST" bash -lc "'
  set -euo pipefail
  cd $REMOTE_DIR

  echo \"→ Загружаю образ из $TAR_NAME…\"
  docker load -i $TAR_NAME

  echo \"→ Чищу старые висячие слои…\"
  docker image prune -f >/dev/null || true

  echo \"→ Останавливаю/удаляю старый контейнер…\"
  docker rm -f $CONTAINER_NAME 2>/dev/null || true

  echo \"→ Стартую контейнер $CONTAINER_NAME…\"
  docker run -d --name $CONTAINER_NAME \
    --network host \
    --env-file $REMOTE_DIR/.env.prod \
    --restart unless-stopped \
    --ulimit nproc=8192:8192 \
    --ulimit nofile=65535:65535 \
    --security-opt seccomp=unconfined \
    -e JAVA_TOOL_OPTIONS=\"$JAVA_OPTS\" \
    $IMAGE_NAME

  echo \"→ Ждём, пока поднимется (health: $INTERNAL_HEALTH)…\"
  for i in {1..20}; do
    code=\$(curl -s -o /dev/null -w \"%{http_code}\" $INTERNAL_HEALTH || true)
    if [[ \"\$code\" == \"200\" ]]; then
      echo \"OK: health 200\"
      break
    fi
    sleep 1
  done

  echo \"→ Последние логи:\"
  docker logs --tail 60 $CONTAINER_NAME || true
'"

echo "▶️  Внешняя проверка $EXTERNAL_HEALTH…"
curl -i "$EXTERNAL_HEALTH" || true

echo ""
echo "✅ Готово. Если нужно — смотри логи:  ssh $REMOTE_HOST 'docker logs -f $CONTAINER_NAME'"