{
  description = "Morning Issue: the self-hosted server, its NixOS module and a VM test";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      overlays.default = final: _prev: {
        morningissue-server = final.callPackage ./nix/package.nix { };
      };

      packages = forAllSystems (
        pkgs:
        let
          server = pkgs.callPackage ./nix/package.nix { };
        in
        {
          morningissue-server = server;
          default = server;
          # A container image without Docker: `nix build .#docker && docker load < result`.
          docker = pkgs.dockerTools.buildLayeredImage {
            name = "morningissue-server";
            tag = server.version;
            contents = [ pkgs.cacert ];
            extraCommands = "mkdir -p data tmp && chmod 1777 tmp";
            config = {
              Entrypoint = [ "${server}/bin/morningissue-server" ];
              Cmd = [ "serve" ];
              Env = [ "MORNINGISSUE_DATA_DIR=/data" ];
              ExposedPorts."8417/tcp" = { };
              Volumes."/data" = { };
              User = "10001:10001";
            };
          };
        }
      );

      nixosModules.default = {
        imports = [ ./nix/module.nix ];
        nixpkgs.overlays = [ self.overlays.default ];
      };

      checks = forAllSystems (pkgs: {
        vm = import ./nix/test.nix {
          inherit pkgs;
          module = self.nixosModules.default;
        };
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk21 pkgs.gradle_9 pkgs.cargo pkgs.rustc ];
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
