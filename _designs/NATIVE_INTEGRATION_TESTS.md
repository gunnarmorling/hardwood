# Native Binary Integration Tests

**Status:** Completed

## Overview

The CLI native binary is smoke-tested by Failsafe integration tests that spawn the compiled
executable as a subprocess and assert on its exit code and output. When the `native` Maven
profile is active (`-Dnative`), these tests run during the `integration-test` phase.

## Test Layers

### JVM tests

All `*CommandTest` classes run during the `test` phase against the JVM build of the
application and provide the primary behavioural coverage for every CLI command. They
remain the fast feedback loop during development and are unaffected by the native
integration layer.

### Native smoke tests

Three `*IT` classes run during the `integration-test` phase via the Maven Failsafe plugin
and exercise the native binary end-to-end:

- `NativeBinarySmokeIT` runs the native binary against a local Parquet file on disk, and
  pins its reported build version against the JVM it was compiled from.
- `NativeBinaryS3SmokeIT` runs the native binary against a Parquet file served by an
  S3 proxy container.
- `NativeCompressionCodecIT` reads one fixture per supported compression codec, covering
  `LZ4` and `LZ4_RAW` as separate cases because they use different decompressors.

These tests exist to catch native-image-specific regressions — missing reflection
registrations, unreachable classpath resources, broken AWS SDK wiring, codec native
libraries that fail to load — rather than to re-verify per-command behaviour. Per-command
assertions live once, in the JVM layer.

## Locating and Configuring the Binary

The Failsafe execution in `cli/pom.xml` passes `native.image.path` as a system property,
pointing at `${project.build.directory}/hardwood-cli`. Each IT reads that property to find
the executable.

The IT classes themselves run in the JVM and use `ProcessBuilder` to spawn the binary.
File path arguments are resolved as classpath resources in the JVM test process and passed
as absolute paths, so they are reachable by the subprocess through the local filesystem.

Configuration reaches the subprocess as environment variables set on the `ProcessBuilder`.
`NativeBinaryS3SmokeIT` uses this to pass `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_REGION`, `AWS_ENDPOINT_URL`, `AWS_PATH_STYLE`, `AWS_CONFIG_FILE` and
`AWS_SHARED_CREDENTIALS_FILE`; the AWS SDK embedded in the native image reads them at
runtime, so no code changes are required in the CLI itself.

The JVM S3 tests instead use `AbstractS3CommandTest`, which configures AWS via
`System.setProperty()`. That mechanism only works because those tests run the CLI in the
same JVM as the test — for the native subprocess, configuration has to arrive from the
outside, which is what the environment variables do.

## Build Changes

The Maven Failsafe plugin is configured in the `cli` module's main `<build>` section with
the `integration-test` and `verify` goals. Integration tests are skipped by default
(`skipITs=true`). The `native` profile sets `skipITs=false`, enabling the smoke tests only
during a native build.

## Running the Native Build Check Locally

The `native-build-check` CI job can be run locally using [act](https://github.com/nektos/act),
which executes GitHub Actions workflow jobs inside Docker containers.

Install via Homebrew:

```bash
brew install act
```

On macOS, the container needs access to the system CA bundle to trust corporate or
self-signed certificates when pulling dependencies:

```bash
security find-certificate -a -p /Library/Keychains/System.keychain > /tmp/act-certs.pem
security find-certificate -a -p ~/Library/Keychains/login.keychain-db >> /tmp/act-certs.pem
```

Then run the job:

```bash
act pull_request -j native-build-check \
  --container-architecture linux/amd64 \
  -P ubuntu-latest=catthehacker/ubuntu:act-latest \
  --container-options "-v $HOME/.m2:/root/.m2 -v /tmp/act-certs.pem:/tmp/act-certs.pem" \
  --env NODE_EXTRA_CA_CERTS=/tmp/act-certs.pem \
  --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
```

- `~/.m2` mount reuses the local Maven cache, avoiding a full dependency re-download on each run.
- `-P ubuntu-latest=catthehacker/ubuntu:act-latest` replaces `act`'s default minimal container image with one that includes Node.js, which is required by JavaScript-based GitHub Actions (`actions/upload-artifact`, `actions/download-artifact`, `actions/setup-java` cleanup). Real GitHub Actions runners include Node.js pre-installed.
- `NODE_EXTRA_CA_CERTS` makes Node.js trust the exported CA bundle.
- `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` tells Testcontainers to reach mapped container ports via `host.docker.internal` instead of the Docker bridge IP (`172.17.0.1`), which is unreachable from inside the `act` container on macOS with Docker Desktop.
