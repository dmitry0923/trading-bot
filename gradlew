#!/bin/sh
# Gradle start up script

APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

# Collect all arguments for the java command, stacking in reverse order:
set --         "-Dorg.gradle.appname=$APP_BASE_NAME"         -classpath "$CLASSPATH"         org.gradle.wrapper.GradleWrapperMain         "$@"

# Stop when "xargs" is not available.
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# Use "xargs" to parse quoted args.
for arg do
    if
        case $arg in                                #(
          -*)   false ;;                            # don't mess with options #(
          /?*)  t=${arg#)}; t=/${t%%/*}             # looks like a POSIX filepath
                [ -e "$t" ] ;;                      #(
          *)    false ;;
        esac
    then
        arg=$( cygpath --path --ignore --mixed "$arg" )
    fi
    shift                   # out with the old
    set -- "$@" "$arg"      # in with the new
done

# Collect all arguments for the java command;
set --         "-Dorg.gradle.appname=$APP_BASE_NAME"         -classpath "$CLASSPATH"         org.gradle.wrapper.GradleWrapperMain         "$@"

exec "$JAVACMD" "$@"
