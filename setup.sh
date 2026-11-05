#!/usr/bin/env bash
# Use the checked-in wrapper; never regenerate it with an older Gradle version.
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
    echo "Java is required. Set JAVA_HOME to your installed JDK or the IDE's bundled JBR."
    exit 1
fi
chmod +x gradlew
./gradlew --version
printf '\nWrapper ready. Build with ./gradlew test buildPlugin\n'
