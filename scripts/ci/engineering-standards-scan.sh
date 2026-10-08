#!/usr/bin/env bash
#
# 工程规范风险词扫描（第四批 E-14）。
#
# 默认只输出告警（exit 0），用于存量代码迁移期；传入 --strict 时存在命中即 exit 1，
# 作为 CI 强制门禁。
#
# 用法：
#   bash scripts/ci/engineering-standards-scan.sh
#   bash scripts/ci/engineering-standards-scan.sh --strict
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

STRICT=0
if [ "${1:-}" = "--strict" ]; then
  STRICT=1
fi

MODULES=(
  mall-api
  mall-order-service
  mall-item-service
  mall-user-service
  mall-risk-service
  mall-gateway-service
)

MAIN_SOURCES=()
for module in "${MODULES[@]}"; do
  while IFS= read -r file; do
    MAIN_SOURCES+=("$file")
  done < <(find "$module/src/main/java" -type f -name '*.java' 2>/dev/null)
done

if [ "${#MAIN_SOURCES[@]}" -eq 0 ]; then
  echo "no java sources found, nothing to scan"
  exit 0
fi

HITS=0

scan() {
  local title="$1"
  local pattern="$2"
  local matches
  matches=$(grep -nE "$pattern" "${MAIN_SOURCES[@]}" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    HITS=$((HITS + 1))
    echo
    echo "WARN: $title"
    echo "$matches"
  fi
}

echo "scanning ${#MAIN_SOURCES[@]} java source files..."

scan "禁止在生产源码出现 debugCode（验证码不外泄）" 'debugCode'
scan "禁止 printStackTrace()" 'printStackTrace\('
scan "禁止 System.out.println" 'System\.out\.println'
scan "Controller 禁止接收 Map 请求体（请改用 DTO + @Valid）" '@RequestBody[[:space:]]+Map<'
scan "Controller 禁止 catch (Exception) 后包装响应（请使用统一异常体系）" 'catch[[:space:]]*\([[:space:]]*Exception[[:space:]]+[a-zA-Z_]+[[:space:]]*\)'
scan "业务服务新增业务时间请使用注入的 Clock（LocalDateTime.now(clock)）" 'LocalDateTime\.now\(\)'
scan "配置请迁移到 @ConfigurationProperties，避免散落 @Value" '@Value\('

require_pattern() {
  local title="$1"
  local pattern="$2"
  local file="$3"
  if [ -f "$file" ] && ! grep -qE "$pattern" "$file"; then
    HITS=$((HITS + 1))
    echo
    echo "WARN: $title ($file)"
    echo "  missing required pattern: $pattern"
  fi
}

require_pattern "Mock 支付接口必须限定 @Profile，生产不可创建" '@Profile' \
  "mall-order-service/src/main/java/com/example/item/controller/MockPaymentController.java"
require_pattern "审计演示接口必须限定 @Profile，生产不可创建" '@Profile' \
  "mall-order-service/src/main/java/com/example/item/controller/AuditLogTestController.java"

if [ "$HITS" -gt 0 ]; then
  echo
  echo "engineering standards scan: $HITS rule(s) matched"
  if [ "$STRICT" -eq 1 ]; then
    exit 1
  fi
else
  echo "engineering standards scan: clean"
fi
