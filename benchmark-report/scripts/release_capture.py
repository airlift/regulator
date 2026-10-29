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
import work_units


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


def normalize_language(data, manifest, expected_release, collector, receipts, units):
    """Import a plan's export. Its hosts must be exactly the accepted unit groups in receipts, and each must hold
    exactly its group's rows of the plan's units."""
    plan = data['plan']
    if 'selection' in plan:
        raise ValueError('release language capture requires the complete matrix')
    allocations = fleet.platform_allocations(manifest['suite'], plan['platforms'])
    if plan.get('schema_version') != 4 or plan.get('platform_allocations') != allocations:
        raise ValueError('release language capture changed its frozen worker allocations')
    expected_candidate = {'source_commit': collector['candidate_commit'], 'source_tree': collector['root_tree']}
    if any(data['candidate'][key] != value for key, value in expected_candidate.items()):
        raise ValueError('language capture changed the collector identity')
    for receipt in receipts:
        if (receipt['engine_tree'] != expected_release['engine_tree'] or
                receipt['candidate_commit'] != collector['candidate_commit'] or
                receipt['candidate_archive_sha256'] != collector['archive_sha256']):
            raise ValueError('accepted language unit changed the released artifact or collector')
    def attempt_group(row):
        return row['unit_attempt_id'], row['group_id']

    accepted = {attempt_group(receipt): receipt for receipt in receipts}
    if (len(accepted) != len(receipts) or len(data['hosts']) != len(receipts) or
            any(accepted.get(attempt_group(host['receipt'])) != host['receipt'] for host in data['hosts'])):
        raise ValueError('language capture has missing, duplicate, or changed hosts')
    owned = {attempt_group(host['receipt']): set() for host in data['hosts']}
    for row in data['observations']:
        owned.setdefault(attempt_group(row), set()).add(row['id'])
    expected = {attempt_group(host['receipt']): set(work_units.unit_group(
        units[host['receipt']['unit_id']], host['receipt']['group_id'])['owned']) for host in data['hosts']}
    if owned != expected:
        raise ValueError("language capture's observations differ from their accepted groups' rows")
    by_partition = {part['id']: part for part in plan['partitions']}
    cases = {case['id']: case for case in manifest['cases']}
    identities, hosts = {}, []
    for host in data['hosts']:
        receipt = host['receipt']
        exports = [(part['id'], part['export_sha256']) for part in host['partitions']]
        if fleet.exports_digest(exports) != receipt['observed_rows_sha256']:
            raise ValueError('language host differs from its accepted exports')
        allocation = allocations.get(receipt['platform'])
        if (receipt['platform'] not in CURRENT_PLATFORMS or allocation is None or
                receipt['instance_type'] != allocation['instance_type']):
            raise ValueError("language host differs from its shard's frozen allocation")
        for part in host['partitions']:
            provenance = part['provenance']
            release = provenance['jvm_build'].get('released_artifact')
            if release is None or release['manifest'] != expected_release:
                raise ValueError('language host did not measure the published release')
            released_artifact.validate_saved(release, provenance['jvm_build']['jvm'])
            if any(provenance[key] != value for key, value in expected_candidate.items()):
                raise ValueError('language host changed the collector identity')
            instance = provenance['instance_identity']
            if (instance['instanceId'], instance['instanceType']) != (receipt['instance_id'], receipt['instance_type']):
                raise ValueError('language host differs from its accepted instance')
            comparison = by_partition[part['id']]
            identity = [manifest['suite'], comparison['case'], comparison['language'], receipt['platform'],
                        int(receipt['replica_id']), receipt['unit_id']]
            identities[receipt['unit_attempt_id'], part['id']] = identity
            encoded_case = json.dumps(cases[comparison['case']], sort_keys=True, ensure_ascii=True,
                                      allow_nan=False).encode()
            hosts.append({'logical_identity': identity, 'case_sha256': hashlib.sha256(encoded_case).hexdigest(),
                          'instance_id': instance['instanceId'], 'collector': collector['candidate_commit']})

    def identified(row):
        return {**row, 'logical_identity': identities[row['unit_attempt_id'], row['partition']]}

    return {'hosts': hosts, 'observations': [identified(row) for row in data['observations']],
            'comparisons': [identified(row) for row in data['comparisons']]}


def validate_language_ledger(ledger, plans):
    """Require the accepted units of one complete language campaign of the imported plans, validated and by their
    directories, none of whose attempts was rejected for its semantics, and return its receipts."""
    fields = unit_session.UNIT_RECEIPT_FIELDS
    receipts = unit_session.read_tsv(ledger, fields, fields)
    campaigns = {receipt['campaign_id'] for receipt in receipts}
    if len(campaigns) != 1:
        raise ValueError(f'language ledger must hold one campaign: {ledger}')
    fleet.validate_unit_campaign(plans, receipts, campaigns.pop())
    unit_session.check_semantic_rejections(ledger.parent)
    return receipts


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
    """Require the reduction's host rows to come from exactly the accepted unit attempt groups that receipts name."""
    fields = ('campaign_id', 'platform', 'unit_id', 'replica_id', 'unit_attempt_id', 'group_id', 'shard_id',
              'instance_id', 'host_epoch', 'candidate_commit', 'engine_tree', 'evidence_manifest_sha256')
    expected = {tuple(row[key] for key in fields) for row in receipts}
    actual = {tuple(row[key] for key in fields) for row in read_rows(reduction / 'host-rows.tsv')}
    if actual != expected:
        raise ValueError('baseline reduction changed the accepted unit attempts')


def language_receipts(items, plans):
    """Each imported plan's accepted unit attempts: its shard's receipts in the ledger beside its export, once that
    ledger holds one complete campaign of the imported plans sharing it. plans holds each validated plan by its
    directory."""
    def ledger(item):
        return Path(item['export']).resolve().parent / 'accepted-units.tsv'

    ledgers = {}
    for item in items:
        directory = Path(item['plan'])
        ledgers.setdefault(ledger(item), {})[directory] = plans[directory]
    accepted = {path: validate_language_ledger(path, shared) for path, shared in ledgers.items()}
    return [[receipt for receipt in accepted[ledger(item)]
             if receipt['shard_id'] == plans[Path(item['plan'])]['shard_id']] for item in items]


def build(plan_path):
    plan = load(plan_path)
    release = released_artifact.manifest(ROOT, plan['release_version'])
    collectors = {}
    combined = {'hosts': [], 'observations': [], 'comparisons': []}
    manifests, exports = [], []
    plans = {Path(item['plan']): fleet.validate(Path(item['plan'])) for item in plan['language']}
    for item, receipts in zip(plan['language'], language_receipts(plan['language'], plans)):
        directory = Path(item['plan'])
        frozen = plans[directory]
        data = load(item['export'])
        if data['plan'] != frozen:
            raise ValueError('language export changed the frozen plan')
        manifest = load(directory / 'source/manifest.json')
        collector = load_collector(item, plan, release)
        if manifest['suite'] in collectors:
            raise ValueError('release capture repeats a language suite')
        collectors[manifest['suite']] = collector
        normalized = normalize_language(data, manifest, release, collector, receipts, work_units.load(directory))
        for key in combined:
            combined[key].extend(normalized[key])
        manifests.append(manifest)
        exports.append(digest(item['export']))
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
                          'jdk': 'Temurin 25.0.4+7', 'releaseVersion': release['version']},
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
