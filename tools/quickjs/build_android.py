"""Build the private, source-only QuickJS CLI with the pinned Android NDK."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile

VERSION = "2026-06-04"
SOURCE_NAME = f"quickjs-{VERSION}.tar.xz"
SOURCE_URL = f"https://bellard.org/quickjs/{SOURCE_NAME}"
SOURCE_SHA256 = "b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a"
PATCH_SHA256 = "1fafd33c6888b1c4833ee027ff2ec6e99642845e5c5d662c00e2df3bfd2e5efc"
NDK_VERSION = "28.2.13676358"
API = 29
ABI_TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", 183, 64),
    "armeabi-v7a": ("armv7a-linux-androideabi", 40, 32),
    "x86": ("i686-linux-android", 3, 32),
    "x86_64": ("x86_64-linux-android", 62, 64),
}
SOURCE_FILES = (
    "qjs.c", "quickjs.c", "quickjs-libc.c", "dtoa.c", "libregexp.c",
    "libunicode.c", "cutils.c",
)


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def require_hash(path, expected):
    if sha256(path) != expected:
        raise ValueError(f"SHA-256 mismatch for {path}")


def run(arguments, **kwargs):
    return subprocess.run(arguments, check=True, timeout=300, **kwargs)


def ndk_tools(ndk):
    properties = dict(
        line.split(" = ", 1)
        for line in (ndk / "source.properties").read_text().splitlines()
        if " = " in line
    )
    if properties.get("Pkg.Revision") != NDK_VERSION:
        raise ValueError(f"Android NDK {NDK_VERSION} is required")
    if sys.platform == "linux":
        return ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin", ""
    if sys.platform == "win32":
        return ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin", ".exe"
    raise ValueError("Use a Linux or Windows x86-64 host")


def verify_elf(binary, readelf, machine, bits):
    with binary.open("rb") as executable:
        header = executable.read(20)
    if (header[:6] != b"\x7fELF" + bytes((bits // 32, 1))
            or struct.unpack_from("<HH", header, 16) != (3, machine)):
        raise ValueError(f"{binary} is not the requested PIE target")
    details = run([readelf, "-h", "-l", "-d", "-n", "--dyn-syms", binary],
                  capture_output=True, text=True).stdout
    loads = []
    for row in re.findall(r"^\s+LOAD\s+([^\r\n]+)$", details, flags=re.MULTILINE):
        columns = row.split()
        offset, address, alignment = (int(value, 16)
                                      for value in (columns[0], columns[1], columns[-1]))
        if alignment < 16384 or offset % 16384 != address % 16384:
            raise ValueError(f"{binary} has an incompatible ELF load segment")
        loads.append(alignment)
    if not loads or "GNU_RELRO" not in details or "BIND_NOW" not in details:
        raise ValueError(f"{binary} lacks expected ELF segments or linker hardening")
    linker = "/system/bin/linker64" if bits == 64 else "/system/bin/linker"
    if f"Requesting program interpreter: {linker}]" not in details:
        raise ValueError(f"{binary} lacks the expected Android ELF interpreter")
    if "dlopen" in details or "qjsc_repl" in details:
        raise ValueError(f"{binary} retains an unexpected native loader or REPL")
    return {"bits": bits, "machine": machine, "loadAlignments": loads}, details


def build(abi, ndk, output, supplied_source):
    tools, suffix = ndk_tools(ndk)
    clang = tools / f"clang{suffix}"
    strip = tools / f"llvm-strip{suffix}"
    readelf = tools / f"llvm-readelf{suffix}"
    patch = Path(__file__).with_name("restricted-cli.patch")
    require_hash(patch, PATCH_SHA256)
    target, machine, bits = ABI_TARGETS[abi]
    output.mkdir(parents=True, exist_ok=False)
    source_archive = output / SOURCE_NAME
    if supplied_source:
        shutil.copyfile(supplied_source, source_archive)
    else:
        run(["curl", "--fail", "--silent", "--show-error", "--location",
             "--proto", "=https", "--retry", "3", "--connect-timeout", "25",
             "--max-time", "240", "-o", source_archive, SOURCE_URL])
    require_hash(source_archive, SOURCE_SHA256)
    with tempfile.TemporaryDirectory(prefix="dosos-qjs-") as temporary:
        with tarfile.open(source_archive) as archive:
            archive.extractall(temporary, filter="data")
        source = Path(temporary) / f"quickjs-{VERSION}"
        if (source / "VERSION").read_text().strip() != VERSION:
            raise ValueError("Unexpected QuickJS version in the source archive")
        with patch.open("rb") as patch_file:
            run(["patch", "--batch", "--forward", "--fuzz=0", "-p1"],
                cwd=source, stdin=patch_file)
        flags = [
            f"--target={target}{API}", "-O2", "-fwrapv", "-funsigned-char",
            "-fstack-protector-strong", "-fPIE", "-fvisibility=hidden",
            "-ffunction-sections", "-fdata-sections", "-D_GNU_SOURCE",
            f'-DCONFIG_VERSION="{VERSION}"', "-Werror=implicit-function-declaration",
            "-Werror=unguarded-availability", "-pie",
            "-Wl,-z,max-page-size=16384,-z,relro,-z,now,--gc-sections",
        ]
        if abi == "armeabi-v7a":
            flags.append("-mthumb")
        binary = output / "libqjs.so"
        run([clang, *flags, *SOURCE_FILES, "-lm", "-ldl", "-o", binary], cwd=source)
        run([strip, "--strip-unneeded", binary])
        shutil.copyfile(source / "LICENSE", output / "QuickJS-LICENSE")
    alignment, details = verify_elf(binary, readelf, machine, bits)
    (output / "elf.txt").write_text(details, encoding="utf-8")
    shutil.copyfile(patch, output / patch.name)
    receipt = {
        "version": VERSION,
        "abi": abi,
        "api": API,
        "source": {"url": SOURCE_URL, "file": SOURCE_NAME, "sha256": SOURCE_SHA256},
        "patch": {"file": patch.name, "sha256": PATCH_SHA256},
        "ndk": {"version": NDK_VERSION, "host": sys.platform,
                "clang": run([clang, "--version"], capture_output=True, text=True).stdout.splitlines()[0]},
        "compile": [*flags, *SOURCE_FILES, "-lm", "-ldl", "-o", binary.name],
        "binary": {"file": binary.name, "sha256": sha256(binary),
                   "bytes": binary.stat().st_size, "elf": alignment},
    }
    (output / "quickjs-build.json").write_text(json.dumps(receipt, indent=2) + "\n",
                                              encoding="utf-8")
    print(f"{abi} {VERSION} {binary.stat().st_size} bytes {receipt['binary']['sha256']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABI_TARGETS, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source", type=Path, help="Reuse the exact pinned source archive")
    args = parser.parse_args()
    build(args.abi, args.ndk.resolve(), args.output.resolve(), args.source)
