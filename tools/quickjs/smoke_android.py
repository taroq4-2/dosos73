"""Exercise the app's QuickJS CLI contract and module restrictions on Android."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import zipfile


EJS_WHEEL_SHA256 = "79300e5fca7f937a1eeede11f0456862c1b41107ce1d726871e0207424f4bdb4"
PLAYER = """
(function () {
    var checkModules = Promise.all([
        import("std").then(m => m.loadFile(scriptArgs[1]), e => e.name),
        import("os").then(m => m.exec(["/system/bin/sh", "-c", "exit 17"]), e => e.name),
        import(scriptArgs[2]).then(m => m.marker, e => e.name),
        import(scriptArgs[3]).then(() => "native module loaded", e => e.name),
    ]).then(results => console.log(JSON.stringify({
        modules: results,
        fileHelper: typeof __loadScript,
        std: typeof std,
        os: typeof os,
    })));

    function playerFunction(_source, signatureKey, signature) {
        var params = Object.create(null);
        var url = {
            set(key, value) { params[key] = value; },
            get(key) { return params[key]; },
        };
        url.set("alr", "yes");
        if (signature)
            url.set(signatureKey, signature + "!");
        Object.setPrototypeOf(url, {
            transform() {
                if (params.n)
                    params.n = params.n.split("").reverse().join("");
            },
        });
        return url;
    }
}).call(this);
"""


def challenge_script(wheel):
    with wheel.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != EJS_WHEEL_SHA256:
            raise ValueError("Unexpected EJS wheel SHA-256")
    with zipfile.ZipFile(wheel) as package:
        root = "yt_dlp_ejs/yt/solver/"
        lib = package.read(root + "lib.min.js").decode("utf-8")
        core = package.read(root + "core.min.js").decode("utf-8")
    data = {
        "type": "player",
        "player": PLAYER,
        "output_preprocessed": True,
        "requests": [{"type": "n", "challenges": ["sample"]},
                     {"type": "sig", "challenges": ["a b"]}],
    }
    return (
        f"{lib}\nObject.assign(globalThis, lib);\n{core}\n"
        f"console.log(JSON.stringify(jsc({json.dumps(data)})));\n"
    )


def verify_challenge(output):
    lines = [json.loads(line) for line in output.splitlines() if line]
    if len(lines) != 2:
        raise ValueError(f"Unexpected QuickJS challenge output: {output}")
    challenge, restrictions = lines
    if not challenge.pop("preprocessed_player", None) or challenge != {
        "type": "result",
        "responses": [{"type": "result", "data": {"sample": "elpmas"}},
                      {"type": "result", "data": {"a b": "a b!"}}],
    }:
        raise ValueError(f"QuickJS EJS challenge did not pass: {challenge}")
    if restrictions != {
        "modules": ["ReferenceError"] * 4,
        "fileHelper": "undefined", "std": "undefined", "os": "undefined",
    }:
        raise ValueError(f"QuickJS exposed a forbidden host capability: {restrictions}")
    return challenge, restrictions


def smoke(adb, serial, binary, wheel):
    command = [adb]
    if serial:
        command.extend(["-s", serial])

    def call(*args, check=True):
        return subprocess.run([*command, *args], check=check, capture_output=True,
                              text=True, timeout=45)

    def shell(*args, check=True):
        return call("shell", shlex.join(args), check=check)

    folder = shell("mktemp", "-d", "/data/local/tmp/dosos-qjs.XXXXXX").stdout.strip()
    if not re.fullmatch(r"/data/local/tmp/dosos-qjs\.[a-zA-Z0-9]{6}", folder):
        raise ValueError(f"Unexpected Android smoke directory: {folder!r}")
    try:
        remote_binary = f"{folder}/libqjs.so"
        call("push", binary, remote_binary)
        shell("chmod", "700", remote_binary)
        help_result = shell(remote_binary, "--help", check=False)
        if (help_result.returncode != 1
                or not help_result.stdout.startswith("QuickJS version 2026-06-04\n")
                or "--script" not in help_result.stdout):
            raise ValueError(f"QuickJS help probe failed: {help_result}")
        eval_result = shell(remote_binary, "-e", "console.log(String(2n ** 10n))")
        if eval_result.stdout.strip() != "1024":
            raise ValueError(f"QuickJS expression probe failed: {eval_result}")
        static_import = shell(remote_binary, "--module", "-e", 'import "std";', check=False)
        if static_import.returncode == 0 or "ReferenceError" not in static_import.stderr:
            raise ValueError(f"QuickJS static import was accepted: {static_import}")
        with tempfile.TemporaryDirectory(prefix="dosos-qjs-smoke-") as temporary:
            work = Path(temporary)
            challenge_file = work / "challenge.js"
            module_file = work / "module.mjs"
            marker_file = work / "marker.txt"
            challenge_file.write_text(challenge_script(wheel), encoding="utf-8", newline="\n")
            module_file.write_text('export const marker = "filesystem module loaded";\n')
            marker_file.write_text("file read succeeded\n")
            for fixture in (challenge_file, module_file, marker_file):
                call("push", fixture, f"{folder}/{fixture.name}")
        result = shell(remote_binary, "--script", f"{folder}/challenge.js",
                       f"{folder}/marker.txt", f"{folder}/module.mjs", remote_binary)
        challenge, restrictions = verify_challenge(result.stdout)
        with binary.open("rb") as local_binary:
            binary_sha256 = hashlib.file_digest(local_binary, "sha256").hexdigest()
        print(json.dumps({
            "abi": shell("getprop", "ro.product.cpu.abi").stdout.strip(),
            "api": shell("getprop", "ro.build.version.sdk").stdout.strip(),
            "binarySha256": binary_sha256,
            "ejsWheelSha256": EJS_WHEEL_SHA256,
            "challenge": challenge,
            "restrictions": restrictions,
            "staticImportBlocked": True,
        }, indent=2))
    finally:
        shell("rm", "-rf", folder)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("ejs_wheel", type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    smoke(args.adb, args.serial, args.binary, args.ejs_wheel)
