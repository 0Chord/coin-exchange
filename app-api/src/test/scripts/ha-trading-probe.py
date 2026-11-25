#!/usr/bin/env python3
"""컨테이너 세 개로 짧게 실행하는 일회성 비교 측정이다. 기존에 준비한 이미지와 Dockerfile을 사용한다.

클래스 준비를 마친 뒤에만 실행한다. 이 제어기는 Gradle이나 빌드 도구를 호출하지 않는다.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import uuid


GIB = 1024 ** 3


def verify_heap_flags(flags, expected_mib):
    maxima = re.findall(r"-XX:MaxHeapSize=(\d+)\b", flags)
    if len(maxima) != 1 or int(maxima[0]) != expected_mib * 1024 ** 2:
        raise RuntimeError("node heap does not match the explicit comparison budget")
    return int(maxima[0])


class DiskBudgetExceeded(RuntimeError):
    pass


def allocated_bytes(text, expected_path):
    """du -sk는 희소 파일의 겉보기 길이가 아닌 실제 할당량을 1 KiB 블록 단위로 보고한다."""
    rows = text.strip().splitlines()
    if len(rows) != 1:
        raise DiskBudgetExceeded("one allocated-size row per owned recording root required")
    fields = rows[0].split(maxsplit=1)
    if len(fields) != 2 or not fields[0].isdigit() or fields[1] != expected_path:
        raise DiskBudgetExceeded("allocated-size response does not identify the owned recording root")
    return int(fields[0]) * 1024


class RunDiskGuard:
    def __init__(self, host_path, read_allocated, host_free=None):
        self.host_path = host_path
        self.read_allocated = read_allocated
        self.host_free = host_free or (lambda: shutil.disk_usage(host_path).free)
        self.interval_seconds = 2.0
        self.stats = dict(allocatedCapBytes=2 * GIB, minimumHostFreeBytes=8 * GIB,
                          earlyStopAllocatedBytes=2 * GIB - 256 * 1024 ** 2,
                          intervalSeconds=self.interval_seconds, checks=0, guardElapsedSeconds=0.0,
                          clientRunningGuardElapsedSeconds=0.0, peakAllocatedBytes=0,
                          clientPhase="startup-and-warmup", clientPhaseGuardElapsedSeconds={}, sampleCapacity=256,
                          minimumObservedHostFreeBytes=None, samples=[],
                          limitation="polling guard with 256 MiB headroom, not a filesystem hard quota; du/exec can perturb throughput",
                          phaseCostScope="host-observed client checkpoint phases; wall costs are not CPU time and are never subtracted from TPS")

    def check(self, phase):
        began = time.monotonic()
        if len(self.stats["samples"]) >= self.stats["sampleCapacity"]:
            raise DiskBudgetExceeded("bounded guard sample capacity exhausted")
        sample = dict(phase=phase, hostFreeBytes=None, allocatedBytesByNode={}, allocatedBytes=None, passed=False,
                      startedMonotonicNs=time.monotonic_ns(),
                      clientPhase=self.stats["clientPhase"] if phase == "client-running" else None)
        try:
            free = self.host_free()
            sample["hostFreeBytes"] = free
            previous = self.stats["minimumObservedHostFreeBytes"]
            self.stats["minimumObservedHostFreeBytes"] = free if previous is None else min(free, previous)
            if free < self.stats["minimumHostFreeBytes"]:
                raise DiskBudgetExceeded("host free space is below the unchanged 8 GiB reserve")
            for member in range(3):
                value = self.read_allocated(member)
                if not isinstance(value, int) or value < 0:
                    raise DiskBudgetExceeded("invalid owned-volume allocated size")
                sample["allocatedBytesByNode"][str(member)] = value
            total = sum(sample["allocatedBytesByNode"].values())
            sample["allocatedBytes"] = total
            self.stats["peakAllocatedBytes"] = max(self.stats["peakAllocatedBytes"], total)
            if total > self.stats["allocatedCapBytes"]:
                raise DiskBudgetExceeded("three owned /records roots exceed the unchanged 2 GiB allocated budget")
            if total >= self.stats["earlyStopAllocatedBytes"]:
                raise DiskBudgetExceeded("owned recording roots reached the 1.75 GiB early-stop threshold (256 MiB headroom)")
            sample["passed"] = True
        except BaseException as failure:
            sample["failure"] = repr(failure)
            raise
        finally:
            sample["elapsedSeconds"] = time.monotonic() - began
            sample["finishedMonotonicNs"] = time.monotonic_ns()
            self.stats["checks"] += 1
            self.stats["guardElapsedSeconds"] += sample["elapsedSeconds"]
            if phase == "client-running":
                self.stats["clientRunningGuardElapsedSeconds"] += sample["elapsedSeconds"]
                phase_totals = self.stats["clientPhaseGuardElapsedSeconds"]
                phase_totals[sample["clientPhase"]] = phase_totals.get(sample["clientPhase"], 0.0) + sample["elapsedSeconds"]
            self.stats["samples"].append(sample)


def reap_client(process):
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
    else:
        process.wait()


def monitored_client(arguments, cwd, environment, timeout, stdout_file, stderr_file, guard, abort, on_poll=None):
    """클라이언트 출력을 디스크에 보관하고 프로세스 실행 중에 해당 실행이 소유한 저장소를 점검한다."""
    with stdout_file.open("w") as stdout, stderr_file.open("w") as stderr:
        process = subprocess.Popen(arguments, cwd=cwd, env=environment, text=True,
                                   stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            began = time.monotonic()
            deadline = began + timeout
            next_check = began + guard.interval_seconds

            def tick():
                nonlocal next_check
                now = time.monotonic()
                if now >= deadline:
                    raise subprocess.TimeoutExpired(arguments, timeout)
                if now >= next_check:
                    guard.check("client-running")
                    next_check = time.monotonic() + guard.interval_seconds

            while process.poll() is None:
                tick()
                if on_poll is not None:
                    on_poll(tick)
                time.sleep(min(.1, max(0, next_check - time.monotonic())))
            process.wait()
            guard.check("client-finished")
        except BaseException as failure:
            try:
                abort()
            except BaseException as stopping:
                failure.add_note("owned client stop failed: " + repr(stopping))
            raise
        finally:
            reap_client(process)
            guard.stats["clientReaped"] = process.poll() is not None
            guard.stats["clientExitCode"] = process.returncode
    return subprocess.CompletedProcess(arguments, process.returncode, stdout_file.read_text(), stderr_file.read_text())


def capture_phase(control, request, bounded, tick):
    """요청 묶음이 모두 처리된 뒤 CAPTURE를 재사용한다. 모든 bounded 노드의 ACK 하한이 정확히 일치해야 한다."""
    label = request["label"]
    if label not in ("measure-start", "measure-end"):
        raise RuntimeError("unknown client checkpoint label")
    expected = [["BUYER", 0, 1, request["buyerAckFloor"], request["buyerAckFloor"]],
                ["SELLER", 0, 1, request["sellerAckFloor"], request["sellerAckFloor"]]]
    deadline = time.monotonic() + 30
    for attempt in range(50):
        current = f"{label}-{attempt}"
        for member in range(3):
            temporary = control / f"node-{member}.command.tmp"
            temporary.write_text("CAPTURE:" + current)
            temporary.replace(control / f"node-{member}.command")
        paths = [control / f"state-{member}-{current}.json" for member in range(3)]
        while not all(path.exists() for path in paths):
            tick()
            if time.monotonic() >= deadline:
                raise RuntimeError("three service checkpoint CAPTUREs were not produced")
            time.sleep(.05)
        states = [json.loads(path.read_text()) for path in paths]
        if not bounded or all(sorted(state["receiptFloors"]) == expected for state in states):
            if any(not state.get("metrics", {}).get("valid") or "serviceBoundary" not in state for state in states):
                raise RuntimeError("service checkpoint instrumentation invalid or unavailable")
            return states
        tick()
        if time.monotonic() >= deadline:
            raise RuntimeError("all-node checkpoint ACK floors did not converge")
        time.sleep(.05)
    raise RuntimeError("service checkpoint CAPTURE attempt budget exhausted")


def nonnegative_delta(before, after, name):
    if any(not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value < 0
           for value in (before, after)) or after < before:
        raise RuntimeError("invalid or decreasing checkpoint " + name)
    return after - before


def counter_delta(before, after, name):
    if before.get("status") != "available" or after.get("status") != "available":
        return dict(status="unavailable", value=None, beforeStatus=before.get("status"), afterStatus=after.get("status"))
    return dict(status="available", value=nonnegative_delta(before.get("value"), after.get("value"), name))


def checkpoint_deltas(before, after):
    """같은 노드의 건수·합계·CPU·할당량 차이만 계산한다. 누적 최댓값끼리는 빼지 않는다."""
    indexed = [{state["member"]: state for state in states} for states in (before, after)]
    if any(len(states) != 3 or set(states) != {0, 1, 2} for states in indexed) or any(len(states) != 3 for states in (before, after)):
        raise RuntimeError("exactly three unique checkpoint members required")
    result = []
    for member in range(3):
        start, end = (states[member] for states in indexed)
        first, last = start["metrics"], end["metrics"]
        if not first.get("valid") or not last.get("valid"):
            raise RuntimeError("operational metrics invalid at checkpoint")
        boundaries = [state["serviceBoundary"] for state in (start, end)]
        if boundaries[0]["threadId"] != boundaries[1]["threadId"]:
            raise RuntimeError("service thread changed between checkpoints")
        first_phases, last_phases = ({phase["phase"]: phase for phase in metrics["phases"]} for metrics in (first, last))
        if set(first_phases) != set(last_phases):
            raise RuntimeError("checkpoint phase sets differ")
        phases = [dict(phase=phase,
                       count=nonnegative_delta(first_phases[phase]["count"], last_phases[phase]["count"], phase + ":count"),
                       totalNs=nonnegative_delta(first_phases[phase]["totalNs"], last_phases[phase]["totalNs"], phase + ":totalNs"))
                  for phase in first_phases]
        counters = {}
        for group in ("offerAttempts", "callbacks"):
            if set(first[group]) != set(last[group]):
                raise RuntimeError("checkpoint counter sets differ")
            counters[group] = {name: nonnegative_delta(first[group][name], last[group][name], group + ":" + name)
                               for name in first[group]}
        marker_start, marker_end = start["markerSender"], end["markerSender"]
        if marker_start.get("failure") or marker_end.get("failure"):
            raise RuntimeError("marker sender failed at checkpoint")
        marker = {name: nonnegative_delta(marker_start[name], marker_end[name], "marker:" + name)
                  for name in ("offered", "sent", "enqueueToWriteSamples", "enqueueToWriteTotalNs")}
        marker.update(timingSampleEvery=256, queuePeakCumulativeAtEnd=marker_end["queuePeak"],
                      maxSemantics="cumulative maxima are not subtracted")
        result.append(dict(member=member, roleAtStart=start["role"], roleAtEnd=end["role"], phases=phases, **counters,
                           serviceThreadCpuNs=counter_delta(boundaries[0]["cpuNs"], boundaries[1]["cpuNs"], "cpuNs"),
                           serviceThreadAllocatedBytes=counter_delta(boundaries[0]["allocatedBytes"], boundaries[1]["allocatedBytes"], "allocatedBytes"),
                           checkpointWallNs=nonnegative_delta(0, boundaries[1]["sampledNs"] - boundaries[0]["sampledNs"], "node-local elapsed clock"),
                           markerSender=marker))
    return dict(scope="same-node checkpoint deltas after all-node ACK-floor verification; service CPU/allocation include final CAPTURE and boundary-control costs, not pure domain CPU",
                maxSemantics="no interval maximum derived from cumulative checkpoints", nodes=result)


def verify_market_balances(balances, capacity, total):
    """자금 준비·체결 수에서 독립 기대 잔액과 미체결 수량을 계산해 대조한다."""
    remaining_by_market = {}
    for ordinal, (asset, quantity) in enumerate((("BTC", 100000000), ("SOL", 1000000000), ("SUI", 1000000000))):
        count = total // 3 + (1 if ordinal < total % 3 else 0)
        funded_count = capacity // 3 + (1 if ordinal < capacity % 3 else 0)
        remaining = (funded_count - count) * quantity
        remaining_by_market[asset + "-USDT"] = (funded_count * quantity, remaining)
        # 사용하지 않은 자산만 행 누락을 허용한다. 사용한 행은 최종 잔액이 0이어도 필수다.
        buyer = balances["BUYER", asset] if count > 0 else balances.get(("BUYER", asset), (0, 0))
        seller = balances["SELLER", asset] if funded_count > 0 else balances.get(("SELLER", asset), (0, 0))
        assert buyer == (count * quantity, 0)
        assert seller == (0, remaining)
    return remaining_by_market


def cleanup_owned_run(command, compose, client_name, image=None):
    cleanup = [(["docker", "rm", "--force", client_name], 15, False),
               (compose + ["logs", "--no-color", "--no-log-prefix"], 15, False),
               (compose + ["down", "--volumes", "--remove-orphans"], 60, True)]
    if image is not None:
        cleanup.append((["docker", "image", "rm", image], 30, True))
    failures = []
    for arguments, timeout, require_success in cleanup:
        try:
            result = command(arguments, timeout=timeout, check=False)
            if require_success and result.returncode:
                failures.append(dict(arguments=arguments, exitCode=result.returncode))
        except BaseException as failure:
            failures.append(dict(arguments=arguments, failure=repr(failure)))
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True, type=Path, help="absolute app-api directory")
    parser.add_argument("--distribution", required=True, type=Path)
    parser.add_argument("--mode", choices=("legacy", "bounded"), required=True)
    parser.add_argument("--image", help="reuse a prepared image; otherwise build the existing Dockerfile once")
    parser.add_argument("--warmup", type=int, default=1000)
    parser.add_argument("--orders", type=int, default=5000)
    parser.add_argument("--in-flight", type=int, default=256)
    parser.add_argument("--execution-in-flight", type=int, default=1024)
    parser.add_argument("--ack-mode", choices=("sync", "async"), default="async")
    parser.add_argument("--driver-root", help="opt-in volatile MediaDriver root; Archive and cluster volumes stay unchanged")
    parser.add_argument("--workload", choices=("full-maker", "partial-maker"), default="full-maker")
    parser.add_argument("--max-samples", type=int, help="explicit total BUY sample/funding cap for partial-maker")
    parser.add_argument("--warmup-seconds", type=int)
    parser.add_argument("--measure-seconds", type=int)
    parser.add_argument("--heap-mb", type=int, choices=(512, 1024), default=1024,
                        help="maximum Java heap per node and client; keep 1024 for the original baseline")
    args = parser.parse_args()
    assert args.project.is_absolute() and args.distribution.is_absolute()
    assert 0 <= args.warmup and 0 < args.orders and args.warmup + args.orders <= 6000
    assert 256 <= args.in_flight <= 1024
    assert args.in_flight <= args.execution_in_flight <= 4096
    if args.driver_root:
        assert args.driver_root.startswith("/dev/shm/") or args.driver_root == "/dev/shm"
    if args.workload == "partial-maker":
        assert args.mode == "bounded", "partial-maker only supports bounded wire3"
        assert args.max_samples is not None and 3 <= args.max_samples <= 4_000_000
        assert args.warmup_seconds is not None and 20 <= args.warmup_seconds <= 30
        assert args.measure_seconds is not None and 10 <= args.measure_seconds <= 15
    else:
        assert args.max_samples is None and args.warmup_seconds is None and args.measure_seconds is None
    if shutil.disk_usage(args.project).free < 12 * 1024 ** 3:
        raise RuntimeError("at least 12 GiB host free space required before this disposable run")
    run_id = "ha142-" + uuid.uuid4().hex[:10]
    report = args.project / "build/reports/ha-trading" / run_id
    control = report / "control"
    control.mkdir(parents=True)
    image = args.image or "exchange-ha-trading:" + run_id
    commands = []
    env = dict(os.environ, FUNDING_IMAGE=image, FUNDING_RUN_ID=run_id)
    prefix = "Bounded" if args.mode == "bounded" else "Legacy"
    members = "|".join(f"{i},node{i}:19700,node{i}:19701,node{i}:19702,node{i}:19703,node{i}:19704" for i in range(3))
    override = report / "probe.compose.yml"
    lines = ["services:"]
    for i in range(3):
        lines += [f"  node{i}:", f"    command: [com.exchange.core.api.trading.infrastructure.aeron.{prefix}HaTradingNodeKt]",
                  "    cpus: 2.0", "    mem_limit: 2g", "    shm_size: 1g", "    environment:",
                  "      HA_PROBE_CONTROL: /ha-control", "      HA_PROBE_MARKER_ENDPOINT: client:19999",
                  f'      JAVA_TOOL_OPTIONS: "-XX:+UseG1GC -Xms256m -Xmx{args.heap_mb}m"', "    volumes:",
                  f"      - {json.dumps(str(control) + ':/ha-control:rw')}"]
        if args.driver_root:
            lines.insert(len(lines) - 2, f"      FUNDING_DRIVER_ROOT: {json.dumps(args.driver_root)}")
    lines += ["  client:", f"    command: [com.exchange.core.api.trading.infrastructure.aeron.{prefix}HaTradingClientKt, '--warmup={args.warmup}', '--orders={args.orders}']",
              "    cpus: 2.0", "    mem_limit: 2g", "    shm_size: 1g", "    environment:",
              f"      FUNDING_MEMBERS: {json.dumps(members)}", "      FUNDING_HOST: client", "      HA_PROBE_MARKER_PORT: '19999'",
              f"      HA_PROBE_IN_FLIGHT: '{args.in_flight}'", f"      HA_PROBE_EXECUTION_IN_FLIGHT: '{args.execution_in_flight}'",
              f"      HA_PROBE_ACK_MODE: '{args.ack_mode}'",
              "      HA_PROBE_CONTROL: /ha-control",
              f'      JAVA_TOOL_OPTIONS: "-XX:+UseG1GC -Xms256m -Xmx{args.heap_mb}m"']
    if args.workload == "partial-maker":
        lines += [f"      HA_PROBE_WORKLOAD: '{args.workload}'", f"      HA_PROBE_MAX_SAMPLES: '{args.max_samples}'",
                  f"      HA_PROBE_WARMUP_SECONDS: '{args.warmup_seconds}'", f"      HA_PROBE_MEASURE_SECONDS: '{args.measure_seconds}'"]
    lines += ["    volumes:", f"      - {json.dumps(str(control) + ':/ha-control:rw')}"]
    override.write_text("\n".join(lines) + "\n")
    base = args.project / "src/test/docker/aeron-funding/compose.yml"
    compose = ["docker", "compose", "-p", run_id, "-f", str(base), "-f", str(override)]

    def command(arguments, timeout=120, check=True, guard=None, abort=None):
        index = len(commands)
        began = time.monotonic()
        entry = dict(arguments=arguments, exitCode=None)
        commands.append(entry)  # 중첩된 guard·abort 명령을 실행하기 전에 로그 이름을 예약한다.
        stdout = report / f"command-{index}.stdout.log"
        stderr = report / f"command-{index}.stderr.log"
        try:
            if guard is None:
                result = subprocess.run(arguments, cwd=args.project, env=env, text=True, capture_output=True, timeout=timeout)
                stdout.write_text(result.stdout)
                stderr.write_text(result.stderr)
            else:
                result = monitored_client(arguments, args.project, env, timeout, stdout, stderr, guard, abort, on_poll=phase_capture_handler)
            entry["exitCode"] = result.returncode
        except BaseException as failure:
            entry["failure"] = repr(failure)
            raise
        finally:
            entry["elapsedSeconds"] = time.monotonic() - began
        if check and result.returncode:
            raise RuntimeError(f"command failed: {arguments}: {result.stderr[-2000:]}")
        return result

    recording_root = "/records/" + run_id

    def read_allocated(member):
        result = command(compose + ["exec", "-T", f"node{member}", "du", "-sk", "--", recording_root], timeout=5)
        return allocated_bytes(result.stdout, recording_root)

    disk_guard = RunDiskGuard(args.project, read_allocated)
    disk_guard.stats.update(project=run_id, recordingRoot=recording_root,
                            allocationMethod="du -sk allocated 1 KiB blocks from this project's three node roots")
    checkpoints = {}

    def phase_capture_handler(tick):
        for label in ("measure-start", "measure-end"):
            request_path = control / f"client-phase-{label}.request.json"
            if label in checkpoints or not request_path.exists():
                continue
            if label == "measure-end" and "measure-start" not in checkpoints:
                raise RuntimeError("measurement ended before its start checkpoint")
            request = json.loads(request_path.read_text())
            if request.get("label") != label or any(
                    not isinstance(request.get(key), int) or isinstance(request.get(key), bool) or request[key] < 0
                    for key in ("buyerAckFloor", "sellerAckFloor")):
                raise RuntimeError("invalid client checkpoint request")
            disk_guard.stats["clientPhase"] = "warmup-capture" if label == "measure-start" else "final-capture"
            states = capture_phase(control, request, args.mode == "bounded", tick)
            checkpoints[label] = states
            temporary = control / f"client-phase-{label}.ready.tmp"
            temporary.write_text(json.dumps(dict(label=label, members=[state["member"] for state in states])))
            temporary.replace(control / f"client-phase-{label}.ready.json")
            disk_guard.stats["clientPhase"] = "measure-window-and-drain" if label == "measure-start" else "client-finished"

    def abort_client():
        # 이번 실행의 고유 일회성 컨테이너만 종료한다. compose CLI만 종료해서는 충분하지 않다.
        command(["docker", "kill", run_id + "-client"], timeout=10, check=False)

    def events(text):
        return [json.loads(line[len("FUNDING_EVENT "):]) for line in text.splitlines() if line.startswith("FUNDING_EVENT ")]

    summary = dict(runId=run_id, mode=args.mode, wire="protobuf", success=False, commands=commands,
                   executionInFlight=args.execution_in_flight, driverRoot=args.driver_root, ackMode=args.ack_mode,
                   workload=args.workload, maxSamples=args.max_samples,
                   warmupSeconds=args.warmup_seconds, measureSeconds=args.measure_seconds,
                   requestedJvmHeapMiB=args.heap_mb,
                   diskGuard=disk_guard.stats)
    try:
        if not args.image:
            command(["docker", "build", "-f", str(args.project / "src/test/docker/aeron-funding/Dockerfile"), "-t", image, str(args.distribution)], timeout=180)
        command(compose + ["up", "-d", "node0", "node1", "node2"])
        deadline = time.monotonic() + 65
        while True:
            logs = command(compose + ["logs", "--no-color", "--no-log-prefix", "node0", "node1", "node2"], check=False).stdout
            ready = {e["member"]: e for e in events(logs) if e.get("kind") == "HA_READY"}
            if set(ready) == {0, 1, 2}:
                if args.driver_root:
                    assert all(Path(e["driver"]).is_relative_to(args.driver_root) for e in ready.values())
                summary["actualDriverDirectories"] = {member: e.get("driver") for member, e in ready.items()}
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("three node processes were not ready")
            time.sleep(.5)
        summary["actualNodeMaxHeapBytes"] = {}
        for member in range(3):
            flags = command(compose + ["exec", "-T", f"node{member}", "jcmd", "1", "VM.flags"]).stdout
            summary["actualNodeMaxHeapBytes"][str(member)] = verify_heap_flags(flags, args.heap_mb)
        disk_guard.check("before-client")
        client = command(compose + ["run", "--rm", "--use-aliases", "--name", run_id + "-client", "client"],
                         timeout=180, guard=disk_guard, abort=abort_client)
        measurements = [e for e in events(client.stdout) if e.get("kind") == "HA_PERFORMANCE"]
        if len(measurements) != 1 or not measurements[0].get("completionValid"):
            raise RuntimeError("client did not produce one complete measurement")
        measurement = measurements[0]
        if not measurement.get("serviceCheckpointsRequested") or set(checkpoints) != {"measure-start", "measure-end"}:
            raise RuntimeError("both all-node service checkpoints required")
        measurement["serviceInterval"] = checkpoint_deltas(checkpoints["measure-start"], checkpoints["measure-end"])
        collector_start, collector_end = (measurement["collectorCheckpoints"][key] for key in ("before", "after"))
        measurement["collectorInterval"] = {
            key: nonnegative_delta(collector_start[key], collector_end[key], "collector:" + key)
            for key in ("observedFrames", "registeredResponses", "drainedCompletions", "lockWaitSamples", "lockWaitTotalNs")}
        measurement["collectorInterval"].update(timingSampleEvery=256, maxSemantics="no maximum derived from cumulative checkpoints")
        for node in measurement["serviceInterval"]["nodes"]:
            if any(node["callbacks"][kind] != measurement["orders"] for kind in ("admission", "execution")):
                raise RuntimeError("measured callback cohort includes warmup or misses an order")
        gates = measurement["gateDwell"]
        if len(gates["masks"]) != 8 or {entry["mask"] for entry in gates["masks"]} != set(range(8)) or any(
                entry["totalNs"] < 0 for entry in gates["masks"]):
            raise RuntimeError("invalid bounded gate-mask observations")
        if sum(entry["totalNs"] for entry in gates["masks"]) != gates["elapsedNs"] or gates["elapsedNs"] > measurement["actualOfferingWindowNs"]:
            raise RuntimeError("overlapping or out-of-window gate dwell")
        summary["serviceCheckpoints"] = {
            label: [{key: state[key] for key in ("member", "role", "serviceBoundary", "metrics", "markerSender")} for state in states]
            for label, states in checkpoints.items()}
        assert measurement["workload"] == args.workload and not measurement["sampleCapReached"]
        total = measurement["warmup"] + measurement["orders"]
        capacity = args.max_samples if args.workload == "partial-maker" else args.warmup + args.orders
        sell_orders = 3 if args.workload == "partial-maker" else capacity
        assert measurement["actualTotalBuys"] == total and measurement["sellPreparation"] == sell_orders
        assert measurement["expectedBuyerAckFloor"] == total and measurement["expectedSellerAckFloor"] == sell_orders
        if args.workload == "partial-maker":
            assert 0 < total < capacity, "sample budget exhaustion invalidates duration measurements"
            assert measurement["warmupSeconds"] == args.warmup_seconds and measurement["measureSeconds"] == args.measure_seconds
            assert measurement["actualOfferingWindowNs"] >= args.measure_seconds * 1_000_000_000
            for phase in ("admission", "leaderCompletion", "allReplicas"):
                assert measurement[phase + "ElapsedNs"] >= measurement["actualOfferingWindowNs"]
                assert measurement[phase + "ElapsedNs"] >= measurement[phase + "RawSpanNs"]
            assert measurement["cohortElapsedIncludingAckNs"] == measurement["phaseElapsedIncludingAckNs"]
            assert measurement["cohortElapsedIncludingAckNs"] >= measurement["allReplicasElapsedNs"]
        else:
            assert total == capacity and measurement["warmup"] == args.warmup and measurement["orders"] == args.orders
        # 리더의 접수 ACK만으로 팔로워가 마지막 ACK까지 소비했다고 판단할 수 없다.
        # 세 노드 모두 정확한 회수 하한을 보일 때까지 측정 요청 구간 밖에서 캡처한다.
        deadline = time.monotonic() + 30
        for attempt in range(50):
            label = f"final-{attempt}"
            for i in range(3):
                temporary = control / f"node-{i}.command.tmp"
                temporary.write_text("CAPTURE:" + label)
                temporary.rename(control / f"node-{i}.command")
            captures = [control / f"state-{i}-{label}.json" for i in range(3)]
            while not all(p.exists() for p in captures):
                if time.monotonic() >= deadline:
                    raise RuntimeError("three current-state captures were not produced")
                time.sleep(.05)
            states = [json.loads(p.read_text()) for p in captures]
            if args.mode == "legacy" or all(
                    sorted(s["receiptFloors"]) == [["BUYER", 0, 1, total, total], ["SELLER", 0, 1, sell_orders, sell_orders]]
                    for s in states):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("followers did not consume the exact final receipt ACK floors")
            time.sleep(.05)
        else:
            raise RuntimeError("bounded final ACK capture attempt budget exhausted")
        if len({s["stateSha256"] for s in states}) != 1:
            raise RuntimeError("replicas differ after the complete input cohort and receipt ACKs")
        buyer_quote = capacity * 10020 + 1000 - total * 10020
        remaining_by_market = {}
        for state in states:
            assert state["sync"] == [2, 2, 2] and state["pending"] == 0
            assert int(state["feeRevenue"]) == total * 30
            balances = {(u, a): (available, hold) for u, a, available, hold in state["balances"]}
            assert balances["BUYER", "USDT"] == (buyer_quote, 0)
            assert balances["SELLER", "USDT"] == (total * 9990, 0)
            remaining_by_market = verify_market_balances(balances, capacity, total)
            if args.mode == "bounded":
                expected_live = {market: amounts for market, amounts in remaining_by_market.items() if amounts[1] > 0}
                assert state["liveOrders"] == len(expected_live) and state["retainedReceipts"] == 0
                makers = {row[1]: row for row in state["liveMakers"]}
                assert len(makers) == len(state["liveMakers"]) and set(makers) == set(expected_live)
                for market, (original, remaining) in expected_live.items():
                    assert makers[market] == ["SELLER", market, "SELL", "RESTING", original, remaining, remaining]
                floors = {row[0]: row for row in state["receiptFloors"]}
                assert len(state["receiptFloors"]) == 2
                assert floors["BUYER"] == ["BUYER", 0, 1, total, total]
                assert floors["SELLER"] == ["SELLER", 0, 1, sell_orders, sell_orders]
                assert state["lastAdmissionSequence"] == total + sell_orders
        assert measurement["expectedBuyerQuoteAvailable"] == buyer_quote
        assert measurement["expectedMakerRemaining"] == [remaining_by_market[m][1] for m in ("BTC-USDT", "SOL-USDT", "SUI-USDT")]
        size = sum(p.stat().st_size for p in report.rglob("*") if p.is_file())
        if size > 2 * 1024 ** 3:
            raise RuntimeError("disposable report budget exceeded")
        disk_guard.check("final-verified-state")
        summary.update(success=True, measurement=measurements[0], financialAndReplicaStateVerified=True,
                       stateSha256=states[0]["stateSha256"], reportBytes=size,
                       limitation=("duration window with explicit finite sample budget; partial makers retained; same-host Docker CPU/IO; "
                                   "not absolute maximum sustainable TPS or remote disaster RPO0" if args.workload == "partial-maker" else
                                   "finite cohort; same-host Docker CPU/IO; not maximum sustainable TPS or remote disaster RPO0"))
    except BaseException as failure:
        summary["failure"] = repr(failure)
        summary["failureNotes"] = getattr(failure, "__notes__", [])
        raise
    finally:
        # 로그·시간 초과·중단으로 앞 단계가 실패해도 각 단계를 모두 시도한다.
        # 이번 고유 프로젝트의 클라이언트·컨테이너·폐기 가능한 이름 있는 볼륨만 제거한다.
        cleanup_failures = cleanup_owned_run(command, compose, run_id + "-client", None if args.image else image)
        previously_successful = summary["success"]
        summary["cleanupFailures"] = cleanup_failures
        summary["ownedProjectCleanupSucceeded"] = not cleanup_failures
        if cleanup_failures:
            summary["success"] = False
            summary.setdefault("failure", "owned project cleanup failed")
        (report / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        print(str(report / "summary.json"))
        if cleanup_failures and previously_successful:
            raise RuntimeError("owned project cleanup failed")


if __name__ == "__main__":
    main()
