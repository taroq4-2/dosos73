"""Overlay verified Expat 2.10.0 libraries in an unsigned compact APK."""

import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import stat
from zipfile import ZipFile

from assemble_rebuilt_runtime import rewrite_apk
from build_expat_runtime import ABIS, SONAME
from fetch_runtime_overrides import validate_elf


OLD_NAME = "libexpat.so.1.13.0"
NEW_NAME = "libexpat.so.1.13.1"
ALIASES = {"usr/lib/libexpat.so", "usr/lib/libexpat.so.1"}


def replace_library(runtime, library):
    if not library.startswith(b"\x7fELF") or b"expat_2.10.0" not in library:
        raise ValueError("Expected an Expat 2.10.0 ELF library")
    data = io.BytesIO()
    old_path = "usr/lib/" + OLD_NAME
    new_path = "usr/lib/" + NEW_NAME
    with ZipFile(io.BytesIO(runtime)) as old, ZipFile(data, "w", compresslevel=9) as new:
        names = old.namelist()
        if len(names) != len(set(names)) or new_path in names or not ALIASES <= set(names):
            raise ValueError("Unexpected Expat runtime paths")
        if b"expat_2.9.0" not in old.read(old_path):
            raise ValueError("Expected the audited Expat 2.9.0 input")
        for original in old.infolist():
            info = copy.copy(original)
            contents = old.read(original)
            if info.filename == old_path:
                info.filename = new_path
                contents = library
            elif info.filename in ALIASES:
                if not stat.S_ISLNK(info.external_attr >> 16) or contents != OLD_NAME.encode():
                    raise ValueError(f"Unexpected alias {info.filename}")
                contents = NEW_NAME.encode()
            new.writestr(info, contents, compresslevel=9)
    return data.getvalue()


def assemble(apk, libraries, output, proof):
    changes = {}
    receipts = {}
    with ZipFile(apk) as old:
        for abi in ABIS:
            path = f"lib/{abi}/libpython.zip.so"
            library_path = libraries / abi / SONAME
            library = library_path.read_bytes()
            validate_elf(library, abi, library_path)
            changes[path] = replace_library(old.read(path), library)
            receipts[abi] = {
                "path": path + "!/usr/lib/" + NEW_NAME,
                "sha256": hashlib.sha256(library).hexdigest(),
                "bytes": len(library),
            }
    rewrite_apk(apk, output, changes)
    proof.write_text(json.dumps(receipts, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("apk", "libraries", "output", "proof"):
        parser.add_argument(f"--{option}", type=Path, required=True)
    args = parser.parse_args()
    assemble(args.apk, args.libraries, args.output, args.proof)
