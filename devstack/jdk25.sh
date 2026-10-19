# Finding the Java 25 the build targets (ADR-012). Sourced, not executed.
#
# Extracted here the moment a second script needed it. The system JDK on a developer's machine is
# routinely older than 25, and Gradle provisions its own toolchain rather than installing one — so
# every devstack script that launches a JAR has to go and find it, and a copy of this logic that
# drifts means one script works and another reports a service that "did not start in 90s" with an
# UnsupportedClassVersionError buried in a log.
find_jdk25() {
  if [[ -n "${JAVA_HOME:-}" ]] && [[ "$("$JAVA_HOME/bin/java" -version 2>&1)" == *'"25'* ]]; then
    printf '%s' "$JAVA_HOME"; return 0
  fi
  if [[ "$(java -version 2>&1)" == *'"25'* ]]; then
    printf '%s' ""; return 0   # already on PATH
  fi
  for candidate in "$HOME"/.jdks/jdk-25* "$HOME"/.gradle/jdks/*25* /usr/lib/jvm/*25*; do
    [[ -x "$candidate/bin/java" ]] && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}

# Sets JAVA_HOME and JAVA, or fails with the one instruction that fixes it.
require_jdk25() {
  local jdk
  jdk="$(find_jdk25)" || {
    printf 'no Java 25 found. The build targets 25 (ADR-012); run\n  ./gradlew build  in backend/ once — Gradle provisions a toolchain under ~/.jdks — or set\n  JAVA_HOME to a JDK 25.\n' >&2
    exit 1
  }
  [[ -n "$jdk" ]] && export JAVA_HOME="$jdk"
  JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
}
