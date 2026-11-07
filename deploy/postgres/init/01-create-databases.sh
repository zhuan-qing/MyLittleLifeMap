#!/bin/bash
# 单实例三库初始化。仅在数据卷为空、容器首次启动时由 postgres 镜像的 entrypoint 执行。
#
# 三库口径：
#   place_db  —— place 服务独占（place / app_setting / place_event）
#   media_db  —— media 服务独占（photo）
#   nacos     —— 预留：Nacos 从单机 derby 切到外部数据源时使用，P0 不上
#
# stats 服务无自有库（点亮是查询结果，结果只进 Redis），gateway 无库。
set -euo pipefail

LOG_PREFIX="[init-db]"

create_database() {
  local db="$1"
  local owner="$2"

  local exists
  exists="$(psql -tAc "SELECT 1 FROM pg_database WHERE datname = '${db}'" \
    --username "${POSTGRES_USER}" --dbname postgres)"

  if [ "${exists}" = "1" ]; then
    echo "${LOG_PREFIX} ${db} 已存在，跳过"
    return 0
  fi

  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres \
    -c "CREATE DATABASE \"${db}\" OWNER \"${owner}\" ENCODING 'UTF8' TEMPLATE template0"
  echo "${LOG_PREFIX} ${db} 创建完成"
}

create_database "place_db" "${POSTGRES_USER}"
create_database "media_db" "${POSTGRES_USER}"
create_database "nacos" "${POSTGRES_USER}"

echo "${LOG_PREFIX} 当前数据库列表："
psql -tAc "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname" \
  --username "${POSTGRES_USER}" --dbname postgres
