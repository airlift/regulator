"""Static measurement seconds of manifest rows under their frozen protocols.

These are the per-row, per-selector, and representative costs of a unit's JMH
runs, and the cost of a language observation's runs. Engines are prebuilt, so
building, pinned Trino preparation, and host bootstrap are not charged; they do
not scale with the rows a host measures.
"""
import math

from shard_results import jmh_runtime_seconds

import row_protocol


JMH_STARTUP_SECONDS = 2
# A lease's timeout is this multiple of its groups' estimate, and a language measurement process's limit this
# multiple of its own, so a slow host outruns neither by its estimate's error alone.
TIMEOUT_FACTOR = 2
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


def iteration_seconds(protocol, operation_seconds=None):
    """A JMH iteration's length: the protocol's, unless operation_seconds, an operation's measured time, stretches it.
    An iteration ends only with the operation in progress, so it lasts whole operations."""
    iteration = protocol["iteration_seconds"]
    if operation_seconds is None:
        return iteration
    return math.ceil(iteration / operation_seconds) * operation_seconds


def language_process_seconds(protocol, native, operation_seconds=None, allocation=False):
    """One measurement process of a language observation whose operation takes operation_seconds, when known: a
    native comparator's repetitions, each a warmup and a timed run that last at least one operation, or a JMH
    launcher and its timed forks, or with allocation, the forks of the protocol's separate allocation pass."""
    if native:
        operation = operation_seconds or 0
        return protocol["native_repetitions"] * (max(protocol["native_warmup_seconds"], operation) +
                                                 max(protocol["native_min_time_seconds"], operation))
    prefix = "allocation_" if allocation else ""
    forks = protocol[f"{prefix}forks"]
    return JMH_STARTUP_SECONDS + forks * JMH_STARTUP_SECONDS + jmh_runtime_seconds(
        1, forks, protocol[f"{prefix}warmup_iterations"], protocol[f"{prefix}measurement_iterations"],
        iteration_seconds(protocol, operation_seconds))


def language_seconds(protocol, native, measured, verification_processes, operation_seconds=None):
    """Seconds to verify and measure one language observation under one of its case's operations.

    Verification starts verification_processes processes, each charged a startup, a conservative allowance even
    for a not-compatible observation, which only verifies. A measured observation then runs its native comparator,
    or JMH and the separate allocation pass of a protocol that has one."""
    seconds = verification_processes * JMH_STARTUP_SECONDS
    if measured:
        seconds += language_process_seconds(protocol, native, operation_seconds)
        if not native and "allocation_forks" in protocol:
            seconds += language_process_seconds(protocol, False, operation_seconds, allocation=True)
    return seconds


def measurement_budget(protocol, native, operation_seconds=None, allocation=False):
    """The whole seconds a language measurement process may run before it is killed: the protocol's limit, or
    TIMEOUT_FACTOR times the process's estimate when that is longer, as a lease allows its groups' estimate."""
    return max(protocol["measurement_timeout_seconds"], math.ceil(
        TIMEOUT_FACTOR * language_process_seconds(protocol, native, operation_seconds, allocation)))


def measurement_seconds(rows, protocols):
    return sum(row_seconds(protocols[row["row_id"]]) for row in rows)


def estimate(rows, protocols, representative_count=0):
    return (
        measurement_seconds(rows, protocols) +
        sum(selector_startup_seconds(protocol) for protocol in selectors(rows, protocols).values()) +
        representative_seconds(representative_count))
