import copy
import unittest

import publication_data


def rows(identifier, family, languages=('re2', 'java', 'trino', 'rust'), population='bulk-text'):
    case = identifier.split('/', 1)[1].removesuffix('/execute')
    return [{'id': identifier, 'caseId': case, 'name': case, 'operation': 'execute', 'language': language,
             'platform': platform, 'memoryMode': mode, 'model': 'count', 'population': population,
             'family': family, 'workload': {'pattern': case, 'inputs': [{'bytes': 1}]},
             'mapping': {'status': 'translated', 'patternBytes': len(case)}, 'result': {'state': 'compared'}}
            for language in languages for platform in ('r9g', 'r8i') for mode in ('native', 'safe')]


class TestPublicationData(unittest.TestCase):
    def setUp(self):
        public = [
            *rows('language-bulk/sherlock/execute', 'classic-text'),
            # The ReDoS-shaped synthetic case stays public.
            *rows('language-bulk/imported/rsc/no-exponential/execute', 'synthetic-scaling'),
            *rows('baseline/trino-specialized/extract', 'extract', ('trino',), 'specialized-patterns'),
            *rows('baseline/trino-like/ANY_ASCII/optimized', 'LIKE', ('like',), 'like'),
            *rows('baseline/trino-like/ANY_MULTIBYTE/optimized', 'LIKE', ('like',), 'like'),
            *rows('baseline/like-single-use/ANY_ASCII', 'LIKE', ('like',), 'like'),
            *rows('baseline/like-dfa-single-use/ANY_ASCII/optimized', 'LIKE', ('like',), 'like'),
        ]
        hidden = [
            *rows('language-bulk/opt/literal-prefix/execute', 'optimizer-probe'),
            *rows('language-bulk/imported/rsc/quadratic/execute', 'synthetic-scaling'),
            *rows('language-bulk/engine/onepass/execute', 'engine-probe', ('re2',)),
            *rows('baseline/trino-like/CONTAINS_LATE/optimized', 'LIKE', ('like',), 'like'),
            *rows('baseline/like-dfa-single-use/ANY_ASCII', 'LIKE', ('like',), 'like'),
        ]
        self.public = public
        self.campaign = {
            'sources': {'currentLabel': 'Complete campaign', 'currentCandidate': '0123456789abcdef' * 2 + 'a' * 8,
                        'releaseVersion': '2.0'},
            'provenance': {'collectors': {}, 'releasedArtifact': {'version': '2.0'}},
            # Hidden rows interleave with public ones.
            'rows': [row for pair in zip(public, hidden) for row in pair] + public[len(hidden):],
        }

    def test_publication_keeps_every_row_except_hidden_ones(self):
        campaign = copy.deepcopy(self.campaign)
        result = publication_data.publication_data(campaign)
        self.assertEqual(self.public, result['rows'])
        self.assertEqual(campaign, self.campaign)
        self.assertEqual({
            'policy': 'user-facing-release-v2', 'completeCampaignRows': len(self.campaign['rows']),
            'publishedRows': len(self.public), 'excludedCampaignRows': len(self.campaign['rows']) - len(self.public),
            'internalEvidence': 'docs/benchmarks/results-2.0/raw-archives.json',
        }, result['publicationScope'])

    def test_publication_requires_a_released_artifact_and_labels_final_results(self):
        final = publication_data.publication_data(self.campaign)
        self.assertEqual('Regulator 2.0 results', final['sources']['currentLabel'])
        self.assertNotIn('publication', final)
        unreleased = copy.deepcopy(self.campaign)
        del unreleased['provenance']['releasedArtifact']
        with self.assertRaisesRegex(ValueError, 'released-artifact provenance'):
            publication_data.publication_data(unreleased)
        for version in (None, '', '2.0-rc1'):
            changed = copy.deepcopy(self.campaign)
            changed['sources']['releaseVersion'] = version
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'release version'):
                publication_data.publication_data(changed)


if __name__ == '__main__':
    unittest.main()
