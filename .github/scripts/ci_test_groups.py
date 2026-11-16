"""한 배치표로 CI 실행 목록을 만들고 모든 그룹의 실제 보고서를 대조한다."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

# 별도 환경변수나 보존된 Archive 입력을 명시할 때만 실행하던 기존 검사다.
CONDITIONAL_CLASSES = {
    "com.exchange.core.api.trading.infrastructure.aeron.BoundedTradingClusterHaTest",
    "com.exchange.core.api.trading.infrastructure.aeron.SavedArchiveThreeCopyReplayTest",
    "com.exchange.core.api.trading.infrastructure.recovery.RemoteTradingRecoveryTest",
}


def test_xmls(root):
    return sorted(path for path in Path(root).rglob("TEST-*.xml") if path.parent.name == "test" and path.parent.parent.name == "test-results")


def xml_manifest(root):
    root = Path(root)
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in test_xmls(root)}


def record_reports(catalog_path, reports, group):
    """Gradle 성공 직후 목록을 고정해 업로드·다운로드 중의 누락도 검사한다."""
    if group not in ["general", *load_catalog(catalog_path)]:
        raise ValueError(f"알 수 없는 실행 그룹: {group}")
    manifest = xml_manifest(reports)
    if not manifest:
        raise ValueError(f"{group}: 실행 보고서가 없습니다")
    output = Path(reports) / "app-api/build/reports/ci-test-partition/execution.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"group": group, "catalogSha256": hashlib.sha256(Path(catalog_path).read_bytes()).hexdigest(), "xmlSha256": manifest}, indent=2), encoding="utf-8")


def read_json(path):
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"중복 JSON 키: {key}")
            result[key] = value
        return result

    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique_keys)


def load_catalog(path):
    catalog = read_json(path)
    if not isinstance(catalog, dict) or not catalog:
        raise ValueError("CI 그룹 배치표가 비어 있습니다")
    assigned = set()
    for group, classes in catalog.items():
        if group in {"general", "all"} or not re.fullmatch(r"[a-z][a-z0-9-]*", group):
            raise ValueError(f"잘못된 CI 그룹: {group}")
        if not isinstance(classes, list) or not classes:
            raise ValueError(f"빈 CI 그룹: {group}")
        for name in classes:
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+", name):
                raise ValueError(f"잘못된 최상위 클래스 이름: {name}")
            if name in assigned:
                raise ValueError(f"테스트 클래스 중복 배정: {name}")
            assigned.add(name)
    return catalog


def owner(name, catalog):
    for group, classes in catalog.items():
        if any(name == item or name.startswith(item + "$") for item in classes):
            return group
    return "general"


def candidate_set(value):
    if not isinstance(value, list) or not value or not all(isinstance(item, str) and item for item in value):
        raise ValueError("테스트 후보 목록이 비었거나 손상됐습니다")
    if len(value) != len(set(value)):
        raise ValueError("테스트 후보가 중복됐습니다")
    return set(value)


def verify_reports(catalog_path, reports):
    catalog = load_catalog(catalog_path)
    groups = ["general", *catalog]
    digest = hashlib.sha256(Path(catalog_path).read_bytes()).hexdigest()
    root = Path(reports)
    expected = {f"test-reports-{group}" for group in groups}
    actual = {path.name for path in root.glob("test-reports-*") if path.is_dir()}
    if actual != expected:
        raise ValueError(f"그룹 보고서 누락/추가: 기대={sorted(expected)}, 실제={sorted(actual)}")
    all_candidates = None
    selected_union = set()
    case_owners = {}
    counts = {}
    for group in groups:
        folder = root / f"test-reports-{group}"
        executions = [path for path in folder.rglob("execution.json") if "ci-test-partition" in path.parts]
        if len(executions) != 1:
            raise ValueError(f"{group}: 실행 완료 보고서가 정확히 하나여야 합니다")
        execution = read_json(executions[0])
        if execution.get("group") != group or execution.get("catalogSha256") != digest or not execution.get("xmlSha256") or execution["xmlSha256"] != xml_manifest(folder):
            raise ValueError(f"{group}: 실제 실행 XML 누락·추가·변조")
        inventories = [path for path in folder.rglob("inventory.json") if "ci-test-partition" in path.parts]
        if len(inventories) != 1:
            raise ValueError(f"{group}: 후보 배정 보고서가 정확히 하나여야 합니다")
        inventory = read_json(inventories[0])
        reported_groups = inventory.get("groups")
        if inventory.get("group") != group or inventory.get("catalogSha256") != digest or not isinstance(reported_groups, list) or len(reported_groups) != len(groups) or set(reported_groups) != set(groups):
            raise ValueError(f"{group}: 배치표·실행 그룹 불일치")
        candidates = candidate_set(inventory.get("allCandidates"))
        selected = candidate_set(inventory.get("selectedCandidates"))
        if all_candidates is not None and candidates != all_candidates:
            raise ValueError(f"{group}: 다른 컴파일 후보를 사용했습니다")
        all_candidates = candidates
        if selected != {name for name in candidates if owner(name, catalog) == group} or selected_union & selected:
            raise ValueError(f"{group}: 테스트 후보 누락·중복·잘못된 배정")
        selected_union |= selected
        cases = skipped = 0
        observed_classes = set()
        xmls = test_xmls(folder)
        for path in xmls:
            suite = ET.parse(path).getroot()
            if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
                raise ValueError(f"{group}: 실패한 검사 보고서 {path.name}")
            children = suite.findall("testcase")
            if int(suite.get("tests", "-1")) != len(children):
                raise ValueError(f"{group}: 불완전한 검사 보고서 {path.name}")
            for case in children:
                name = case.get("classname")
                if not name:
                    raise ValueError(f"{group}: 검사 클래스 식별 누락")
                expected_owner = owner(name, catalog) if name in candidates else "general"
                if expected_owner != group:
                    raise ValueError(f"{group}: 다른 그룹의 검사 실행 {name}")
                identity = (name, case.get("name"))
                if identity in case_owners:
                    raise ValueError(f"검사 결과 중복: {identity}")
                case_owners[identity] = group
                if case.find("failure") is not None or case.find("error") is not None:
                    raise ValueError(f"{group}: 실패한 검사 {identity}")
                cases += 1
                is_skipped = case.find("skipped") is not None
                if is_skipped and name not in CONDITIONAL_CLASSES:
                    raise ValueError(f"{group}: 기존 조건부 검사가 아닌 skip {identity}")
                skipped += is_skipped
                observed_classes.add(name)
        if cases <= skipped:
            raise ValueError(f"{group}: 실제로 끝낸 검사가 없습니다")
        for name in catalog.get(group, []):
            if not any(item == name or item.startswith(name + "$") for item in observed_classes):
                raise ValueError(f"{group}: 배정한 검사 보고서 누락 {name}")
        counts[group] = {"reported": cases, "skipped": skipped, "passed": cases - skipped}
    if selected_union != all_candidates:
        raise ValueError("전체 테스트 후보의 합집합이 다릅니다")
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["matrix", "record", "verify"])
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1] / "ci-test-groups.json")
    parser.add_argument("--reports", type=Path)
    parser.add_argument("--group")
    args = parser.parse_args()
    catalog = load_catalog(args.catalog)
    if args.command == "matrix":
        matrix = json.dumps({"group": ["general", *catalog]}, separators=(",", ":"))
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                output.write(f"matrix={matrix}\n")
        print(matrix)
    elif args.command == "record":
        if args.reports is None or args.group is None:
            parser.error("record는 --reports와 --group이 필요합니다")
        record_reports(args.catalog, args.reports, args.group)
    else:
        if args.reports is None:
            parser.error("verify는 --reports가 필요합니다")
        counts = verify_reports(args.catalog, args.reports)
        print(json.dumps(counts, ensure_ascii=False, indent=2))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
                summary.write("### 제품 검사 그룹 결과\n\n| 그룹 | 보고 | 통과 | skip |\n| --- | ---: | ---: | ---: |\n")
                for group, row in counts.items():
                    summary.write(f"| {group} | {row['reported']} | {row['passed']} | {row['skipped']} |\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as failure:
        print(f"CI 그룹 검증 실패: {failure}", file=sys.stderr)
        sys.exit(1)
