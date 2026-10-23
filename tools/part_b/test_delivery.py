import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("delivery", Path(__file__).with_name("build_delivery.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class DeliveryTests(unittest.TestCase):
    def test_allowlist(self):
        for name in ["workbench.cmd", "deploy/Workbench.ps1", "tools/performance/check_large_mixed.py", "backend/src/main/java/App.java", "frontend/src/App.vue",
                     "deploy/compose.yml", "docs/part-a/submission/PartA_Submission.docx"]:
            self.assertTrue(m.selected(name), name)
        for name in ["deploy/.env.local", "deploy/runtime/config.json", "backend/config/application-local.yml",
                     "frontend/node_modules/x.js", "frontend/dist/index.html", "frontend/test-results/a.png",
                     "backend/src/test/secret.p12", "docs/part-a/PartA需求规格说明书.docx",
                     "../backend/pom.xml", "backend/src/../../secret", "C:/secret", "deploy\\config.yml"]:
            self.assertFalse(m.selected(name), name)

    def test_output_selection_and_verify_are_mutually_exclusive(self):
        import subprocess, sys
        result = subprocess.run([sys.executable, str(Path(m.__file__)), "--verify", "fixture.zip",
                                 "--output-directory", "unused"], capture_output=True)
        self.assertEqual(2, result.returncode)

    def test_runtime_values_and_private_keys_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); (p/"deploy").mkdir()
            (p/"deploy/.env.local").write_text("S3_SECRET_KEY=unit-synthetic-secret-123456789\n", encoding="utf-8")
            secrets=m.local_secrets(p)
            self.assertEqual(len(secrets), 1)
            with self.assertRaises(ValueError):
                m.check_payload("asset", b"unit-synthetic-secret-123456789", secrets)
            with self.assertRaises(ValueError):
                m.check_payload("key", b"-----BEGIN PRIVATE KEY-----\nYWJjZGVm\n", [])
            m.check_payload("source", b"$" + b"{S3_SECRET_KEY}", secrets)

    def test_archive_tampering_rejected(self):
        import json, zipfile
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"bad.zip"
            manifest={"files":[{"path":"README.md","bytes":4,"sha256":m.sha(b"good")}]}
            with zipfile.ZipFile(p,"w") as z:
                z.writestr(m.PREFIX+"/README.md",b"evil")
                z.writestr(m.PREFIX+"/MANIFEST.json",json.dumps(manifest))
            with self.assertRaisesRegex(ValueError,"SHA-256"):
                m.verify(p)

    def test_forbidden_archive_entry_rejected(self):
        import zipfile
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"bad.zip"
            for name in [m.PREFIX+"/deploy/.env.local",m.PREFIX+"/../outside"]:
                with zipfile.ZipFile(p,"w") as z:
                    z.writestr(name,b"synthetic")
                with self.assertRaisesRegex(ValueError,"Unsafe"):
                    m.verify(p)

    def test_added_compiled_class_requires_repackaging(self):
        import zipfile
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            main = "cn/edu/pdfworkbench/PdfWorkbenchApplication.class"
            classes = root / "backend/target/classes"
            (classes / main).parent.mkdir(parents=True)
            (classes / main).write_bytes(b"fixture-class")
            jar = root / "backend/target/pdf-workbench-1.1.0.jar"
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("BOOT-INF/classes/" + main, b"fixture-class")
            self.assertEqual(1, m.application_jar(root, [])[1])
            (classes / "Added.class").write_bytes(b"new-class")
            with self.assertRaisesRegex(ValueError, "class/resource set differs"):
                m.application_jar(root, [])

    def test_safe_paths(self):
        self.assertTrue(m.safe_name("PDF_Extractor_PartB/frontend/dist/index.html"))
        for p in ["/root", "../root", "a/../b", "a//b", "a\\b", "C:/root", ""]:
            self.assertFalse(m.safe_name(p), p)

if __name__ == "__main__":
    unittest.main()
