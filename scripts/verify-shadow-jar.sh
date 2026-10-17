#!/usr/bin/env bash
set -euo pipefail

shopt -s nullglob
candidates=(build/libs/ArcMounts-*.jar)
production_jars=()
for candidate in "${candidates[@]}"; do
  [[ "$candidate" == *-plain.jar ]] || production_jars+=("$candidate")
done

[[ ${#production_jars[@]} -eq 1 ]] || {
  printf 'Expected exactly one production ArcMounts JAR, found %s\n' "${#production_jars[@]}" >&2
  exit 1
}

jar_path="${production_jars[0]}"
[[ -s "$jar_path" ]] || { printf 'Production JAR is empty: %s\n' "$jar_path" >&2; exit 1; }

entries="$(mktemp)"
trap 'rm -f "$entries"' EXIT
jar tf "$jar_path" > "$entries"
grep -Fxq 'plugin.yml' "$entries"
grep -Fxq 'kotlin/collections/CollectionsKt.class' "$entries"
unzip -p "$jar_path" plugin.yml | grep -Fxq 'name: ArcMounts'

if grep -Eq '^(ru/arc/mounts/api/|ru/arc/paper/api/|ru/arc/paper/sql/(PaperSqlServiceProvider|SharedPaperSqlService)[^/]*\.class$|ru/arc/paper/redis/(PaperRedisServiceProvider|SharedPaperRedisService)[^/]*\.class$|ru/arc/paper/metrics/PaperArcMetricsProvider[^/]*\.class$|ru/arc/paper/runtime/PaperNativeCleanupServiceProvider[^/]*\.class$|org/bukkit/|io/papermc/)' "$entries"; then
  printf 'Production JAR embeds an ARC-owned service/API or server-provided Paper class.\n' >&2
  exit 1
fi

printf 'Verified production JAR: %s\n' "$jar_path"
