"""정상 배정과 보고서 누락·중복·실패가 필수 검사를 바꾸는지 확인한다."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from ci_test_groups import CONDITIONAL_CLASSES, load_catalog, owner, record_reports, verify_reports


class CiTestGroupsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = self.root / "groups.json"
        self.catalog.write_text(json.dumps({"cluster": ["example.ClusterTest"]}))
        self.all = ["example.GeneralTest", "example.ClusterTest", "example.ClusterTest$Nested"]
        for group in ("general", "cluster"):
            folder = self.root / f"test-reports-{group}"
            inventory = folder / "build/reports/ci-test-partition/inventory.json"
            inventory.parent.mkdir(parents=True)
            inventory.write_text(json.dumps({"group": group, "catalogSha256": hashlib.sha256(self.catalog.read_bytes()).hexdigest(), "groups": ["general", "cluster"], "allCandidates": self.all, "selectedCandidates": [name for name in self.all if owner(name, load_catalog(self.catalog)) == group]}))
            self.xml(group, "example.GeneralTest" if group == "general" else "example.ClusterTest$Nested")
            record_reports(self.catalog, folder, group)

    def xml(self, group, name, skipped=False, failed=False):
        path = self.root / f"test-reports-{group}/build/test-results/test/TEST-sample.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", tests="1", failures="1" if failed else "0", errors="0")
        case = ET.SubElement(suite, "testcase", classname=name, name="case")
        if skipped:
            ET.SubElement(case, "skipped")
        if failed:
            ET.SubElement(case, "failure")
        ET.ElementTree(suite).write(path)
        return path

    def mutate_inventory(self, group, field, value):
        path = self.root / f"test-reports-{group}/build/reports/ci-test-partition/inventory.json"
        data = json.loads(path.read_text())
        data[field] = value
        path.write_text(json.dumps(data))

    def test_normal_partition_keeps_nested_classes_and_new_tests_general(self):
        self.assertEqual("general", owner("example.NewTest", load_catalog(self.catalog)))
        self.assertEqual("general", owner("example.ClusterTestExtra", load_catalog(self.catalog)))
        self.assertEqual({"general", "cluster"}, set(verify_reports(self.catalog, self.root)))

    def test_duplicate_json_key_and_class_assignment_are_rejected(self):
        for text in ('{"cluster":[],"cluster":[]}', '{"a":["example.Test"],"b":["example.Test"]}'):
            with self.subTest(text=text):
                self.catalog.write_text(text)
                with self.assertRaises(ValueError):
                    load_catalog(self.catalog)

    def test_reserved_or_empty_groups_are_rejected(self):
        for catalog in ({"general": ["example.Test"]}, {"all": ["example.Test"]}, {"cluster": []}, {}):
            with self.subTest(catalog=catalog):
                self.catalog.write_text(json.dumps(catalog))
                with self.assertRaises(ValueError):
                    load_catalog(self.catalog)

    def test_missing_selected_candidate_fails(self):
        self.mutate_inventory("cluster", "selectedCandidates", ["example.ClusterTest"])
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_missing_group_artifact_fails(self):
        (self.root / "test-reports-cluster").rename(self.root / "missing")
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_changed_catalog_fails(self):
        self.mutate_inventory("cluster", "catalogSha256", "old")
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_different_candidate_universe_fails(self):
        self.mutate_inventory("cluster", "allCandidates", self.all + ["example.UnsharedTest"])
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_unknown_group_artifact_fails(self):
        (self.root / "test-reports-unknown").mkdir()
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_existing_conditional_skip_is_counted_separately_from_pass(self):
        path = self.xml("general", "example.GeneralTest")
        suite = ET.parse(path).getroot()
        suite.set("tests", "2")
        skipped = ET.SubElement(suite, "testcase", classname=sorted(CONDITIONAL_CLASSES)[0], name="conditional")
        ET.SubElement(skipped, "skipped")
        ET.ElementTree(suite).write(path)
        record_reports(self.catalog, self.root / "test-reports-general", "general")
        self.assertEqual({"reported": 2, "skipped": 1, "passed": 1}, verify_reports(self.catalog, self.root)["general"])

    def test_completed_xml_missing_or_copied_fails(self):
        folder = self.root / "test-reports-general"
        path = folder / "build/test-results/test/TEST-sample.xml"
        content = path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)
        path.write_bytes(content)
        (path.parent / "TEST-duplicate.xml").write_bytes(content)
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)
        record_reports(self.catalog, folder, "general")
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_new_skip_in_partly_successful_group_fails(self):
        path = self.xml("cluster", "example.ClusterTest", skipped=True)
        suite = ET.parse(path).getroot()
        suite.set("tests", "2")
        ET.SubElement(suite, "testcase", classname="example.ClusterTest$Nested", name="passing")
        ET.ElementTree(suite).write(path)
        record_reports(self.catalog, self.root / "test-reports-cluster", "cluster")
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_assigned_class_report_missing_fails(self):
        self.catalog.write_text(json.dumps({"cluster": ["example.ClusterTest", "example.OtherTest"]}))
        self.all.append("example.OtherTest")
        for group in ("general", "cluster"):
            self.mutate_inventory(group, "catalogSha256", hashlib.sha256(self.catalog.read_bytes()).hexdigest())
            self.mutate_inventory(group, "allCandidates", self.all)
            self.mutate_inventory(group, "selectedCandidates", [name for name in self.all if owner(name, load_catalog(self.catalog)) == group])
            record_reports(self.catalog, self.root / f"test-reports-{group}", group)
        with self.assertRaises(ValueError):
            verify_reports(self.catalog, self.root)

    def test_wrong_group_failed_or_all_skipped_results_fail(self):
        for name, skipped, failed in (("example.GeneralTest", False, False), ("example.ClusterTest", False, True), ("example.ClusterTest", True, False)):
            with self.subTest(name=name, skipped=skipped, failed=failed):
                self.xml("cluster", name, skipped=skipped, failed=failed)
                record_reports(self.catalog, self.root / "test-reports-cluster", "cluster")
                with self.assertRaises(ValueError):
                    verify_reports(self.catalog, self.root)


if __name__ == "__main__":
    unittest.main()
