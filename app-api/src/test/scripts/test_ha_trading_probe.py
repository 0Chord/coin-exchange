"""제어기의 기준값만 검사하며 Docker, JVM, 빌드나 성능 측정을 실행하지 않는다."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location("ha_trading_probe", Path(__file__).with_name("ha-trading-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

project_root = Path(__file__).resolve().parents[4]
sustained_spec = importlib.util.spec_from_file_location(
    "ha_trading_probe_600", project_root / "engineering/results/sustained-final-a-20261031/ha-trading-probe-600.py")
sustained_probe = importlib.util.module_from_spec(sustained_spec)
sustained_spec.loader.exec_module(sustained_probe)


class MarketBalancesTest(unittest.TestCase):
    controllers = (("short", probe), ("sustained-600", sustained_probe))
    assets = ("BTC", "SOL", "SUI")
    # TradingEngineProbeWorkload의 순서·수량과 HaProbeRunPlan의 원금·잔량 계약을 손계산했다.
    # None은 실제 체결이나 자금 준비가 없어 만들어지지 않은 잔고 행이다.
    cases = (
        (1, 1, (100_000_000, None, None), (0, None, None),
         ((100_000_000, 0), (0, 0), (0, 0))),
        (2, 2, (100_000_000, 1_000_000_000, None), (0, 0, None),
         ((100_000_000, 0), (1_000_000_000, 0), (0, 0))),
        (3, 3, (100_000_000, 1_000_000_000, 1_000_000_000), (0, 0, 0),
         ((100_000_000, 0), (1_000_000_000, 0), (1_000_000_000, 0))),
        (6000, 6000, (200_000_000_000, 2_000_000_000_000, 2_000_000_000_000), (0, 0, 0),
         ((200_000_000_000, 0), (2_000_000_000_000, 0), (2_000_000_000_000, 0))),
        (10, 1, (100_000_000, None, None), (300_000_000, 3_000_000_000, 3_000_000_000),
         ((400_000_000, 300_000_000), (3_000_000_000, 3_000_000_000), (3_000_000_000, 3_000_000_000))),
        (10, 2, (100_000_000, 1_000_000_000, None), (300_000_000, 2_000_000_000, 3_000_000_000),
         ((400_000_000, 300_000_000), (3_000_000_000, 2_000_000_000), (3_000_000_000, 3_000_000_000))),
        (10, 4, (200_000_000, 1_000_000_000, 1_000_000_000), (200_000_000, 2_000_000_000, 2_000_000_000),
         ((400_000_000, 200_000_000), (3_000_000_000, 2_000_000_000), (3_000_000_000, 2_000_000_000))),
    )

    @classmethod
    def fixture(cls, case):
        capacity, total, buyer_available, seller_hold, market_amounts = case
        balances = {("BUYER", asset): (available, 0)
                    for asset, available in zip(cls.assets, buyer_available) if available is not None}
        balances.update({("SELLER", asset): (0, hold)
                         for asset, hold in zip(cls.assets, seller_hold) if hold is not None})
        expected = {asset + "-USDT": amounts for asset, amounts in zip(cls.assets, market_amounts)}
        return capacity, total, balances, expected

    @classmethod
    def with_unused_zeros(cls, balances):
        return {(user, asset): balances.get((user, asset), (0, 0))
                for user in ("BUYER", "SELLER") for asset in cls.assets}

    def test_unused_rows_may_be_absent_or_present_zero_and_market_amounts_are_exact(self):
        for name, controller in self.controllers:
            for case in self.cases:
                capacity, total, balances, expected = self.fixture(case)
                with self.subTest(controller=name, capacity=capacity, total=total, unused="absent"):
                    self.assertEqual(expected, controller.verify_market_balances(balances, capacity, total))
                with self.subTest(controller=name, capacity=capacity, total=total, unused="present-zero"):
                    self.assertEqual(expected, controller.verify_market_balances(
                        self.with_unused_zeros(balances), capacity, total))

    def test_missing_traded_buyer_or_funded_seller_rows_are_rejected(self):
        for name, controller in self.controllers:
            # 완전 체결 판매자의 (0, 0) 행과 미체결 시장의 판매자 예약 행도 필수다.
            for case in (self.cases[2], self.cases[4]):
                capacity, total, balances, _ = self.fixture(case)
                for key in balances:
                    missing = self.with_unused_zeros(balances)
                    del missing[key]
                    with self.subTest(controller=name, capacity=capacity, total=total, missing=key):
                        with self.assertRaises(KeyError):
                            controller.verify_market_balances(missing, capacity, total)

    def test_present_unused_rows_must_have_zero_available_and_hold(self):
        capacity, total, balances, _ = self.fixture(self.cases[0])
        for name, controller in self.controllers:
            for user in ("BUYER", "SELLER"):
                for asset in ("SOL", "SUI"):
                    for wrong in ((1, 0), (0, 1), (-1, 0), (0, -1)):
                        invalid = self.with_unused_zeros(balances)
                        invalid[user, asset] = wrong
                        with self.subTest(controller=name, user=user, asset=asset, wrong=wrong):
                            with self.assertRaises(AssertionError):
                                controller.verify_market_balances(invalid, capacity, total)

    def test_present_used_rows_must_match_available_and_hold(self):
        for name, controller in self.controllers:
            for case in (self.cases[2], self.cases[4]):
                capacity, total, balances, _ = self.fixture(case)
                for key, expected in balances.items():
                    for field in (0, 1):
                        wrong = list(expected)
                        wrong[field] = 0 if expected[field] else 1
                        invalid = self.with_unused_zeros(balances)
                        invalid[key] = tuple(wrong)
                        with self.subTest(controller=name, capacity=capacity, total=total, key=key, field=field):
                            with self.assertRaises(AssertionError):
                                controller.verify_market_balances(invalid, capacity, total)


class MemoryBudgetTest(unittest.TestCase):
    def test_flags_must_identify_exactly_one_actual_maximum_heap(self):
        for mib in (512, 1024):
            maximum = mib * 1024 ** 2
            flags = f"1:\n -XX:InitialHeapSize=268435456 -XX:MaxHeapSize={maximum} -XX:+UseG1GC\n"
            self.assertEqual(maximum, probe.verify_heap_flags(flags, mib))
        for flags in ("", "-XX:SoftMaxHeapSize=536870912", "-XX:MaxHeapSize=1073741824",
                      "-XX:MaxHeapSize=536870912 -XX:MaxHeapSize=536870912"):
            with self.assertRaises(RuntimeError):
                probe.verify_heap_flags(flags, 512)

    def test_explicit_heap_and_original_default_apply_to_three_nodes_and_client(self):
        for explicit in (None, 512):
            with self.subTest(heap=explicit), tempfile.TemporaryDirectory() as directory:
                project = Path(directory) / "app-api"
                project.mkdir()
                args = ["probe", "--project", str(project), "--distribution", directory,
                        "--mode", "bounded", "--image", "prepared-image"]
                if explicit is not None:
                    args += ["--heap-mb", str(explicit)]

                def stop_before_start(arguments, **options):
                    if "up" in arguments:
                        raise RuntimeError("render-only stop")
                    return subprocess.CompletedProcess(arguments, 0, stdout="", stderr="")

                with patch.object(sys, "argv", args), patch.object(probe.subprocess, "run", side_effect=stop_before_start), \
                        patch.object(probe.shutil, "disk_usage", return_value=Mock(free=50 * probe.GIB)), patch("builtins.print"):
                    with self.assertRaisesRegex(RuntimeError, "render-only stop"):
                        probe.main()
                reports = list(project.glob("build/reports/ha-trading/*"))
                self.assertEqual(1, len(reports))
                heap = explicit or 1024
                compose = (reports[0] / "probe.compose.yml").read_text()
                self.assertEqual(4, compose.count(f"-Xms256m -Xmx{heap}m"))
                summary = json.loads((reports[0] / "summary.json").read_text())
                self.assertEqual(heap, summary["requestedJvmHeapMiB"])
                self.assertFalse(summary["success"])


class DiskGuardTest(unittest.TestCase):
    def test_allocated_kib_must_identify_exactly_the_owned_recording_root(self):
        self.assertEqual(4096, probe.allocated_bytes("4\t/records/owned\n", "/records/owned"))
        for invalid in ("4 /records/other", "-1 /records/owned", "4 /records/owned\n8 /records/other", "bad"):
            with self.assertRaises(probe.DiskBudgetExceeded):
                probe.allocated_bytes(invalid, "/records/owned")

    def test_three_actual_allocations_sum_and_stop_with_256_mib_headroom(self):
        values = [512 * 1024 ** 2] * 3
        read = Mock(side_effect=lambda member: values[member])
        guard = probe.RunDiskGuard(Path("."), read, host_free=lambda: 12 * probe.GIB)
        guard.check("before-client")
        self.assertEqual([0, 1, 2], [call.args[0] for call in read.call_args_list])
        self.assertEqual(1536 * 1024 ** 2, guard.stats["peakAllocatedBytes"])
        values[2] += 256 * 1024 ** 2
        with self.assertRaises(probe.DiskBudgetExceeded):
            guard.check("client-running")
        self.assertEqual(2 * probe.GIB, guard.stats["allocatedCapBytes"])
        self.assertEqual(1792 * 1024 ** 2, guard.stats["peakAllocatedBytes"])
        self.assertEqual(2, guard.stats["checks"])
        self.assertFalse(guard.stats["samples"][-1]["passed"])
        self.assertGreaterEqual(guard.stats["guardElapsedSeconds"], guard.stats["clientRunningGuardElapsedSeconds"])

    def test_host_reserve_and_unreadable_recordings_fail_closed(self):
        read = Mock(return_value=0)
        guard = probe.RunDiskGuard(Path("."), read, host_free=lambda: 8 * probe.GIB - 1)
        with self.assertRaises(probe.DiskBudgetExceeded):
            guard.check("before-client")
        read.assert_not_called()
        failed = probe.RunDiskGuard(Path("."), Mock(side_effect=RuntimeError("exec unavailable")), host_free=lambda: 12 * probe.GIB)
        with self.assertRaisesRegex(RuntimeError, "exec unavailable"):
            failed.check("client-running")
        self.assertEqual(1, failed.stats["checks"])
        self.assertIn("exec unavailable", failed.stats["samples"][0]["failure"])

    def test_guard_phase_costs_and_sample_budget_are_bounded_without_disabling_checks(self):
        guard = probe.RunDiskGuard(Path("."), lambda member: 0, host_free=lambda: 12 * probe.GIB)
        guard.stats["sampleCapacity"] = 1
        guard.stats["clientPhase"] = "measure-window-and-drain"
        guard.check("client-running")
        sample = guard.stats["samples"][0]
        self.assertEqual("measure-window-and-drain", sample["clientPhase"])
        self.assertGreaterEqual(sample["finishedMonotonicNs"], sample["startedMonotonicNs"])
        self.assertEqual(guard.stats["clientRunningGuardElapsedSeconds"],
                         guard.stats["clientPhaseGuardElapsedSeconds"]["measure-window-and-drain"])
        with self.assertRaisesRegex(probe.DiskBudgetExceeded, "sample capacity"):
            guard.check("client-running")

    def monitored_failure(self, timeout, guard_failure=None, abort_failure=None):
        process = Mock(pid=24680)
        process.returncode = None
        process.poll.side_effect = lambda: process.returncode
        process.wait.side_effect = lambda timeout=None: process.returncode
        guard = Mock(interval_seconds=0.0, stats={})
        guard.check.side_effect = guard_failure
        abort = Mock(side_effect=abort_failure)
        with tempfile.TemporaryDirectory() as root:
            with patch.object(probe.subprocess, "Popen", return_value=process), patch.object(probe.os, "killpg") as kill:
                kill.side_effect = lambda pid, signum: setattr(process, "returncode", -signum)
                with self.assertRaises(BaseException) as failure:
                    probe.monitored_client(["owned-client"], Path(root), {}, timeout,
                                           Path(root) / "out", Path(root) / "err", guard, abort)
                abort.assert_called_once()
                process.wait.assert_called()
                kill.assert_called_once_with(process.pid, probe.signal.SIGTERM)
                self.assertTrue(guard.stats["clientReaped"])
                return failure.exception

    def test_live_guard_failure_stops_and_reaps_client_preserving_original_cause(self):
        failure = self.monitored_failure(10, probe.DiskBudgetExceeded("allocated cap"), RuntimeError("stop failed"))
        self.assertIsInstance(failure, probe.DiskBudgetExceeded)
        self.assertIn("allocated cap", str(failure))
        self.assertTrue(any("stop failed" in note for note in failure.__notes__))

    def test_timeout_stops_and_reaps_client(self):
        self.assertIsInstance(self.monitored_failure(0), subprocess.TimeoutExpired)

    def test_log_timeout_cannot_skip_owned_volume_cleanup(self):
        calls = []
        compose = ["docker", "compose", "-p", "owned-run"]

        def command(arguments, **options):
            calls.append(arguments)
            if "logs" in arguments:
                raise subprocess.TimeoutExpired(arguments, options["timeout"])
            return subprocess.CompletedProcess(arguments, 0)

        failures = probe.cleanup_owned_run(command, compose, "owned-run-client")
        self.assertEqual(1, len(failures))
        self.assertIn(compose + ["down", "--volumes", "--remove-orphans"], calls)
        self.assertEqual(["docker", "rm", "--force", "owned-run-client"], calls[0])
        self.assertFalse(any("prune" in arguments for arguments in calls))


class CheckpointTest(unittest.TestCase):
    @staticmethod
    def states(count):
        return [dict(member=member, role="LEADER" if member == 0 else "FOLLOWER",
                     receiptFloors=[["BUYER", 0, 1, count, count], ["SELLER", 0, 1, 3, 3]],
                     serviceBoundary=dict(threadId=7, sampledNs=1_000 + count,
                                          cpuNs=dict(status="available", value=100 + count),
                                          allocatedBytes=dict(status="unsupported", value=None)),
                     metrics=dict(valid=True,
                                  phases=[dict(phase="execution_total", count=count, totalNs=count * 10, maxNs=1_000 - count)],
                                  offerAttempts=dict(execution=count, reply=count),
                                  callbacks=dict(admission=count, execution=count)),
                     markerSender=dict(offered=count, sent=count, enqueueToWriteSamples=count,
                                       enqueueToWriteTotalNs=count * 5, enqueueToWriteMaxNs=500 - count,
                                       queuePeak=2, failure=None)) for member in range(3)]

    def test_same_node_differences_exclude_warmup_and_never_subtract_cumulative_maxima(self):
        result = probe.checkpoint_deltas(self.states(100), self.states(125))
        self.assertEqual(3, len(result["nodes"]))
        for node in result["nodes"]:
            self.assertEqual([dict(phase="execution_total", count=25, totalNs=250)], node["phases"])
            self.assertEqual(dict(status="available", value=25), node["serviceThreadCpuNs"])
            self.assertEqual("unavailable", node["serviceThreadAllocatedBytes"]["status"])
            self.assertIsNone(node["serviceThreadAllocatedBytes"]["value"])
            self.assertNotIn("maxNs", node["phases"][0])
            self.assertEqual(25, node["markerSender"]["offered"])

    def test_missing_members_thread_change_and_decreasing_counts_are_invalid(self):
        for mutate in (lambda states: states.pop(),
                       lambda states: states[0]["serviceBoundary"].update(threadId=8),
                       lambda states: states[0]["metrics"]["callbacks"].update(execution=0),
                       lambda states: states[0]["metrics"].update(valid=False)):
            after = self.states(125)
            mutate(after)
            with self.assertRaises(RuntimeError):
                probe.checkpoint_deltas(self.states(100), after)

    def test_real_zero_counter_is_available_and_unknown_counter_stays_unknown(self):
        self.assertEqual(dict(status="available", value=0),
                         probe.counter_delta(dict(status="available", value=0), dict(status="available", value=0), "CPU"))
        self.assertIsNone(probe.counter_delta(dict(status="disabled", value=None), dict(status="disabled", value=None), "CPU")["value"])
        with self.assertRaises(RuntimeError):
            probe.counter_delta(dict(status="available", value=5), dict(status="available", value=-1), "CPU")

    def test_capture_reuses_existing_command_and_waits_for_all_three_exact_ack_floors(self):
        request = dict(label="measure-start", buyerAckFloor=100, sellerAckFloor=3)
        with tempfile.TemporaryDirectory() as root:
            control = Path(root)
            for attempt in range(2):
                states = self.states(100)
                if attempt == 0:
                    states[2]["receiptFloors"][0][-1] = 99
                for state in states:
                    (control / f"state-{state['member']}-measure-start-{attempt}.json").write_text(json.dumps(state))
            tick = Mock()
            states = probe.capture_phase(control, request, True, tick)
            self.assertEqual(100, states[2]["receiptFloors"][0][-1])
            self.assertTrue(tick.called)
            for member in range(3):
                self.assertEqual("CAPTURE:measure-start-1", (control / f"node-{member}.command").read_text())


if __name__ == "__main__":
    unittest.main()
