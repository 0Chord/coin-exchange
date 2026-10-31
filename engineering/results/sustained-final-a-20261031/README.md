# 최종 A의 600초 관측 근거와 재현 소스

`summary.json`은 21,294,294건 / 600.052195940초 = 35,487.402836 TPS의 원자료다. `control/`은 시작·끝·최종의 세 노드 상태이며, `independent-final-result-audit.json`은 별도 읽기 전용 대조 결과다. `execution-plan.json`·`execution.json`·`runtime-diff.json`과 manifest에 당시 설정·명령·실행물 지문을 남겼다.

제품 코드와 기본 측정 클라이언트는 PR의 소스다. 기본 클라이언트는 짧은 시험용이므로, 600초 시험에 실제 사용한 `client-source/`의 두 Kotlin 파일과 `ha-trading-probe-600.py`를 함께 보존한다. Kotlin 소스는 기존 24개 관련 검사에서 검증한 소스와 바이트 단위로 같다. 컴파일 결과·원본 Archive·수천 개 command 로그는 게시하지 않는다.

## 폐기 가능한 별도 복제본에서 준비

기존 작업 폴더를 덮어쓰지 않도록 새 경로를 사용한다. Java 25·Docker가 필요하며 기록 한도 24GiB와 호스트 최소 여유 8GiB를 함께 고려해 공간을 준비한다. 아래 초기화 스크립트는 HdrHistogram 2.2.2를 시험 컴파일에만 제공한다.

```sh
git clone --branch test/http-trading-performance/142 https://github.com/0Chord/coin-exchange.git /tmp/exchange-sustained-replay-142
cd /tmp/exchange-sustained-replay-142
cp engineering/results/sustained-final-a-20261031/client-source/*.kt app-api/src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/
./gradlew --no-daemon --max-workers=1 -I engineering/results/sustained-final-a-20261031/compile-client.init.gradle :app-api:test --tests '*HaTradingProbeFlowTest' --tests '*HaTradingProbeMarkerTest' --tests '*HaTradingProbeMetricsTest' --console=plain
```

이는 테스트 출력만 600초 클라이언트로 바꾼다. main 클래스와 라이브러리의 지문을 `base-runtime-manifest.json`과 대조한 뒤 실행한다. 다른 컴퓨터·빌드 환경에서는 원래 실행 이미지와 바이너리 전체가 같다고 가정하지 않는다.

```sh
cd /tmp/exchange-sustained-replay-142
python3 engineering/results/sustained-final-a-20261031/ha-trading-probe-600.py --project "$PWD/app-api" --distribution "$PWD/app-api/build/aeron-verification" --mode bounded --warmup 1000 --orders 5000 --in-flight 512 --execution-in-flight 1024 --ack-mode async --driver-root /dev/shm --workload partial-maker --max-samples 64000000 --warmup-seconds 20 --measure-seconds 600 --heap-mb 1024
```

제어기는 자기 실행의 `ha142-*` 컨테이너·기록 볼륨·네트워크와 직접 만든 이미지를 종료 시 정리한다. 통계·상태 캡처는 `app-api/build/reports/ha-trading/`에 남는다. 같은 클러스터에서 순번·금융 상태를 유지하고 측정 배열만 작은 구간마다 정리한다. 구간 준비·drain·집계·마지막 ACK는 전체 TPS에 포함된다.

이번 게시 단계에서 위 새 복제본 절차로 10분 부하를 추가 실행하지는 않았다. 관측 근거는 `execution.json`에 기록한 기존 실제 실행이며, 이 문서는 그때 사용한 소스를 접근 가능하게 보존한 재현 절차다. 절대 최대 TPS나 동일 성능을 보장하지 않는다.
