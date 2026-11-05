"""Build the pinned Cryptodome package for Android API 29 without a target pip."""

import argparse
import ast
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


LOCK = Path(__file__).with_name("crypto-runtime-lock.json")
PREFIX = "usr/lib/python3.12/site-packages/"
TARGETS = {
    "arm64-v8a": ("aarch64-linux-android29", "AArch64", 64),
    "armeabi-v7a": ("armv7a-linux-androideabi29", "ARM", 32),
    "x86": ("i686-linux-android29", "Intel 80386", 32),
    "x86_64": ("x86_64-linux-android29", "Advanced Micro Devices X86-64", 64),
}
X86_EXTENSIONS = {
    "Cryptodome/Cipher/_raw_aesni.abi3.so": ["-maes"],
    "Cryptodome/Hash/_ghash_clmul.abi3.so": [
        "-mpclmul", "-mssse3", "-DHAVE_WMMINTRIN_H", "-DHAVE_TMMINTRIN_H",
    ],
}


def file_record(path):
    with path.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"sha256": checksum, "bytes": path.stat().st_size}


def safe_source_path(source, name, directory=False):
    relative = PurePosixPath(name)
    if relative.is_absolute() or ".." in relative.parts or "\\" in name:
        raise ValueError(f"Unexpected compiler input path: {name}")
    path = source.joinpath(*relative.parts)
    if not (path.is_dir() if directory else path.is_file()) or path.is_symlink():
        raise ValueError(f"Missing compiler input path: {name}")
    return relative.as_posix()


def read_extensions(source):
    # Read only the literal source and include lists. Host setuptools would
    # otherwise probe and configure optimizations for the host's CPU width.
    setup = ast.parse((source / "setup.py").read_text(encoding="utf-8"))
    definitions = [
        node.value for node in setup.body
        if isinstance(node, ast.Assign) and len(node.targets) == 1
        and isinstance(node.targets[0], ast.Name) and node.targets[0].id == "ext_modules"
    ]
    if len(definitions) != 1 or not isinstance(definitions[0], ast.List):
        raise ValueError("Unexpected upstream ext_modules declaration")
    result = {}
    for call in definitions[0].elts:
        if (not isinstance(call, ast.Call) or not isinstance(call.func, ast.Name)
                or call.func.id != "Extension" or len(call.args) != 1):
            raise ValueError("Unexpected extension declaration")
        name = ast.literal_eval(call.args[0])
        values = {kw.arg: ast.literal_eval(kw.value) for kw in call.keywords}
        if (not re.fullmatch(r"Crypto\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", name)
                or len(values) != len(call.keywords)
                or set(values) - {"sources", "include_dirs", "py_limited_api"}
                or values.get("py_limited_api") is not True
                or not values.get("sources")):
            raise ValueError("Unexpected extension metadata")
        path = "Cryptodome/" + name.removeprefix("Crypto.").replace(".", "/") + ".abi3.so"
        if path in result:
            raise ValueError(f"Duplicate extension: {path}")
        result[path] = {
            "sources": [safe_source_path(source, name) for name in values["sources"]],
            "includes": [safe_source_path(source, name, directory=True)
                         for name in values.get("include_dirs", [])],
        }
    if len(result) != 42 or not X86_EXTENSIONS.keys() <= result.keys():
        raise ValueError("Unexpected Cryptodome extension inventory")
    return result


def target_flags(abi, module):
    target, _, bits = TARGETS[abi]
    flags = [
        f"--target={target}", "-O3", "-fPIC", "-fstack-protector-strong",
        "-ffunction-sections", "-fdata-sections", "-Werror=implicit-function-declaration",
        "-DHAVE_STDINT_H", "-DPYCRYPTO_LITTLE_ENDIAN", f"-DSYS_BITS={bits}",
        "-DLTC_NO_ASM", "-DHAVE_POSIX_MEMALIGN",
    ]
    if bits == 64:
        flags.append("-DHAVE_UINT128")
    if abi in {"x86", "x86_64"}:
        flags.extend(["-DHAVE_CPUID_H", "-DHAVE_X86INTRIN_H", "-DUSE_SSE2", "-msse2"])
        if bits == 32:
            flags.append("-mstackrealign")
        flags.extend(X86_EXTENSIONS.get(module, []))
    return flags


def inspect_elf(readelf, library, abi):
    text = subprocess.check_output(
        [readelf, "--elf-output-style=GNU", "-W", "-h", "-l", "-d", "--dyn-syms", library],
        encoding="utf-8",
    )
    _, machine, bits = TARGETS[abi]
    needed = sorted(re.findall(r"Shared library: \[([^]]+)\]", text))
    loads = [int(row.split()[-1], 16) for row in text.splitlines() if row.lstrip().startswith("LOAD ")]
    exports = sorted(set(re.findall(
        r"(?m)^\s*\d+:\s+[0-9a-f]+\s+\d+\s+(?:FUNC|OBJECT)\s+(?:GLOBAL|WEAK)\s+(?:DEFAULT|PROTECTED)\s+\d+\s+(\S+)",
        text,
    )))
    if (not re.search(rf"(?m)^\s*Machine:\s*{re.escape(machine)}\s*$", text)
            or not re.search(rf"(?m)^\s*Class:\s*ELF{bits}\s*$", text)
            or not re.search(r"(?m)^\s*Type:\s*DYN\s", text)
            or re.search(r"\((?:RPATH|RUNPATH|TEXTREL)\)", text)
            or not loads or min(loads) < 16384 or not exports
            or not set(needed) <= {"libc.so", "libm.so", "libdl.so"}):
        raise ValueError(f"Unexpected ELF loader requirements: {abi}/{library.name}")
    return {"needed": needed, "load_alignment": loads, "exports": exports}


def build_extension(source, staging, abi, module, recipe, tools):
    clang, readelf, strip = tools
    output = staging / module
    output.parent.mkdir(parents=True, exist_ok=True)
    flags = target_flags(abi, module)
    includes = ["-I" + name for name in recipe["includes"]]
    link = "-Wl,-z,max-page-size=16384,-z,relro,-z,now,--no-undefined,--gc-sections,--build-id=sha1"
    subprocess.run(
        [clang, *flags, *includes, *recipe["sources"], "-shared", link, "-o", output],
        cwd=source, check=True,
    )
    subprocess.run([strip, "--strip-unneeded", output], check=True)
    return module, {
        **file_record(output), **inspect_elf(readelf, output, abi),
        "compiler_flags": flags, "linker_flags": ["-shared", link], **recipe,
    }


def write_runtime(folder, payload):
    archive = folder / "cryptodome-runtime.zip"
    with ZipFile(archive, "w", compression=ZIP_DEFLATED, compresslevel=9) as output:
        for name, path in sorted(payload.items()):
            member = ZipInfo(PREFIX + name, date_time=(1980, 1, 1, 0, 0, 0))
            member.create_system = 3
            member.external_attr = (stat.S_IFREG | 0o644) << 16
            output.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED, compresslevel=9)
    return file_record(archive)


def build_abi(abi, source, root, extensions, python_sources, tools, jobs, lock):
    folder = root / abi
    folder.mkdir(parents=True)
    staging = folder / "compiled"
    selected = [name for name in sorted(extensions) if not abi.startswith("arm") or name not in X86_EXTENSIONS]
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [pool.submit(build_extension, source, staging, abi, name, extensions[name], tools) for name in selected]
        native = dict(job.result() for job in futures)
    archive = write_runtime(folder, python_sources | {name: staging / name for name in native})
    proof = {
        "schema": "dosos73-cryptodomex-build-v1", "abi": abi, "target": TARGETS[abi][0],
        "lock": lock, "archive": archive,
        "python": {PREFIX + name: file_record(path) for name, path in python_sources.items()},
        "elf": {PREFIX + name: record for name, record in native.items()},
    }
    (folder / "native-build-proof.json").write_text(
        json.dumps(proof, indent=2, sort_keys=True) + "\n", encoding="utf-8",
    )
    shutil.rmtree(staging)
    print(f"{abi}: {len(native)} ELF files, {len(python_sources)} Python files, {archive['sha256']}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("ndk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--jobs", type=int, choices=range(1, 9), default=4)
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if file_record(args.source)["sha256"] != lock["source"]["sha256"]:
        raise ValueError("PyCryptodomex source SHA-256 differs from the lock")
    ndk_properties = (args.ndk / "source.properties").read_text(encoding="utf-8")
    if f"Pkg.Revision = {lock['ndk']['revision']}" not in ndk_properties.splitlines():
        raise ValueError("Unexpected NDK revision")
    if lock["ndk"]["api_level"] != 29 or lock["version"] != "3.24.1":
        raise ValueError("Revalidate the recipe for a different API level or source")
    ndk = args.ndk.resolve() / "toolchains/llvm/prebuilt"
    host, = [folder for folder in ndk.iterdir() if folder.is_dir()]
    suffix = ".exe" if (host / "bin/clang.exe").is_file() else ""
    tools = tuple(host / "bin" / (name + suffix) for name in ("clang", "llvm-readelf", "llvm-strip"))
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    with tarfile.open(args.source) as archive:
        archive.extractall(root, filter="data")
    source = root / "pycryptodomex-3.24.1"
    license_file = source / "LICENSE.rst"
    if file_record(license_file)["sha256"] != lock["license_sha256"]:
        raise ValueError("Upstream license differs from the lock")
    shutil.copyfile(license_file, root / "LICENSE.rst")
    shutil.copyfile(LOCK, root / LOCK.name)
    shutil.copyfile(args.source, root / lock["source"]["filename"])
    (root / "compiler-version.txt").write_text(
        subprocess.check_output([tools[0], "--version"], encoding="utf-8"), encoding="utf-8",
    )
    extensions = read_extensions(source)
    python_root = source / "lib"
    python_sources = {file.relative_to(python_root).as_posix(): file
                      for file in (python_root / "Cryptodome").rglob("*.py")
                      if "SelfTest" not in file.relative_to(python_root).parts}
    if len(python_sources) != 109:
        raise ValueError("Unexpected Cryptodome Python module inventory")
    for abi in TARGETS:
        build_abi(abi, source, root, extensions, python_sources, tools, args.jobs, lock)
    shutil.rmtree(source)


if __name__ == "__main__":
    main()
