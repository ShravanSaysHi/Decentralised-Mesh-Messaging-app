#!/bin/sh
set -e
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
exec java -Dorg.gradle.appname="gradlew" -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
