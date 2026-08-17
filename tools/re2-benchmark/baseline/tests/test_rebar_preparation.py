import os
import shlex
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path


BASE = Path(__file__).parents[1]


class TestRebarPreparation(unittest.TestCase):
    def test_shared_joni_directory_survives_both_route_orders_and_cache_reuse(self):
        source = (BASE / "run-shard.sh").read_text()
        preparation = source[source.index("prepare_rebar()\n"):source.index("    local primary_systems\n")]
        preparation += "}\nprepare_rebar\n"
        for routes in (("native-access", "object-row"), ("object-row", "native-access")):
            with self.subTest(routes=routes), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                repository = root / "repository"
                scripts = repository / "tools/re2-benchmark"
                rebar = root / "rebar"
                shared = root / "shared"

                def executable(path, text):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + text)
                    path.chmod(0o755)

                # Run the actual staging script with tiny corpus inputs. Only builds
                # and revision checks are replaced; its child environment is real.
                (scripts / "rebar").mkdir(parents=True)
                shutil.copy2(BASE.parent / "rebar/prepare.sh", scripts / "rebar/prepare.sh")
                executable(scripts / "manifests/validate-rebar-workloads.sh", "exit 0\n")
                executable(scripts / "manifests/validate-extended-rebar.sh", "exit 0\n")
                executable(scripts / "manifests/rebar-revision.sh", "echo pinned-fixture\n")
                (scripts / "manifests/rebar-extended-workloads.tsv").write_text("fixture\n")
                executable(scripts / "rebar/run-regulator.sh", "exit 0\n")
                executable(scripts / "rebar/build-regulator.sh",
                           f"mkdir -p {shlex.quote(str(repository / 'target'))}\n"
                           f"echo fixture > {shlex.quote(str(repository / 'target/rebar-classpath.txt'))}\n")
                executable(rebar / "target/release/rebar", "exit 0\n")
                for name in ("definitions/curated", "haystacks", "regexes"):
                    (rebar / "benchmarks" / name).mkdir(parents=True)
                (rebar / "benchmarks/engines.toml").write_text('[[engine]]\n  name = "re2"\n  cwd = "fixture"\n')
                (rebar / "benchmarks/definitions/curated/fixture.toml").write_text("engines = [\n  're2',\n]\n")
                for name in ("native-portable", "native-tuned"):
                    executable(shared / "rebar" / name / "target/release/main", "exit 0\n")

                environment = dict(os.environ)
                environment.pop("TRINO_COMPARATOR_WORK_DIR", None)
                configurations = []
                for invocation, route in enumerate((*routes, routes[0])):
                    results = root / f"result-{invocation}"
                    for name in ("raw", "logs"):
                        (results / name).mkdir(parents=True)
                    variables = {
                        "ROOT": str(repository), "SHARED_WORK_DIR": str(shared),
                        "REBAR_ROOT": str(rebar), "RESULT_DIR": str(results),
                        "RESULT_TOOL": str(BASE / "shard_results.py"), "MANIFEST": str(BASE / "rows.tsv"),
                        "SHARD_ID": "rebar-a", "ROUTE": route, "semantic_log": str(results / "semantic.log"),
                        "candidate_commit": "fixture", "engine_tree": "fixture", "manifest_sha256": "fixture",
                        "candidate_system": "regulator-native-access" if route == "native-access" else "regulator-object-row",
                        "systems": "regulator-native-access,native-re2-before,native-re2-after,joni"
                        if route == "native-access" else "regulator-object-row",
                        "REBAR_COMPARATOR_ORDER": "forward" if routes[0] == "native-access" else "reverse",
                    }
                    script = "set -euo pipefail\n" + "\n".join(
                        f"{key}={shlex.quote(value)}" for key, value in variables.items()) + "\n"
                    script += '''
cargo() { echo build >> "${SHARED_WORK_DIR}/builds"; }
prepare_pinned_trino() {
    PINNED_TRINO_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino"
    mkdir -p "${PINNED_TRINO_WORK_DIR}"
    echo fixture > "${PINNED_TRINO_WORK_DIR}/provenance.properties"
}
'''
                    result = subprocess.run(["bash"], input=script + preparation, env=environment,
                                            text=True, capture_output=True, timeout=30)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    config = (shared / "rebar/rebar-a/benchmarks/engines.toml").read_bytes()
                    configurations.append(config)
                    engines = tomllib.loads(config.decode())["engine"]
                    joni = next(engine for engine in engines if engine["name"] == "joni/trino")
                    actual = {item["name"]: item["value"] for item in joni["run"]["envs"]}
                    self.assertEqual(str(shared / "pinned-trino"), actual["TRINO_COMPARATOR_WORK_DIR"])
                self.assertEqual(["build"], (shared / "builds").read_text().splitlines())
                self.assertEqual(configurations[0], configurations[1])
                self.assertEqual(configurations[0], configurations[2])


if __name__ == "__main__":
    unittest.main()
