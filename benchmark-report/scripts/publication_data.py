#!/usr/bin/env python3
"""Publish a complete release campaign's rows, except the hidden ones, as user-facing results."""

from __future__ import annotations

import argparse
import copy
import gzip
import json
from pathlib import Path
import re


POLICY = 'user-facing-release-v2'
RELEASE_VERSION = r'[0-9]+(?:\.[0-9]+)*'
# Hidden rows either do not represent user work or duplicate a public row; edit the rules below to show or hide rows.
HIDDEN_FAMILIES = {'engine-probe', 'optimizer-probe', 'synthetic-scaling'}
# The exceptions the hidden-family and optimized-LIKE filters keep visible. LIKE_DFA_PATTERNS matches likeDfaVariant
# in src/language-report.tsx.
PUBLIC_CASES = {'imported/rsc/no-exponential'}
LIKE_DFA_PATTERNS = {'ANY_ASCII', 'ANY_MULTIBYTE'}


def hidden(row):
    identifier = row['id']
    if row['family'] in HIDDEN_FAMILIES:
        # The ReDoS-shaped case stays public.
        return row['caseId'] not in PUBLIC_CASES
    if re.fullmatch(r'baseline/trino-like/[^/]+/optimized', identifier):
        # Trino's optional LIKE DFA changes nothing for the other patterns.
        return identifier.split('/')[2] not in LIKE_DFA_PATTERNS
    # DFA-disabled controls duplicate the ordinary single-use rows.
    return re.fullmatch(r'baseline/like-dfa-single-use/[^/]+', identifier) is not None


def load(path: Path):
    content = path.read_bytes()
    return json.loads(gzip.decompress(content) if path.suffix == '.gz' else content)


def publication_data(campaign):
    version = campaign['sources'].get('releaseVersion')
    if not isinstance(version, str) or not re.fullmatch(RELEASE_VERSION, version):
        raise ValueError('campaign sources.releaseVersion is not a release version')
    result = copy.deepcopy({**campaign, 'rows': [row for row in campaign['rows'] if not hidden(row)]})
    if 'releasedArtifact' in campaign.get('provenance', {}):
        result['sources']['currentLabel'] = f'Regulator {version} results'
    else:
        result['sources']['currentLabel'] = f'Regulator {version} preliminary results'
        result['publication'] = {
            'status': 'preliminary', 'sourcePolicy': 'candidate-build',
            'note': f"Preliminary results from candidate build {campaign['sources']['currentCandidate'][:12]}, "
                    f"measured before the {version} release.",
        }
    result['publicationScope'] = {
        'policy': POLICY,
        'completeCampaignRows': len(campaign['rows']),
        'publishedRows': len(result['rows']),
        'excludedCampaignRows': len(campaign['rows']) - len(result['rows']),
        'internalEvidence': f'docs/benchmarks/results-{version}/raw-archives.json',
    }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--campaign', type=Path, required=True,
                        help='complete verified campaign capture; retained outside the public report')
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    result = publication_data(load(arguments.campaign))
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, allow_nan=False, ensure_ascii=False, separators=(',', ':')) + '\n')
    print(f"Published {len(result['rows'])} user-facing rows; "
          f"hid {result['publicationScope']['excludedCampaignRows']} campaign rows: {arguments.output}")


if __name__ == '__main__':
    main()
