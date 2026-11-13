# The Morning Issue server, built with Gradle from the repository with -PserverOnly (no Android
# SDK). Dependencies come from nix/deps.json through the mitm cache; refresh it after changing
# dependencies with:
#   nix build .#morningissue-server.mitmCache.updateScript && ./result
{
  lib,
  stdenv,
  gradle_9,
  jdk21_headless,
  makeWrapper,
  version ? "0.1.0",
}:

let
  gradle = gradle_9;
  fs = lib.fileset;
in
stdenv.mkDerivation (finalAttrs: {
  pname = "morningissue-server";
  inherit version;

  # Only what the server build reads, so app or Kobo changes do not rebuild it.
  src = fs.toSource {
    root = ../.;
    fileset = fs.unions [
      ../settings.gradle.kts
      ../build.gradle.kts
      ../gradle.properties
      ../gradle/libs.versions.toml
      ../core
      ../server
      ../assets
    ];
  };

  nativeBuildInputs = [
    gradle
    makeWrapper
  ];

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  __darwinAllowLocalNetworking = true;

  gradleFlags = [
    "-PserverOnly"
    "-PserverVersion=${finalAttrs.version}"
    "-Dorg.gradle.java.home=${jdk21_headless}"
  ];
  gradleBuildTask = ":server:installDist";
  # Record dependencies by running the real build: KSP resolves its own at execution time, which
  # the default nixDownloadDeps task does not see.
  gradleUpdateTask = ":server:installDist";

  # The tests run in CI and the VM test in checks.
  doCheck = false;

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/morningissue $out/bin
    cp -r server/build/install/morningissue-server/* $out/share/morningissue/
    makeWrapper $out/share/morningissue/bin/morningissue-server $out/bin/morningissue-server \
      --set JAVA_HOME ${jdk21_headless}
    runHook postInstall
  '';

  meta = {
    description = "Self-hosted server for Morning Issue: feeds, editions and Kobo sync";
    homepage = "https://github.com/tgnra/morningissue";
    license = lib.licenses.mit;
    mainProgram = "morningissue-server";
    platforms = [ "x86_64-linux" "aarch64-linux" ];
    sourceProvenance = with lib.sourceTypes; [
      fromSource
      binaryBytecode # Maven dependencies
      binaryNativeCode # the bundled SQLite library inside sqlite-bundled
    ];
  };
})
