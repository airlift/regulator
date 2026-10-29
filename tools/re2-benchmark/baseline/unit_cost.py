"""Static measurement seconds of manifest rows under their frozen protocols.

These are the per-row, per-selector, and representative costs of a unit's JMH
runs, and the cost of a language comparison's runs. Engines are prebuilt, so
building, pinned Trino preparation, and host bootstrap are not charged; they do
not scale with the rows a host measures.
"""
from shard_results import jmh_runtime_seconds

import row_protocol


JMH_STARTUP_SECONDS = 2
# A full five-fork reference and five bounded host-JVM processes for one representative.
PROTOCOL_REPRESENTATIVE_SECONDS = 132


def measured_seconds(protocol, processes):
    return (
        jmh_runtime_seconds(1, processes, int(protocol["warmup_iterations"]), 0,
                            row_protocol.seconds(protocol["warmup_time"])) +
        jmh_runtime_seconds(1, processes, 0, int(protocol["measurement_iterations"]),
                            row_protocol.seconds(protocol["measurement_time"])))


def row_seconds(protocol):
    execution = protocol["execution"]
    if execution == "jmh-processes":
        return measured_seconds(protocol, int(protocol["processes"]))
    if execution == "jmh-forks":
        forks = int(protocol["processes"])
        return measured_seconds(protocol, forks) + forks * JMH_STARTUP_SECONDS
    if execution == "native-repetitions":
        warmup = int(protocol["warmup_iterations"]) * row_protocol.seconds(protocol["warmup_time"])
        measurement = int(protocol["measurement_iterations"]) * row_protocol.seconds(protocol["measurement_time"])
        # Google Benchmark grows the warmup and the first repetition through shorter sizing runs, aiming each
        # at 1.4 times its minimum time, the margin its iteration estimate adds; later repetitions reuse the
        # first one's iteration count. SHARD_DISPATCH.md describes the sizing.
        return 2.5 * warmup + 1.5 * measurement
    raise ValueError(f"no cost rule for execution {execution!r}")


def selector_startup_seconds(protocol):
    """Top-level JVM startups of one JMH selector; forks charge their own startups per row.

    A process selector starts its compiler-hints JVM and then its processes; a
    forked selector starts its launcher JVM, and a native selector its runner.
    """
    if protocol["execution"] == "jmh-processes":
        return (int(protocol["processes"]) + 1) * JMH_STARTUP_SECONDS
    return JMH_STARTUP_SECONDS


def representative_seconds(count):
    return count * PROTOCOL_REPRESENTATIVE_SECONDS


def compares_with_reference(protocol):
    """A representative compares a row's frozen windows with the ten-by-one-second reference."""
    return protocol["measurement_time"] != row_protocol.FULL_WINDOW


def runs_each_workload(row):
    """A specialized-pattern suite runs each workload in its own JMH invocation, as a Trino query would."""
    return "specialized" in row["suite"].split("-")


def selectors(rows, protocols):
    """JMH selectors: one exact method, system, and protocol, over a Cartesian parameter block.

    A row whose suite runs each workload alone is its own selector. The unit
    planner merges methods that share one -p block into one selector, so this
    per-method grouping charges more JVM startups than a unit runs; that is a
    deliberately conservative allowance.
    """
    result = {}
    for row in rows:
        protocol = protocols[row["row_id"]]
        key = (row["system"], row["benchmark"], row_protocol.protocol_key(protocol),
               row["row_id"] if runs_each_workload(row) else None)
        result.setdefault(key, protocol)
    return result


def language_jmh_seconds(protocol):
    """One language observation's JMH run: its launcher, its timed forks, and the separate allocation pass
    of a protocol that has one."""
    seconds = (JMH_STARTUP_SECONDS + protocol["forks"] * JMH_STARTUP_SECONDS + jmh_runtime_seconds(
        1, protocol["forks"], protocol["warmup_iterations"], protocol["measurement_iterations"],
        protocol["iteration_seconds"]))
    if "allocation_forks" in protocol:
        seconds += (JMH_STARTUP_SECONDS + protocol["allocation_forks"] * JMH_STARTUP_SECONDS + jmh_runtime_seconds(
            1, protocol["allocation_forks"], protocol["allocation_warmup_iterations"],
            protocol["allocation_measurement_iterations"], protocol["iteration_seconds"]))
    return seconds


def language_seconds(protocol, jvm_observations, native_observations, verification_processes):
    """Seconds to verify and measure one language comparison under one of its case's operations.

    JVM observations run JMH; a native comparator warms up and measures each repetition. Verification
    starts verification_processes processes for each of the comparison's three observations.
    """
    native = protocol["native_repetitions"] * (protocol["native_warmup_seconds"] + protocol["native_min_time_seconds"])
    return (jvm_observations * language_jmh_seconds(protocol) + native_observations * native +
            3 * verification_processes * JMH_STARTUP_SECONDS)


def measurement_seconds(rows, protocols):
    return sum(row_seconds(protocols[row["row_id"]]) for row in rows)


def estimate(rows, protocols, representative_count=0):
    return (
        measurement_seconds(rows, protocols) +
        sum(selector_startup_seconds(protocol) for protocol in selectors(rows, protocols).values()) +
        representative_seconds(representative_count))
