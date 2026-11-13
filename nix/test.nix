# The NixOS VM test: the service starts with one user, the generated password works once, the
# password change opens the API. Run with `nix flake check` or `nix build .#checks.<system>.vm`.
{ pkgs, module }:

pkgs.testers.runNixOSTest {
  name = "morningissue";
  # The module adds this flake's overlay to the machine's nixpkgs.
  node.pkgsReadOnly = false;

  nodes.machine = {
    imports = [ module ];
    services.morningissue = {
      enable = true;
      users = [ "alice" ];
      publicUrl = "http://machine:8417";
    };
    environment.systemPackages = [ pkgs.curl pkgs.jq ];
    virtualisation.memorySize = 1536;
  };

  testScript = ''
    import json

    api = "http://127.0.0.1:8417/api/v1"

    def call(method, path, body=None, token=None, expect=200):
        args = f"-s -o /tmp/body -w '%{{http_code}}' -X {method} {api}{path}"
        if token:
            args += f" -H 'Authorization: Bearer {token}'"
        if body is not None:
            args += f" -H 'Content-Type: application/json' --data-raw '{json.dumps(body)}'"
        status = machine.succeed(f"curl {args}").strip()
        text = machine.succeed("cat /tmp/body")
        assert status == str(expect), f"{method} {path}: expected {expect}, got {status}: {text}"
        return json.loads(text) if text.strip() else None

    machine.wait_for_unit("morningissue.service")
    machine.wait_for_open_port(8417)

    health = call("GET", "/health")
    assert health["ok"] is True and health["api"] == 1, health

    machine.succeed("test -f /var/lib/morningissue/initial-passwords/alice.txt")
    mode = machine.succeed("stat -L -c %a /var/lib/morningissue/initial-passwords/alice.txt").strip()
    assert mode == "600", mode
    password = machine.succeed("cat /var/lib/morningissue/initial-passwords/alice.txt").strip()
    assert len(password) == 20, password
    machine.succeed(f"journalctl -u morningissue | grep -F 'Created user \"alice\" with password \"{password}\"'")

    login = call("POST", "/auth/login", {
        "username": "alice", "password": password,
        "device": {"id": "vm-phone", "name": "VM phone", "kind": "PHONE"},
    })
    assert login["mustChangePassword"] is True, login
    token = login["token"]

    locked = call("GET", "/settings", token=token, expect=403)
    assert locked["error"] == "password_change_required", locked

    call("POST", "/auth/password", {"current": password, "new": "a much longer password"}, token=token, expect=204)
    machine.fail("test -e /var/lib/morningissue/initial-passwords/alice.txt")

    # The lockout is gone, and the settings service answers.
    settings = call("GET", "/settings", token=token)
    assert "resetTime" in settings, settings
    account = call("GET", "/account", token=token)
    assert account["mustChangePassword"] is False, account

    assert call("GET", "/kobo/config.json")["server"] == "http://machine:8417"

    # The database (and the SQLite native library it loads) survives a restart.
    machine.succeed("systemctl restart morningissue")
    machine.wait_for_open_port(8417)
    call("GET", "/account", token=token)
  '';
}
