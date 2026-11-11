#!/usr/bin/env bash
#
# P0 验收脚本：中间件全部可连通。
# 覆盖 dev_doc §9 P0 交付物：PG 三库、Redis、RustFS 桶、Nacos namespace 与四个 group 配置，
# 外加四个业务服务的探活（服务没起时只提示，不算失败，P0 验收目标是"中间件可连通"）。
#
# 用法（在 MyLittleLifeMap-backend 目录下）：
#   ./scripts/smoke-test.sh
#
# 退出码：0 全部通过；1 有必需项失败。

set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

POSTGRES_USER="${POSTGRES_USER:-lifemap}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
REDIS_PASSWORD="${REDIS_PASSWORD:-lifemap_dev_2026}"
REDIS_PORT="${REDIS_PORT:-6379}"
RUSTFS_API_PORT="${RUSTFS_API_PORT:-9000}"
RUSTFS_BUCKET="${RUSTFS_BUCKET:-lifemap}"
NACOS_PORT="${NACOS_PORT:-8848}"
NACOS_NAMESPACE="${NACOS_NAMESPACE:-lifemap-dev}"

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf '  \033[32m[PASS]\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31m[FAIL]\033[0m %s\n' "$*"; }
info() { printf '  \033[36m[SKIP]\033[0m %s\n' "$*"; }

section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- 容器状态
section "1. 容器状态"
running="$(docker compose ps --services --status running 2>/dev/null || true)"
for svc in postgres redis rustfs nacos; do
  if printf '%s\n' "${running}" | grep -qx "${svc}"; then
    ok "${svc} 运行中"
  else
    bad "${svc} 未运行（docker compose up -d）"
  fi
done

# ---------------------------------------------------------------- PostgreSQL
section "2. PostgreSQL（一实例三库）"
if docker compose exec -T postgres pg_isready -U "${POSTGRES_USER}" -d postgres >/dev/null 2>&1; then
  ok "pg_isready 通过（127.0.0.1:${POSTGRES_PORT}）"

  dbs="$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d postgres -tAc \
        "SELECT datname FROM pg_database WHERE datistemplate = false" 2>/dev/null | tr -d '\r')"
  for db in place_db media_db nacos; do
    if printf '%s\n' "${dbs}" | grep -qx "${db}"; then
      ok "数据库 ${db} 存在"
    else
      bad "数据库 ${db} 缺失（检查 deploy/postgres/init 是否执行过，或数据卷已存在旧结构）"
    fi
  done

  ver="$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d postgres -tAc 'SHOW server_version' 2>/dev/null | tr -d '\r ')"
  info "PostgreSQL 版本 ${ver}"
else
  bad "PostgreSQL 不可连接"
fi

# ---------------------------------------------------------------- Redis
section "3. Redis"
if pong="$(docker compose exec -T redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning ping 2>/dev/null | tr -d '\r')" \
   && [[ "${pong}" == "PONG" ]]; then
  ok "PING → PONG（127.0.0.1:${REDIS_PORT}，已启用密码与 AOF）"
else
  bad "Redis 不可连接或密码错误"
fi

# ---------------------------------------------------------------- RustFS
section "4. RustFS（S3 兼容对象存储）"
if curl -fsS "http://127.0.0.1:${RUSTFS_API_PORT}/health" >/dev/null 2>&1; then
  ok "健康检查通过（API ${RUSTFS_API_PORT} / 控制台 ${RUSTFS_CONSOLE_PORT:-9001}/rustfs/console/）"
else
  bad "RustFS 健康检查失败"
fi

# 建桶与列桶都走 s3cli（mc 客户端），别名已由 MC_HOST_local 注入
if docker compose exec -T s3cli mc mb --ignore-existing "local/${RUSTFS_BUCKET}" >/dev/null 2>&1 \
   && docker compose exec -T s3cli mc mb --ignore-existing "local/${RUSTFS_BUCKET}-backup" >/dev/null 2>&1; then
  if docker compose exec -T s3cli mc ls local 2>/dev/null | grep -q "${RUSTFS_BUCKET}/"; then
    ok "桶 ${RUSTFS_BUCKET} 就绪（含 ${RUSTFS_BUCKET}-backup）"
  else
    bad "桶 ${RUSTFS_BUCKET} 未创建"
  fi
else
  bad "s3cli 执行失败，无法确认桶"
fi

# ---------------------------------------------------------------- Nacos
section "5. Nacos（namespace + 四个 group）"
NACOS_BASE="http://127.0.0.1:${NACOS_PORT}/nacos"
# readiness 返回正文是 "OK"（不是 actuator 的 "UP"），只判 HTTP 状态码
if curl -fsS -o /dev/null "${NACOS_BASE}/v1/console/health/readiness" 2>/dev/null; then
  ok "readiness → 200（控制台 http://127.0.0.1:${NACOS_PORT}/nacos）"
else
  bad "Nacos 未就绪"
fi

if curl -fsS "${NACOS_BASE}/v1/console/namespaces" 2>/dev/null | grep -q "\"namespace\":\"${NACOS_NAMESPACE}\""; then
  ok "namespace ${NACOS_NAMESPACE} 存在"
else
  bad "namespace ${NACOS_NAMESPACE} 缺失（执行 ./scripts/nacos-init.sh）"
fi

configs="$(curl -fsS "${NACOS_BASE}/v1/cs/configs?search=accurate&dataId=&group=&pageNo=1&pageSize=50&tenant=${NACOS_NAMESPACE}" 2>/dev/null || true)"
for pair in "gateway.yaml:GATEWAY" "place-service.yaml:PLACE" "media-service.yaml:MEDIA" "stats-service.yaml:STATS"; do
  data_id="${pair%%:*}"
  group="${pair##*:}"
  if printf '%s' "${configs}" \
      | tr '}' '\n' \
      | grep -q "\"dataId\":\"${data_id}\"" \
     && printf '%s' "${configs}" | tr '}' '\n' | grep -q "\"group\":\"${group}\""; then
    ok "配置 ${group}/${data_id} 存在"
  else
    bad "配置 ${group}/${data_id} 缺失"
  fi
done

# ---------------------------------------------------------------- 业务服务（可选）
section "6. 业务服务探活（可选，P0 不要求常驻）"
for entry in "gateway:${GATEWAY_PORT:-8080}" "place:${PLACE_PORT:-8081}" \
             "media:${MEDIA_PORT:-8082}" "stats:${STATS_PORT:-8083}"; do
  name="${entry%%:*}"
  port="${entry##*:}"
  if health="$(curl -fsS "http://127.0.0.1:${port}/actuator/health" 2>/dev/null)"; then
    status="$(printf '%s' "${health}" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')"
    ok "${name} :${port} → ${status:-UP}"
  else
    info "${name} :${port} 未启动"
  fi
done

# ---------------------------------------------------------------- media 内部接口
section "7. media 内部接口（可选，media 未启动则跳过）"
MEDIA_BASE="http://127.0.0.1:${MEDIA_PORT:-8082}"
if curl -fsS "${MEDIA_BASE}/actuator/health" >/dev/null 2>&1; then
  # 空 ids 必须直接返回 {}，不发 SQL——这是详情页"这条记录没有照片"的常态
  empty="$(curl -fsS "${MEDIA_BASE}/internal/photos/by-place-ids?ids=" 2>/dev/null || true)"
  if [ "${empty}" = "{}" ]; then
    ok "by-place-ids 空 ids → {}"
  else
    bad "by-place-ids 空 ids 返回 ${empty:-空}"
  fi

  # 内部接口只能读照片前缀，别让它变成任意对象下载器
  code="$(curl -s -o /dev/null -w '%{http_code}' "${MEDIA_BASE}/internal/photos/stream?key=../../etc/passwd" || true)"
  if [ "${code}" = "400" ]; then
    ok "stream 拒绝非法 key（400）"
  else
    bad "stream 非法 key 返回 ${code}"
  fi
else
  info "media :${MEDIA_PORT:-8082} 未启动，跳过内部接口检查"
fi

# ---------------------------------------------------------------- 汇总
printf '\n\033[1m结果：%d 通过 / %d 失败\033[0m\n' "${PASS}" "${FAIL}"
[[ "${FAIL}" -eq 0 ]] || exit 1
