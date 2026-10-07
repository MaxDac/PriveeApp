import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile


spec = importlib.util.spec_from_file_location(
    "verify_release_apk", Path(__file__).resolve().parents[1] / "verify_release_apk.py"
)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class IdentityTests(unittest.TestCase):
    BADGING = "package: name='com.privee.app' versionCode='2' versionName='0.1.0' platformBuildVersionName='36'\n"

    def test_release(self):
        verifier.verify_identity(self.BADGING, "0.1.0", 2)

    def test_wrong_identity(self):
        for badging in ("", self.BADGING.replace("com.privee.app", "com.example"),
                        self.BADGING.replace("versionCode='2'", "versionCode='3'"),
                        self.BADGING.replace("0.1.0", "0.2.0")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                verifier.verify_identity(badging, "0.1.0", 2)

    def test_debuggable(self):
        with self.assertRaisesRegex(ValueError, "debuggable"):
            verifier.verify_identity(self.BADGING + "application-debuggable\n", "0.1.0", 2)


class AssetTests(unittest.TestCase):
    JNI = "lib/arm64-v8a/libsignal_jni.so"

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "test.apk"
        self.native = b"source-built-libsignal"
        self.write_lock({"build/output/jniLibs/arm64-v8a/libsignal_jni.so": hashlib.sha256(self.native).hexdigest()})
        self.entries = {
            "classes.dex": b"dex",
            self.JNI: self.native,
            "lib/arm64-v8a/libandroidx.graphics.path.so": b"androidx",
            "assets/acknowledgments/libsignal.md": b"notices",
        }

    def write_lock(self, expected):
        (self.root / "libsignal").mkdir(exist_ok=True)
        (self.root / "libsignal" / "source.lock.json").write_text(json.dumps({"build": {"expectedSha256": expected}}))

    def write_apk(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)

    def test_valid(self):
        self.write_apk()
        verifier.verify_assets(self.apk, self.root)

    def test_unpinned_lock(self):
        self.write_lock(None)
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "does not pin"):
            verifier.verify_assets(self.apk, self.root)

    def test_native_differs_from_pin(self):
        self.entries[self.JNI] = b"maven-prebuilt"
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "not the source-built"):
            verifier.verify_assets(self.apk, self.root)

    def test_unexpected_or_missing_natives(self):
        for name in ("lib/x86_64/libsignal_jni.so", "lib/arm64-v8a/libsignal_jni_testing.so",
                     "lib/arm64-v8a/libother.so"):
            with self.subTest(name=name):
                self.entries[name] = b"native"
                self.write_apk()
                with self.assertRaisesRegex(ValueError, "must ship exactly"):
                    verifier.verify_assets(self.apk, self.root)
                del self.entries[name]
        del self.entries[self.JNI]
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "must ship exactly"):
            verifier.verify_assets(self.apk, self.root)

    def test_desktop_natives(self):
        for name in ("libsignal_jni_amd64.so", "libsignal_jni_aarch64.dylib", "signal_jni_amd64.dll"):
            with self.subTest(name=name):
                self.entries[name] = b"desktop"
                self.write_apk()
                with self.assertRaisesRegex(ValueError, "desktop"):
                    verifier.verify_assets(self.apk, self.root)
                del self.entries[name]

    def test_missing_or_empty_notices(self):
        self.entries["assets/acknowledgments/libsignal.md"] = b""
        self.write_apk()
        with self.assertRaisesRegex(ValueError, "Empty"):
            verifier.verify_assets(self.apk, self.root)
        del self.entries["assets/acknowledgments/libsignal.md"]
        self.write_apk()
        with self.assertRaises(KeyError):
            verifier.verify_assets(self.apk, self.root)

    def test_duplicate_entry(self):
        self.write_apk()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr("classes.dex", b"dex")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verifier.verify_assets(self.apk, self.root)

    def test_repository_lock_pins_only_the_shipped_library(self):
        lock = json.loads((verifier.ROOT / "libsignal" / "source.lock.json").read_text(encoding="utf-8"))
        self.assertEqual(lock["build"]["output"], "build/output/jniLibs/arm64-v8a/libsignal_jni.so")
        expected = lock["build"]["expectedSha256"]
        self.assertEqual(set(expected), {"build/output/jniLibs/arm64-v8a/libsignal_jni.so"})


if __name__ == "__main__":
    unittest.main()