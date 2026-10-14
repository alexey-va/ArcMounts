#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

property() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' gradle.properties
}

plugin_version="${PLUGIN_VERSION:-$(property pluginVersion)}"
api_version="${API_VERSION:-$(property mountsApiVersion)}"
staging_root="build/release-repository"

[[ "$plugin_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || fail "Invalid plugin version"
[[ "$api_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || fail "Invalid API version"

expected_files=(
  "ru/ruscrafting/ArcMounts/$plugin_version/ArcMounts-$plugin_version.jar"
  "ru/ruscrafting/ArcMounts/$plugin_version/ArcMounts-$plugin_version.pom"
  "ru/ruscrafting/arc/arc-mounts-api/$api_version/arc-mounts-api-$api_version.jar"
  "ru/ruscrafting/arc/arc-mounts-api/$api_version/arc-mounts-api-$api_version-sources.jar"
  "ru/ruscrafting/arc/arc-mounts-api/$api_version/arc-mounts-api-$api_version.pom"
  "ru/ruscrafting/arc/arc-mounts-api/$api_version/arc-mounts-api-$api_version.module"
)

for relative in "${expected_files[@]}"; do
  [[ -s "$staging_root/$relative" ]] || fail "Staged artifact is missing or empty: $relative"
done

python3 - \
  "$staging_root/ru/ruscrafting/ArcMounts/$plugin_version/ArcMounts-$plugin_version.pom" \
  "$staging_root/ru/ruscrafting/arc/arc-mounts-api/$api_version/arc-mounts-api-$api_version.pom" \
  "$plugin_version" "$api_version" <<'PY'
import sys
import xml.etree.ElementTree as ET

plugin_pom, api_pom, plugin_version, api_version = sys.argv[1:]
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

def validate(path, group, artifact, version, must_be_dependency_free=False):
    root = ET.parse(path).getroot()
    actual = tuple(root.findtext(f"m:{key}", namespaces=namespace) for key in ("groupId", "artifactId", "version"))
    expected = (group, artifact, version)
    if actual != expected:
        raise SystemExit(f"POM coordinate mismatch in {path}: {actual!r}")
    dependencies = root.find("m:dependencies", namespace)
    if must_be_dependency_free and dependencies is not None and list(dependencies):
        raise SystemExit(f"Runtime plugin POM must not propagate compile dependencies: {path}")

validate(plugin_pom, "ru.ruscrafting", "ArcMounts", plugin_version, must_be_dependency_free=True)
validate(api_pom, "ru.ruscrafting.arc", "arc-mounts-api", api_version)
PY

exec ./scripts/publish-staged-maven.sh "$staging_root" "${expected_files[@]}"
