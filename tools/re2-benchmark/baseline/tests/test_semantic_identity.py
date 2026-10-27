import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path


BASE = Path(__file__).parents[1]


class TestSemanticIdentity(unittest.TestCase):
    def test_native_probe_options_do_not_reach_measurements(self):
        source = (BASE / "run-shard.sh").read_text()
        options = source[source.index("route_jvm_arguments=("):source.index("prepare_jmh_host_compiler_arguments()")]
        start = source.index("SEMANTIC_FAILURE_STATUS=")
        functions = source[start:source.index("\n}\n", source.index("run_semantic_verifier()")) + 3]
        probe = source[source.index("expected_native_access=false\n"):source.index("# Units of one shard on one host")]
        for route in ("native-access", "object-row"):
            with self.subTest(route=route), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                script = "set -euo pipefail\n"
                script += f"ROUTE={shlex.quote(route)}\nHEAP_SIZE=8g\nCPU_LIST=0\nCLASSPATH=fixture\nUNIT_DIRECTORY=\n"
                script += f"RESULT_DIR={shlex.quote(str(root))}\nmkdir -p {shlex.quote(str(root / 'logs'))}\n"
                script += functions
                script += f"semantic_log={shlex.quote(str(root / 'probe.log'))}\n"
                script += 'taskset() { shift 2; "$@"; }\njava() { printf "%s\\n" "$@"; }\n'
                script += options + probe
                script += 'printf "%s\\n" "${route_jvm_arguments[@]}" "${jmh_process_jvm_arguments[@]}" "${jmh_fork_arguments}"\n'
                result = subprocess.run(["bash"], input=script, text=True, capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                probe_arguments = (root / "probe.log").read_text().splitlines()
                expected = "true" if route == "native-access" else "false"
                flag = f"-Dio.airlift.regulator.dfa.native-reader-probe={expected}"
                self.assertIn(flag, probe_arguments)
                self.assertEqual(["io.airlift.regulator.DfaAbsolutePointerProbe", expected], probe_arguments[-2:])
                measurement_arguments = result.stdout.splitlines()[len(probe_arguments):]
                self.assertTrue(measurement_arguments)
                self.assertFalse(any("native-reader-probe" in argument for argument in measurement_arguments))


if __name__ == "__main__":
    unittest.main()
