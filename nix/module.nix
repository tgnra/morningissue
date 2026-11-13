# services.morningissue: the Morning Issue server as a systemd service.
#
# The initial passwords of new users are in `journalctl -u morningissue` and in
# <dataDir>/initial-passwords/<user>.txt (0600), until each user changes theirs.
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.morningissue;
  toml = pkgs.formats.toml { };

  generated = {
    server = {
      listen = cfg.listenAddress;
      port = cfg.port;
      dataDir = toString cfg.dataDir;
    }
    // lib.optionalAttrs (cfg.publicUrl != null) { publicUrl = cfg.publicUrl; };
    users.names = cfg.users;
  };

  configFile = toml.generate "morningissue.toml" (lib.recursiveUpdate generated cfg.settings);
  defaultDataDir = "/var/lib/morningissue";
in
{
  options.services.morningissue = {
    enable = lib.mkEnableOption "the Morning Issue server";

    package = lib.mkOption {
      type = lib.types.package;
      default = pkgs.morningissue-server;
      defaultText = lib.literalExpression "pkgs.morningissue-server";
      description = "The server package (from this flake's overlay or packages output).";
    };

    users = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      example = [ "tania" "sam" ];
      description = ''
        Account names. A new name gets a generated password on the next start; a name removed
        from the list is disabled and its data kept.
      '';
    };

    listenAddress = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = "Address to listen on. Keep it local and put a TLS reverse proxy in front.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 8417;
      description = "Port to listen on.";
    };

    publicUrl = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "https://news.example.org";
      description = "The address clients use, shown to Kobos (/kobo/config.json).";
    };

    dataDir = lib.mkOption {
      type = lib.types.path;
      default = defaultDataDir;
      description = ''
        Where the database, initial passwords and caches live. The default is managed by
        systemd's StateDirectory; another path must exist and be writable by the service.
      '';
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Open the port in the firewall.";
    };

    settings = lib.mkOption {
      type = toml.type;
      default = { };
      example = lib.literalExpression ''{ refresh.intervalMinutes = 60; kobo.imageCacheMb = 512; }'';
      description = "Further config.toml settings, merged over the generated ones.";
    };

    jvmOptions = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ "-Xmx512m" ];
      description = "Options for the JVM.";
    };
  };

  config = lib.mkIf cfg.enable {
    environment.etc."morningissue/config.toml".source = configFile;

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];

    systemd.services.morningissue = {
      description = "Morning Issue server";
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];
      restartTriggers = [ configFile ];

      # The bundled SQLite extracts its native library to java.io.tmpdir and loads it. systemd
      # mounts a DynamicUser's state directory noexec, so it goes to the service's private /tmp.
      environment = {
        JAVA_OPTS = lib.concatStringsSep " " ([ "-Djava.io.tmpdir=/tmp" ] ++ cfg.jvmOptions);
      };

      serviceConfig = {
        ExecStart = "${lib.getExe cfg.package} --config /etc/morningissue/config.toml serve";
        DynamicUser = true;
        Restart = "on-failure";
        RestartSec = 5;
        UMask = "0077";

        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        NoNewPrivileges = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        CapabilityBoundingSet = "";
      }
      // (
        if toString cfg.dataDir == defaultDataDir then
          { StateDirectory = "morningissue"; StateDirectoryMode = "0700"; }
        else
          { ReadWritePaths = [ (toString cfg.dataDir) ]; }
      );
    };
  };
}
