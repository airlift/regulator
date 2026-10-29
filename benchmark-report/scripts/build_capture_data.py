"""Build report data from complete verified captures, never from prior HTML."""

import argparse
import json
from pathlib import Path

from baseline_data import read_rows
from language_data import digest
from publication_data import publication_data
from report_data import validate_report


def load(path):
    with Path(path).open() as source:
        return json.load(source)


def write_report(report, path):
    validate_report(report)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(
        report, allow_nan=False, ensure_ascii=False, separators=(',', ':')) + '\n')


def validate_released_campaign_paths(arguments):
    inputs = {'--released-campaign': arguments.released_campaign}
    if arguments.released_campaign.exists():
        plan = load(arguments.released_campaign)
        if plan.get('candidate_provenance'):
            inputs['campaign candidate provenance'] = Path(plan['candidate_provenance'])
        for index, item in enumerate(plan.get('language', [])):
            for field in ('plan', 'export', 'candidate_provenance'):
                if item.get(field):
                    inputs[f'language[{index}].{field}'] = Path(item[field])
            if item.get('export'):
                # The campaign's result root holds the export beside its ledgers and accepted unit attempts;
                # a linked export names it by its target.
                inputs[f'language[{index}] result root'] = Path(item['export']).resolve().parent
        baseline = plan.get('baseline', {})
        for field in ('primary', 'confirmation', 'reduction', 'candidate_provenance'):
            if baseline.get(field):
                inputs[f'baseline.{field}'] = Path(baseline[field])
    outputs = {
        '--output': arguments.output,
        '--complete-output': arguments.complete_output,
    }

    def aliases(first, second):
        first_resolved = first.resolve()
        second_resolved = second.resolve()
        if first_resolved == second_resolved:
            return True
        if second.is_dir() and first_resolved.is_relative_to(second_resolved):
            return True
        return first.exists() and second.exists() and first.samefile(second)

    items = list(outputs.items())
    for index, (name, path) in enumerate(items):
        for other_name, other_path in items[index + 1:] + list(inputs.items()):
            if aliases(path, other_path):
                raise ValueError(f'{name} must differ from {other_name}')


def validate_reduction(directory):
    for row in read_rows(directory / 'output-checksums.tsv'):
        path = directory / row['file']
        if path.stat().st_size != int(row['size_bytes']) or digest(path) != row['sha256']:
            raise ValueError(f'baseline reduction changed: {path}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--released-campaign', type=Path, required=True,
                        help='complete release campaign paths and pinned artifact version')
    parser.add_argument('--complete-output', type=Path, required=True,
                        help='complete campaign capture for the private evidence archive')
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    try:
        validate_released_campaign_paths(arguments)
    except ValueError as error:
        parser.error(str(error))
    from release_capture import build as build_release
    complete = build_release(arguments.released_campaign)
    write_report(complete, arguments.complete_output)
    report = publication_data(complete)
    write_report(report, arguments.output)
    print(f"Built {len(report['rows'])} report rows from verified captures: {arguments.output}")


if __name__ == '__main__':
    main()
