"""Build the pinned Expat update for the app's Android Python runtime."""

import argparse
import hashlib
from pathlib import Path
import platform
import subprocess
import tarfile


ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
SOURCE_SHA256 = "44e0139015f1c9908d0876b5516235136f16b14932d8f6634a16737a12742f53"
SONAME = "libexpat.so.1"


def build(archive, ndk, output, cmake, ninja):
    with archive.open("rb") as file:
        if hashlib.file_digest(file, "sha256").hexdigest() != SOURCE_SHA256:
            raise ValueError("Expected the official expat-2.10.0.tar.bz2")
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    suffix = ".exe" if platform.system() == "Windows" else ""
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    output.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive) as source:
        source.extractall(output / "source", filter="data")
    for abi in ABIS:
        work = output / "build" / abi
        subprocess.run([
            cmake, "-S", output / "source/expat-2.10.0", "-B", work, "-G", "Ninja",
            f"-DCMAKE_MAKE_PROGRAM={ninja}",
            f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-29", "-DCMAKE_BUILD_TYPE=MinSizeRel",
            "-DEXPAT_BUILD_TOOLS=OFF", "-DEXPAT_BUILD_TESTS=OFF", "-DEXPAT_BUILD_EXAMPLES=OFF",
            "-DEXPAT_BUILD_DOCS=OFF", "-DEXPAT_BUILD_PKGCONFIG=OFF", "-DEXPAT_SHARED_LIBS=ON",
            "-DNO_SONAME=ON", f"-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-soname,{SONAME},-z,max-page-size=16384",
        ], check=True)
        subprocess.run([cmake, "--build", work, "--parallel", "3"], check=True)
        library = output / abi / SONAME
        library.parent.mkdir()
        subprocess.run([tools / f"llvm-strip{suffix}", "--strip-unneeded", "-o", library,
                        work / "libexpat.so"], check=True)
        dynamic = subprocess.check_output([tools / f"llvm-readelf{suffix}", "-d", library], text=True)
        if not any("(SONAME)" in line and f"[{SONAME}]" in line for line in dynamic.splitlines()):
            raise ValueError(f"Unexpected ELF SONAME for {abi}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("archive", "ndk", "output"):
        parser.add_argument(f"--{option}", type=Path, required=True)
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--ninja", required=True, type=Path)
    args = parser.parse_args()
    build(args.archive.resolve(), args.ndk.resolve(), args.output.resolve(), args.cmake, args.ninja.resolve())
