#!/usr/bin/env python3
"""Assert that lib/arm64-v8a/libmeshd.so is packaged in the debug APK.

Robust by construction: uses Python's zipfile (real uncompressed sizes), instead of
parsing `zipinfo -l` / `unzip -lv` text output whose column order differs between
implementations (that fragile parsing is what made previous runs fail).
Prints an explicit "NAME<TAB>UNCOMPRESSED<TAB>COMPRESSED" table for every entry,
so a failure is self-diagnosing.
"""
import sys
import zipfile

TARGET = "lib/arm64-v8a/libmeshd.so"
MIN_BYTES = 1_000_000

apk = sys.argv[1]
with zipfile.ZipFile(apk) as z:
    infos = z.infolist()

    print("---- every native lib in the APK (name<TAB>uncompressed<TAB>compressed) ----")
    nats = [i for i in infos if i.filename.startswith("lib/") and i.filename.endswith(".so")]
    for i in nats:
        print(f"{i.filename}\t{i.file_size}\t{i.compress_size}")
    if not nats:
        print("(none found)")

    print("---- every meshd mention (lib AND assets) ----")
    mentions = [i for i in infos if "meshd" in i.filename.lower()]
    for i in mentions:
        print(f"{i.filename}\t{i.file_size}\t{i.compress_size}")
    if not mentions:
        print("(no meshd entry at all)")

    if TARGET not in z.namelist():
        print(f"::error::meshd not packaged in the APK under {TARGET}")
        print("EVIDENCE entries with 'meshd':", [i.filename for i in mentions])
        sys.exit(1)

    size = z.getinfo(TARGET).file_size
    # Machine-readable key line, stable and matched by the workflow grep:
    #   meshd inside APK = <bytes> bytes (uncompressed)
    print(f"meshd inside APK = {size} bytes (uncompressed)")

    with z.open(TARGET) as fh:
        magic = fh.read(4)
    if magic != b"\x7fELF":
        print(f"::error::packaged libmeshd.so is not an ELF file (magic={magic!r})")
        sys.exit(1)
    print("packaged libmeshd.so magic = ELF (ok)")

if size <= MIN_BYTES:
    print(f"::error::packaged meshd too small ({size} bytes)")
    sys.exit(1)

print("OK: meshd arm64-v8a is packaged, ELF, and above the size gate")
