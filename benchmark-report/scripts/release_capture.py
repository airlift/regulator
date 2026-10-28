"""Import complete release campaigns while retaining artifact and collector identities."""

import csv
import hashlib
import json
from pathlib import Path
import sys

from baseline_data import baseline_rows, read_rows
from language_data import CURRENT_PLATFORMS, LANGUAGES, MEMORY_MODES, digest, language_rows
from report_data import validate_report

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / 'tools/re2-benchmark/baseline'
sys.path.insert(0, str(BASELINE))
sys.path.insert(0, str(ROOT / 'tools/re2-benchmark/language'))
import fleet
import released_artifact
import unit_session


def load(path):
    return json.loads(Path(path).read_text())


def load_collector(item, plan, release):
    path = item.get('candidate_provenance', plan.get('candidate_provenance'))
    if not path:
        raise ValueError('each release capture requires a pinned collector')
    with Path(path).open() as source:
        collectors = list(csv.DictReader(source, delimiter='\t'))
    if len(collectors) != 1 or collectors[0]['engine_tree'] != release['engine_tree']:
        raise ValueError('collector source does not match the released production tree')
    return collectors[0]


def normalize_language(data, manifest, expected_release, collector, receipts):
    plan = data['plan']
    if plan['replicas'] != 3 or 'selection' in plan:
        raise ValueError('release language capture requires the complete three-host matrix')
    allocations = fleet.platform_allocations(manifest['suite'], plan['platforms'])
    if plan.get('schema_version') != 4 or plan.get('platform_allocations') != allocations:
        raise ValueError('release language capture changed its frozen worker allocations')
    expected_candidate = {'source_commit': collector['candidate_commit'], 'source_tree': collector['root_tree']}
    if any(data['candidate'][key] != value for key, value in expected_candidate.items()):
        raise ValueError('language capture changed the collector identity')
    by_partition = {part['id']: part for part in plan['partitions']}
    jobs = {job['id']: job for job in plan['jobs']}
    if len(jobs) != len(plan['jobs']) or len(data['hosts']) != len(jobs):
        raise ValueError('language capture has missing or duplicate hosts')
    accepted = {}
    for receipt in receipts:
        if receipt['plan_sha256'] != digest_plan(plan):
            continue
        if (receipt.get('released_artifact') != expected_release or
                receipt['candidate_commit'] != collector['candidate_commit'] or
                receipt['candidate_archive_sha256'] != collector['archive_sha256']):
            raise ValueError('accepted language batch changed the released artifact or collector')
        for job, checksum in receipt['exports'].items():
            if job in accepted:
                raise ValueError('duplicate accepted language export')
            accepted[job] = (checksum, receipt['instance_id'])
    if set(accepted) != set(jobs):
        raise ValueError('accepted language ledger does not cover the complete plan')
    cases = {case['id']: case for case in manifest['cases']}
    identities, hosts, instances = {}, [], {}
    for host in data['hosts']:
        job, provenance = host['job'], host['provenance']
        if job['id'] in identities or jobs.get(job['id']) != job:
            raise ValueError('language host changed its frozen assignment')
        release = provenance['jvm_build'].get('released_artifact')
        if release is None or release['manifest'] != expected_release:
            raise ValueError('language host did not measure the published release')
        released_artifact.validate_saved(release, provenance['jvm_build']['jvm'])
        if any(provenance[key] != value for key, value in expected_candidate.items()):
            raise ValueError('language host changed the collector identity')
        instance = provenance['instance_identity']
        allocation = allocations.get(job['platform'])
        planned = {key: job.get(key) for key in ('instance_type', 'vcpus')}
        if (job['platform'] not in CURRENT_PLATFORMS or allocation is None or planned != allocation or
                instance['instanceType'] != allocation['instance_type'] or
                accepted[job['id']] != (host['export_sha256'], instance['instanceId'])):
            raise ValueError('language host differs from its accepted instance or export')
        part = by_partition[job['partition']]
        cohort = instances.setdefault((job['platform'], job['partition']), set())
        if instance['instanceId'] in cohort:
            raise ValueError('release replicas require independent hosts')
        cohort.add(instance['instanceId'])
        identity = [manifest['suite'], part['case'], part['language'], job['platform'], job['replica']]
        identities[job['id']] = identity
        encoded_case = json.dumps(cases[part['case']], sort_keys=True, ensure_ascii=True, allow_nan=False).encode()
        hosts.append({'logical_identity': identity, 'case_sha256': hashlib.sha256(encoded_case).hexdigest(),
                      'instance_id': instance['instanceId'], 'collector': collector['candidate_commit']})
    return {'hosts': hosts,
            'observations': [{**row, 'logical_identity': identities[row['job']]} for row in data['observations']],
            'comparisons': [{**row, 'logical_identity': identities[row['job']]} for row in data['comparisons']]}


def digest_plan(plan):
    return hashlib.sha256(json.dumps(plan, sort_keys=True, ensure_ascii=True, allow_nan=False).encode() + b'\n').hexdigest()


def select_language_receipts(path, plan, ledgers):
    path = Path(path).resolve()
    if path not in ledgers:
        ledgers[path] = {'receipts': load(path)['receipts'], 'used': set()}
    ledger = ledgers[path]
    expected = digest_plan(plan)
    selected = [index for index, receipt in enumerate(ledger['receipts'])
                if receipt['plan_sha256'] == expected]
    if ledger['used'].intersection(selected):
        raise ValueError('language receipt is assigned to multiple captures')
    ledger['used'].update(selected)
    return [ledger['receipts'][index] for index in selected]


def validate_language_receipt_coverage(ledgers):
    for ledger in ledgers.values():
        if len(ledger['used']) != len(ledger['receipts']):
            raise ValueError('language ledger contains receipts outside the imported captures')


def validate_baseline(baseline, release, collector):
    """Require a complete unit campaign of the collector, reduced from exactly its accepted unit attempts."""
    from build_capture_data import validate_reduction
    platforms = BASELINE / 'platforms.tsv'
    if digest(platforms) != collector['platform_manifest_sha256']:
        raise ValueError('platform manifest differs from the baseline collector')
    primary = Path(baseline['primary']) / 'accepted-units.tsv'
    confirmation = Path(baseline['confirmation']) / 'accepted-units.tsv' if baseline.get('confirmation') else None
    fields = unit_session.UNIT_RECEIPT_FIELDS
    # The confirmation ledger needs the receipt schema the primary ledger's acceptance requires.
    confirmations = unit_session.read_tsv(confirmation, fields, fields) if confirmation else []
    unit_session.validate_unit_campaign(BASELINE / 'rows.tsv', platforms, BASELINE / 'shards.tsv', primary,
                                        baseline['campaign_id'], confirmations=confirmations)
    # The reducer refuses a phase whose correctness stage rejected any attempt; checking again here also
    # refuses a rejection recorded after the reduction was made.
    for ledger in (primary, confirmation):
        if ledger:
            unit_session.check_semantic_rejections(ledger.parent)
    receipts = read_rows(primary) + confirmations
    for receipt in receipts:
        if (receipt['engine_tree'] != release['engine_tree'] or receipt['candidate_commit'] != collector['candidate_commit'] or
                receipt['candidate_archive_sha256'] != collector['archive_sha256']):
            raise ValueError('baseline receipt changed release source or collector')
    reduction = Path(baseline['reduction'])
    validate_reduction(reduction)
    # The reducer owns the proof that each attempt measured the release JAR; its summary names the release it proved.
    if load(reduction / 'summary.json').get('release_version') != release['version']:
        raise ValueError('baseline reduction did not prove its attempts measured the release JAR')
    validate_reduced_attempts(reduction, receipts)


def validate_reduced_attempts(reduction, receipts):
    """Require the reduction's host rows to come from exactly the accepted unit attempts, as their receipts name them."""
    fields = ('campaign_id', 'platform', 'unit_id', 'replica_id', 'unit_attempt_id', 'shard_id', 'instance_id',
              'host_epoch', 'candidate_commit', 'engine_tree', 'evidence_manifest_sha256')
    expected = {tuple(row[key] for key in fields) for row in receipts}
    actual = {tuple(row[key] for key in fields) for row in read_rows(reduction / 'host-rows.tsv')}
    if actual != expected:
        raise ValueError('baseline reduction changed the accepted unit attempts')


def build(plan_path):
    plan = load(plan_path)
    release = released_artifact.manifest(ROOT, plan['release_version'])
    collectors = {}
    combined = {'hosts': [], 'observations': [], 'comparisons': []}
    manifests, exports = [], []
    language_ledgers = {}
    for item in plan['language']:
        directory = Path(item['plan'])
        frozen = fleet.validate(directory)
        data = load(item['export'])
        if data['plan'] != frozen:
            raise ValueError('language export changed the frozen plan')
        manifest = load(directory / 'source/manifest.json')
        collector = load_collector(item, plan, release)
        if manifest['suite'] in collectors:
            raise ValueError('release capture repeats a language suite')
        collectors[manifest['suite']] = collector
        receipts = select_language_receipts(
            Path(item['export']).parent / 'accepted-language-sessions.json', frozen, language_ledgers)
        normalized = normalize_language(data, manifest, release, collector, receipts)
        for key in combined:
            combined[key].extend(normalized[key])
        manifests.append(manifest)
        exports.append(digest(item['export']))
    validate_language_receipt_coverage(language_ledgers)
    if sorted((manifest['suite'], len(manifest['cases'])) for manifest in manifests) != [('language-bulk', 238), ('language-lifecycle', 13)]:
        raise ValueError('release import requires all 238 bulk and 13 lifecycle cases')
    baseline = plan['baseline']
    collector = load_collector(baseline, plan, release)
    collectors['baseline'] = collector
    validate_baseline(baseline, release, collector)
    reduction = Path(baseline['reduction'])
    rows = language_rows(combined, manifests, platforms=CURRENT_PLATFORMS)
    lifecycle = next(manifest for manifest in manifests if manifest['suite'] == 'language-lifecycle')
    # The current baseline already includes all LIKE lifecycle shards.
    rows.extend(baseline_rows(reduction, lifecycle))
    report = {'schemaVersion': 2,
              'sources': {'currentLabel': 'Regulator ' + release['version'] + ' final results',
                          'currentCandidate': release['source_commit'], 'engineTree': release['engine_tree'],
                          'jdk': 'Temurin 25.0.4+7'},
              'languages': LANGUAGES, 'platforms': CURRENT_PLATFORMS, 'memoryModes': MEMORY_MODES, 'rows': rows,
              'methodology': ['Public measurements load the checksummed published release JAR. Collector source is recorded separately.',
                              'Comparisons use three independent hosts, with same-host ratios and all original samples retained. Baseline confirmations use a fourth host where required.',
                              'Ordinary R-family workers have 2 vCPUs and 16 GiB RAM. Measured JVMs retain an 8 GiB heap; JMH launchers use at most 256 MiB.',
                              'JVM language timings use five forks and ten one-second warmup and measurement iterations. Native RE2 uses five repetitions.',
                              'Observed host ranges are not confidence intervals. Incompatibility, reproduced timeouts, and timing variability remain explicit.'],
              'provenance': {'releasedArtifact': release, 'collectors': collectors,
                             'languageExportSha256': exports, 'logicalLanguageJobs': len(combined['hosts']),
                             'baselineReductionChecksumsSha256': digest(reduction / 'output-checksums.tsv'),
                             'baselineReducerSha256': digest(ROOT / 'tools/re2-benchmark/baseline/aggregate_results.py'),
                             'campaignPlanSha256': digest(plan_path)}}
    validate_report(report)
    return report
