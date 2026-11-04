{
  description = "silverbullet-android: unofficial Android shell + quick capture for a SilverBullet server";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { android_sdk.accept_license = true; allowUnfree = true; };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      make-avd = pkgs.writeShellScriptBin "make-avd" ''
        avdmanager list avd | grep -q "Name: sb" && { echo "AVD sb exists"; exit 0; }
        echo no | avdmanager create avd -n sb -k "system-images;android-35;google_apis;x86_64" -d pixel_7
      '';
      run-emulator = pkgs.writeShellScriptBin "run-emulator" ''
        exec emulator -avd sb -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect "$@"
      '';
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk17
          pkgs.gradle
          pkgs.android-tools
          pkgs.silverbullet # local test server
          android.androidsdk
          make-avd
          run-emulator
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        # AGP downloads a dynamically linked aapt2 that cannot run on NixOS; use the SDK's.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/35.0.0/aapt2";
        shellHook = ''
          export ANDROID_USER_HOME="$PWD/.android"
          export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
          mkdir -p "$ANDROID_AVD_HOME"
        '';
      };
    };
}
