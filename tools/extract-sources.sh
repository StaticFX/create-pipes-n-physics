#!/usr/bin/env bash
# Extracts the dependency SOURCES jars (Create, NeoForge, Ponder, Sable) out of the gradle
# cache into run/sources/<name>/ (gitignored via run/), so engine work can read and grep
# third-party code at a stable path instead of re-locating jars in ~/.gradle every session.
# Versions come from gradle.properties, so a dependency bump just needs a re-run.
set -euo pipefail
cd "$(dirname "$0")/.."

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
prop() { sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" gradle.properties | tr -d '[:space:]'; }

extract() { # <target dir name> <path glob under the gradle cache>
    local name="$1" glob="$2" found=0
    rm -rf "run/sources/$name"
    mkdir -p "run/sources/$name"
    while IFS= read -r jar; do
        (cd "run/sources/$name" && unzip -oq "$jar")
        echo "  $name: $(basename "$jar")"
        found=1
    done < <(find "$CACHE" -path "$glob" -name "*-sources.jar" 2>/dev/null)
    [ "$found" = 1 ] || echo "  $name: NO sources jar matched $glob (skipped — is the dependency resolved?)"
}

echo "Extracting dependency sources to run/sources/ ..."
extract create           "*com.simibubi.create/create-*/$(prop create_version)/*"
extract neoforge         "*net.neoforged/neoforge/$(prop neo_version)/*"
extract ponder           "*net.createmod.ponder/ponder-neoforge/$(prop ponder_version)+*/*"
extract sable            "*dev.ryanhcode.sable/sable-*/$(prop sable_version)/*"
extract sable-companion  "*dev.ryanhcode.sable-companion/sable-companion-*/$(prop sable_companion_version)/*"
# Both flywheel jars land in one tree: the api is what an addon compiles against, the impl carries
# the GLSL and the visualization internals a render question actually needs to read.
extract flywheel         "*dev.engine-room.flywheel/flywheel-neoforge*/$(prop flywheel_version)/*"
echo "Done — read/grep under run/sources/."
