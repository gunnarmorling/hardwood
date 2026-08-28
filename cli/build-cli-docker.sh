#!/bin/bash
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Copyright The original authors
#
#  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

set -e

# Build the CLI Docker image. Produces (or reuses) the native dist — the Linux
# hardwood binary, the completion script, and the codec native libraries — then
# builds the image from it. The native build targets the host platform, so this
# script has to run on Linux; see NATIVE_BUILD.md for how a Linux binary is
# obtained from another host.
# Use -f/--force to rebuild the dist even if one already exists.
#
# Usage: cd cli && ./build-cli-docker.sh [options] [image-tag]
#
# Options:
#   -f, --force         Rebuild the native dist even if one already exists
#
# Examples:
#   cd cli && ./build-cli-docker.sh                    # reuse dist if present, tag ghcr.io/hardwood-hq/hardwood:local
#   cd cli && ./build-cli-docker.sh v1.0.0             # reuse dist if present, tag ghcr.io/hardwood-hq/hardwood:v1.0.0
#   cd cli && ./build-cli-docker.sh -f                 # force rebuild, tag ghcr.io/hardwood-hq/hardwood:local
#   cd cli && ./build-cli-docker.sh --force v1.0.0     # force rebuild, tag ghcr.io/hardwood-hq/hardwood:v1.0.0

FORCE_REBUILD=false
IMAGE_TAG="local"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    -f|--force)
      FORCE_REBUILD=true
      shift
      ;;
    *)
      IMAGE_TAG="$1"
      shift
      ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BINARY="$REPO_ROOT/cli/target/hardwood-cli"
COMPLETION="$REPO_ROOT/cli/target/hardwood_completion"
LIBS_DIR="$REPO_ROOT/cli/target/native-libs"
IMAGE_NAME="ghcr.io/hardwood-hq/hardwood:${IMAGE_TAG}"

# A dist usable by the image is a Linux ELF binary, the completion script, and the
# Linux codec libraries (*.so). Check the ELF magic (0x7f 'E' 'L' 'F') so a host
# build (e.g. a macOS Mach-O) is never mistaken for a usable dist.
is_linux_elf() {
  [ -f "$1" ] && [ "$(head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

dist_ready() {
  is_linux_elf "$BINARY" && [ -f "$COMPLETION" ] && ls "$LIBS_DIR"/*.so >/dev/null 2>&1
}

# Build the full native dist (binary + codec libraries) for the image:
#   - the codec libraries are prebuilt platform binaries shipped inside the
#     dependency JARs, so pin the Linux variants explicitly rather than letting the
#     os-* Maven profiles pick by host;
#   - native integration tests are skipped: this build feeds the image, and the
#     native profile's ITs run in their own CI job.
build_dist() {
  echo "Building the native dist (Linux binary + codec libraries)..."
  echo "This may take several minutes."
  echo ""
  cd "$REPO_ROOT"
  ./mvnw -Dnative package \
    -pl cli,error-prone-checks -am \
    -DskipTests -DskipITs \
    -Ddist.os=linux -Ddist.lib.extension='*.so' \
    -Dzstd.os.dir=linux -Dsnappy.os.dir=Linux -Dlz4.os.dir=linux
  cd "$REPO_ROOT/cli"
  echo ""
}

# Checked up front rather than left to the ELF check below: the native build
# targets the host, so on a non-Linux host the script would spend several
# minutes producing a binary the image cannot run before saying so.
if [ "$(uname -s)" != "Linux" ]; then
  echo "Error: the image needs a Linux hardwood binary, and the native build targets the"
  echo "host platform ($(uname -s) detected), so this script has to run on Linux."
  echo "See NATIVE_BUILD.md, 'Building a Linux binary'."
  exit 1
fi

echo "Building Docker image for hardwood CLI: $IMAGE_NAME"
echo ""

if [ "$FORCE_REBUILD" = true ] || ! dist_ready; then
  build_dist
else
  echo "Using existing Linux native dist in cli/target (binary, completion, native-libs)."
  echo "(Use -f/--force to rebuild)"
  echo ""
fi

if ! dist_ready; then
  echo "Error: native dist is incomplete after the build."
  echo "Expected a Linux ELF at cli/target/hardwood-cli, cli/target/hardwood_completion,"
  echo "and codec libraries (*.so) in cli/target/native-libs."
  exit 1
fi

echo "Building Docker image..."
docker build -t "$IMAGE_NAME" -f "$REPO_ROOT/cli/docker/Dockerfile" "$REPO_ROOT/cli"

echo ""
echo "✓ Docker image built successfully: $IMAGE_NAME"
echo ""
echo "Run the image with:"
echo "  docker run --rm $IMAGE_NAME --help"
echo "  docker run --rm -v \"\$(pwd)\":/repo -w /repo $IMAGE_NAME info -f core/src/test/resources/plain_uncompressed.parquet"

