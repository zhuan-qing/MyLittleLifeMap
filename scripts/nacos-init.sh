#!/usr/bin/env bash
#
# Nacos 初始化：建 namespace + 按服务发布四个 group 的配置。
# 幂等——重复执行只会覆盖同名配置，不会报错。
#
# 用法：
#   ./scripts/nacos-init.sh                                  # 默认连 127.0.0.1:8848
#   NACOS_ADDR=192.168.1.10:8848 ./scripts/nacos-init.sh
#   NACOS_USERNAME=nacos NACOS_PASSWORD=xxx ./scripts/nacos-init.sh   # 开了鉴权时
#
# 约定：namespace 按环境隔离（lifemap-dev / lifemap-prod），group 按服务拆分（GATEWAY/PLACE/MEDIA/STATS）。
# 配置内容放在 deploy/nacos/<GROUP>/<dataId>.yaml，改配置 = 改文件后重跑本脚本，也可直接在控制台改。

set -euo pipefail

NACOS_ADDR="${NACOS_ADDR:-127.0.0.1:8848}"
NACOS_NAMESPACE="${NACOS_NAMESPACE:-lifemap-dev}"
NACOS_NAMESPACE_NAME="${NACOS_NAMESPACE_NAME:-开发环境}"
NACOS_NAMESPACE_DESC="${NACOS_NAMESPACE_DESC:-个人足迹地图 开发环境}"

BASE="http://${NACOS_ADDR}/nacos"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF_DIR="${SCRIPT_DIR}/../deploy/nacos"
TOKEN=""

log() { echo "[nacos-init] $*"; }
die() { echo "[nacos-init] 失败：$*" >&2; exit 1; }

# 给 URL 按需追加 accessToken（兼容已带 query 的地址）
with_token() {
  local url="$1"
  if [[ -z "${TOKEN}" ]]; then
    printf '%s' "${url}"
  elif [[ "${url}" == *"?"* ]]; then
    printf '%s&accessToken=%s' "${url}" "${TOKEN}"
  else
    printf '%s?accessToken=%s' "${url}" "${TOKEN}"
  fi
}

# ---------- 0. 可选鉴权：NACOS_AUTH_ENABLE=true 时填 NACOS_USERNAME / NACOS_PASSWORD ----------
if [[ -n "${NACOS_USERNAME:-}" ]]; then
  login_body="$(curl -sS -X POST "${BASE}/v1/auth/login" \
    --data-urlencode "username=${NACOS_USERNAME}" \
    --data-urlencode "password=${NACOS_PASSWORD:-}")" || die "登录 Nacos 失败"
  TOKEN="$(printf '%s' "${login_body}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
  [[ -n "${TOKEN}" ]] || die "未取到 accessToken，检查账号密码：${login_body}"
  log "已登录 Nacos（${NACOS_USERNAME}）"
fi

# ---------- 1. 等待 Nacos 就绪 ----------
log "等待 Nacos 就绪：${BASE}"
ready=""
for _ in $(seq 1 60); do
  if curl -fsS "$(with_token "${BASE}/v1/console/health/readiness")" >/dev/null 2>&1; then
    ready="yes"
    break
  fi
  sleep 2
done
[[ -n "${ready}" ]] || die "Nacos 在 120s 内未就绪，先确认容器状态：docker compose ps"

# ---------- 2. 创建 namespace（已存在则跳过） ----------
log "创建 namespace：${NACOS_NAMESPACE}"
ns_list="$(curl -sS "$(with_token "${BASE}/v1/console/namespaces")")"

if printf '%s' "${ns_list}" | grep -q "\"namespace\":\"${NACOS_NAMESPACE}\""; then
  log "namespace ${NACOS_NAMESPACE} 已存在，跳过"
else
  resp="$(curl -sS -X POST "$(with_token "${BASE}/v1/console/namespaces")" \
    --data-urlencode "customNamespaceId=${NACOS_NAMESPACE}" \
    --data-urlencode "namespaceName=${NACOS_NAMESPACE_NAME}" \
    --data-urlencode "namespaceDesc=${NACOS_NAMESPACE_DESC}")"
  [[ "${resp}" == "true" ]] || die "创建 namespace 返回：${resp}"
  log "namespace ${NACOS_NAMESPACE} 创建完成"
fi

# ---------- 3. 按 group 发布配置 ----------
shopt -s nullglob
files=("${CONF_DIR}"/*/*.yaml)
[[ ${#files[@]} -gt 0 ]] || die "未找到配置源文件：${CONF_DIR}/<GROUP>/*.yaml"

for file in "${files[@]}"; do
  group="$(basename "$(dirname "${file}")")"
  data_id="$(basename "${file}")"

  # 配置内容从 stdin 读：Git Bash 下 curl.exe 不认 /e/... 这种 MSYS 路径，
  # 用 content@- 配合重定向，Windows 与 Linux 都能跑
  resp="$(curl -sS -X POST "$(with_token "${BASE}/v1/cs/configs")" \
    --data-urlencode "dataId=${data_id}" \
    --data-urlencode "group=${group}" \
    --data-urlencode "tenant=${NACOS_NAMESPACE}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@-" < "${file}")"
  [[ "${resp}" == "true" ]] || die "发布 ${group}/${data_id} 失败：${resp}"
  log "已发布 ${group}/${data_id}"
done

# ---------- 4. 回读校验 ----------
log "校验 namespace 下的配置："
curl -sS "$(with_token "${BASE}/v1/cs/configs?search=accurate&dataId=&group=&pageNo=1&pageSize=50&tenant=${NACOS_NAMESPACE}")" \
  | tr ',' '\n' | grep -E '"(dataId|group)"' | sed 's/^[[:space:]]*/    /'

log "完成。控制台 http://${NACOS_ADDR}/nacos  命名空间 ${NACOS_NAMESPACE}"
