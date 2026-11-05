"""Estimate a campaign capture's numeric comparisons as mean costs with hierarchical bootstrap intervals."""
import math
from pathlib import Path
from statistics import mean
import sys
from uncertainty import uncertainty

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from language_data import DIRECTION_WARNING, RATIO_VARIATION_WARNING, host_ratio_warnings  # noqa: E402


def estimate(row):
    """Replace a compared row's host-median estimates with mean costs and their intervals.

    Each host must keep its mean and its independent processes' means; a mean is never inferred from a median,
    and no process is invented. Whether the hosts disagree is judged from the same mean costs; the precision and
    acceptance warnings stay.
    """
    result = row['result']
    identity = '|'.join(row[key] for key in ('id', 'language', 'platform', 'memoryMode'))
    inputs = []
    for host in result['hosts']:
        sides = {}
        for side in ('candidate', 'comparator'):
            value = host[side]
            missing = [field for field in ('meanNs', 'epochMeansNs') if field not in value]
            if missing:
                raise ValueError(f'{identity} {side} host {host["instanceId"]} lacks {" and ".join(missing)}; '
                                 'reduce its campaign with a reducer that keeps process means')
            if not math.isclose(mean(value['epochMeansNs']), value['meanNs'], rel_tol=1e-9):
                raise ValueError(f'{identity} {side} host {host["instanceId"]} mean is not its process means\' mean')
            sides[side] = {'processMeansNs': value['epochMeansNs']}
        inputs.append({'instanceId': host['instanceId'], **sides})
    candidate = mean(host['candidate']['meanNs'] for host in result['hosts'])
    comparator = mean(host['comparator']['meanNs'] for host in result['hosts'])
    ratios = [host['candidate']['meanNs'] / host['comparator']['meanNs'] for host in result['hosts']]
    result.update({
        'estimator': 'mean', 'candidateNs': candidate, 'comparatorNs': comparator, 'ratio': candidate / comparator,
        'minimumRatio': min(ratios), 'maximumRatio': max(ratios), 'deltaNs': candidate - comparator,
        'deltaNsPerByte': (candidate - comparator) / row['inputBytes'] if row.get('inputBytes') else None,
        'uncertainty': uncertainty(inputs, 'mean', identity),
        'warnings': host_ratio_warnings(ratios) + [warning for warning in result['warnings']
                                                   if warning not in (RATIO_VARIATION_WARNING, DIRECTION_WARNING)],
    })


def estimate_comparisons(report):
    for row in report['rows']:
        if row['result']['state'] == 'compared':
            estimate(row)
