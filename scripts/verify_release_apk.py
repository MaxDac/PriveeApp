"""Verify the unsigned release APK before it is passed to the signing job."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def verify_identity(badging, version_name, version_code):
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        badging,
        re.MULTILINE,
    )
    if not package or package.groups() != (
        "com.privee.app", str(version_code), version_name
    ):
        raise ValueError("APK application ID or version does not match the release.")
    if re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE):
        raise ValueError("A debuggable APK cannot be released.")


LIBSIGNAL_LOCK = Path("libsignal") / "source.lock.json"
LIBSIGNAL_JNI = "lib/arm64-v8a/libsignal_jni.so"
LIBSIGNAL_NOTICES = "assets/acknowledgments/libsignal.md"
# AOSP androidx.graphics:graphics-path (pulled in by Compose), built from AOSP sources.
EXPECTED_NATIVES = sorted([LIBSIGNAL_JNI, "lib/arm64-v8a/libandroidx.graphics.path.so"])
DESKTOP_NATIVE = re.compile(r"(^|/)(libsignal_jni[^/]*\.(so|dylib)|signal_jni[^/]*\.dll)$")


def verify_assets(apk, root=ROOT):
    lock = json.loads((root / LIBSIGNAL_LOCK).read_text(encoding="utf-8"))
    pinned = lock["build"].get("expectedSha256")
    if not pinned:
        raise ValueError(f"{LIBSIGNAL_LOCK.as_posix()} does not pin build.expectedSha256.")
    pinned_jni = pinned.get("build/output/jniLibs/arm64-v8a/libsignal_jni.so")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries.")
        natives = sorted(name for name in names if name.startswith("lib/"))
        if natives != EXPECTED_NATIVES:
            raise ValueError(f"APK must ship exactly {EXPECTED_NATIVES}, found {natives}.")
        if hashlib.sha256(archive.read(LIBSIGNAL_JNI)).hexdigest() != pinned_jni:
            raise ValueError(
                f"{LIBSIGNAL_JNI} is not the source-built libsignal pinned in {LIBSIGNAL_LOCK.as_posix()}."
            )
        desktop = sorted(name for name in names if not name.startswith("lib/") and DESKTOP_NATIVE.search(name))
        if desktop:
            raise ValueError(f"APK contains libsignal desktop natives: {desktop}")
        if not archive.read(LIBSIGNAL_NOTICES):
            raise ValueError(f"Empty required APK entry: {LIBSIGNAL_NOTICES}")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    badging = subprocess.check_output(
        [str(args.build_tools / ("aapt2" + suffix)), "dump", "badging", str(args.apk)],
        text=True,
        encoding="utf-8",
    )
    verify_identity(badging, args.version_name, args.version_code)
    verify_assets(args.apk)
    subprocess.run(
        [str(args.build_tools / ("zipalign" + suffix)), "-c", "-P", "16", "4", str(args.apk)],
        check=True,
    )
    print("Release APK identity, source-built libsignal, notices and native alignment verified.")


if __name__ == "__main__":
    main()
