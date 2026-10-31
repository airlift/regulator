"""The campaign controller's telemetry: the progress model and health as metrics, its events as logs, and each unit
attempt as a trace, exported over OTLP/HTTP to the collector on the control host.

The controller is the only exporter, and only where OTEL_EXPORTER_OTLP_ENDPOINT names the collector, as the control
host's controller unit does; anywhere else nothing is exported. Export runs on the SDK's own threads, so a collector
that is down drops data and never holds up the loop. The SDK is imported only when something is exported, so the
commands that export nothing, such as status, run without it.
"""

import os
from datetime import datetime

SERVICE = "regulator-campaign"
# Each gauge, observed as the controller's latest loop left it, with its description. Prometheus keeps these names.
GAUGES = {
    "regulator_loop_seconds": "Seconds the controller's latest loop took",
    "regulator_spending_stop": "1 while a spending stop stands",
    "regulator_settings_version": "The live settings' version",
    "regulator_boot_window_hosts": "Hosts the boot window admits launched and not yet reporting",
    "regulator_max_usd": "The campaign's spending ceiling, in USD",
    "regulator_phase_state": "1 at each phase's state",
    "regulator_region_vcpus": "vCPUs the campaign's hosts hold, by region and market",
    "regulator_region_max_vcpus": "vCPUs the settings allow the campaign, by region and market",
    "regulator_region_booting_hosts": "Hosts launched and not yet reporting, by region",
    "regulator_workers": "A phase's workers, by pool and state",
    "regulator_queued_items": "A phase's work items queued for a lease, by pool",
    "regulator_units": "A phase's unit replicas",
    "regulator_accepted_units": "A phase's unit replicas with every group accepted",
    "regulator_groups": "A phase's groups",
    "regulator_accepted_groups": "A phase's accepted groups",
    "regulator_unresolved_units": "A phase's unit replicas left unresolved",
    "regulator_work_left_seconds": "Estimated seconds of a phase's groups queued or in flight, by pool",
    "regulator_correction": "Measured over estimated seconds of a phase's accepted groups, by pool",
    "regulator_finish_time": "When a phase's pool finishes at its live hosts, in epoch seconds",
    "regulator_deadline_time": "When a phase's launches end, in epoch seconds",
    "regulator_slack_min": "The fewest leases held beyond the one it runs by a running worker of the pool",
    "regulator_slack_zero_workers": "Running workers with no lease beyond the one they run in a pool with work queued",
    "regulator_boot_to_ready_seconds": "Median seconds from a phase's hosts' launch to their first report",
    "regulator_launch_refused_seconds": "Seconds a phase's launches have been refused, by reason",
    "regulator_leftovers": "A phase's ended hosts whose prefix is not yet recovered and empty",
    "regulator_spend_usd": "A phase's spend so far, in USD",
    "regulator_spend_rate_usd_per_hour": "A phase's live hosts' spend rate, in USD per hour",
    "regulator_spend_projected_usd": "A phase's spend by its finish at its live hosts, or by its reservations' ends",
    "regulator_worker_group_estimated_seconds": "The estimated seconds of the group a worker runs",
    "regulator_worker_group_elapsed_seconds": "The seconds since the group a worker runs started",
}
# The gauges whose series each stand for a condition, a launch refusal or a worker's or phase's state: a series an
# earlier loop observed and the latest does not reads 0, so a panel's current value shows the condition cleared
# rather than the last value it had.
CLEARED_GAUGES = ("regulator_launch_refused_seconds", "regulator_workers", "regulator_phase_state")
# Each counter, with its description; Prometheus names each with _total.
COUNTERS = {
    "regulator_idle_stops": "Hosts that stopped themselves idle, by whether their pool had work queued",
    "regulator_interruptions": "Hosts a Spot reclaim ended",
    "regulator_stalled_hosts": "Hosts stopped for a lease that outran its timeout",
}


def start(campaign):
    """The campaign's telemetry: exported to the collector OTEL_EXPORTER_OTLP_ENDPOINT names, or, without one,
    nowhere."""
    if not os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        return Telemetry(campaign)
    from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
    from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
    # The collector reads the gauges as often as the loop sets them.
    return Telemetry(campaign, [PeriodicExportingMetricReader(OTLPMetricExporter(), export_interval_millis=15_000)],
                     [BatchSpanProcessor(OTLPSpanExporter())], [BatchLogRecordProcessor(OTLPLogExporter())])


class Telemetry:
    """Where the controller's metrics, logs and traces go: the SDK's providers with the readers and processors given,
    or with none, nowhere, without the SDK. An event's line is printed either way."""

    def __init__(self, campaign="", readers=(), span_processors=(), log_processors=()):
        self.exporting = bool(readers or span_processors or log_processors)
        self.points = {}
        # Every series of each of CLEARED_GAUGES observed so far, by its sorted attributes.
        self.observed = {name: {} for name in CLEARED_GAUGES}
        if not self.exporting:
            return
        from opentelemetry import trace
        from opentelemetry.metrics import Observation
        from opentelemetry.sdk._logs import LoggerProvider
        from opentelemetry.sdk.metrics import MeterProvider
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        resource = Resource.create({"service.name": SERVICE, "service.instance.id": campaign})
        self.meters = MeterProvider(metric_readers=list(readers), resource=resource, shutdown_on_exit=False)
        self.tracers = TracerProvider(resource=resource, shutdown_on_exit=False)
        self.loggers = LoggerProvider(resource=resource, shutdown_on_exit=False)
        for processor in span_processors:
            self.tracers.add_span_processor(processor)
        for processor in log_processors:
            self.loggers.add_log_record_processor(processor)
        meter = self.meters.get_meter(SERVICE)
        for name, description in GAUGES.items():
            meter.create_observable_gauge(name, [lambda options, name=name: [
                Observation(value, attributes) for attributes, value in self.points.get(name, ())]],
                description=description)
        self.counters = {name: meter.create_counter(name, description=description)
                         for name, description in COUNTERS.items()}
        self.tracer = self.tracers.get_tracer(SERVICE)
        self.logger = self.loggers.get_logger(SERVICE)
        self.context = trace.set_span_in_context

    def observe(self, points):
        """Set every gauge to points, each gauge's values by its name as (attributes, value); a gauge points names no
        value of reads none, but each series of CLEARED_GAUGES observed before reads 0."""
        unknown = set(points) - GAUGES.keys()
        if unknown:
            raise ValueError(f"no such gauge: {', '.join(sorted(unknown))}")
        points = dict(points)
        for name, observed in self.observed.items():
            named = {tuple(sorted(attributes.items())): attributes for attributes, _ in points.get(name, ())}
            observed.update(named)
            points[name] = [*points.get(name, ()), *((attributes, 0) for key, attributes in observed.items()
                                                     if key not in named)]
        self.points = points

    def count(self, name, **attributes):
        if self.exporting:
            self.counters[name].add(1, attributes)

    def event(self, name, text, **attributes):
        """Print an event's line, and log it with the local time in its line and its attributes."""
        print(text, flush=True)
        if self.exporting:
            self.logger.emit(body=f"{datetime.now().astimezone():%Y-%m-%d %H:%M:%S %Z} {text}", event_name=name,
                             attributes={"event": name, **attributes})

    def attempt(self, attributes, start, end, steps):
        """Trace a unit attempt from start to end, epoch seconds, with its steps, each (name, start, end), as its
        children."""
        if not self.exporting:
            return

        def nanoseconds(seconds):
            return int(seconds * 1_000_000_000)

        span = self.tracer.start_span("unit attempt", attributes=attributes, start_time=nanoseconds(start))
        for name, begun, ended in steps:
            self.tracer.start_span(name, context=self.context(span), start_time=nanoseconds(begun)).end(
                end_time=nanoseconds(ended))
        span.end(end_time=nanoseconds(end))

    def shutdown(self):
        """Export what is pending, as far as the collector takes it, and stop."""
        if self.exporting:
            for provider in (self.meters, self.tracers, self.loggers):
                provider.shutdown()
