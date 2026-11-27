"""Caller boundaries; actionlint separately validates the complete YAML syntax."""
from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / 'workflows' / 'investigate-issue.yml'
PIN = '68cd96bce3684462baffa569a26295965af36339'
REFERENCE = ('BenCodez/VotingPlugin/.github/workflows/'
             'investigate-issue-reusable.yml@' + PIN)


def require_boundary(source):
    """Reject unsafe extensions to this deliberately small caller contract."""
    trigger = source.split('\non:\n', 1)[1].split('\npermissions:\n', 1)[0]
    if re.findall(r'^  ([A-Za-z_]+):', trigger, re.MULTILINE) != ['workflow_dispatch']:
        raise ValueError('manual-only trigger required')
    permissions = source.split('\npermissions:\n', 1)[1].split('\njobs:\n', 1)[0]
    if dict(re.findall(r'^  (\w+): (\w+)$', permissions, re.MULTILINE)) != {
            'actions': 'read', 'contents': 'read', 'issues': 'read'}:
        raise ValueError('read-only permissions required')
    if re.findall(r'^    uses: (\S+)$', source, re.MULTILINE) != [REFERENCE]:
        raise ValueError('reviewed immutable engine required')
    if re.findall(r'^      (\w+): \$\{\{ inputs\.(\w+) \}\}$', source, re.MULTILINE) != [
            ('issue_number', 'issue_number'), ('timeout_minutes', 'timeout_minutes')]:
        raise ValueError('manual inputs must reach the shared validator')
    if '      openai_project_id: ${{ vars.OPENAI_PROJECT_ID }}\n' not in source:
        raise ValueError('explicit project required')
    secret_block = source.split('    secrets:\n', 1)
    if len(secret_block) != 2 or secret_block[1] != '      OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}\n':
        raise ValueError('explicit secret mapping required')
    if any(value in source for value in ('post_comment:', 'secrets: inherit', '    steps:', '    runs-on:')):
        raise ValueError('caller must not implement an engine or posting path')
    for condition in ("github.repository == 'BenCodez/SimpleAPI'",
                      "github.ref == 'refs/heads/main'",
                      "github.event_name == 'workflow_dispatch'"):
        if condition not in source:
            raise ValueError('trusted caller identity required')


class EntrypointTests(unittest.TestCase):
    def setUp(self):
        self.source = WORKFLOW.read_text()

    def test_actual_caller_boundary(self):
        require_boundary(self.source)
        self.assertRegex(self.source, r"timeout_minutes:\n(?:.*\n)*?        default: '15'")
        self.assertNotIn('post_comment', self.source)

    def test_automatic_event_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'manual-only'):
            require_boundary(self.source.replace('on:\n', 'on:\n  issues:\n    types: [opened]\n'))

    def test_mutable_or_wrong_engine_is_rejected(self):
        for replacement in ('main', '0' * 40):
            with self.subTest(replacement=replacement), self.assertRaisesRegex(ValueError, 'immutable'):
                require_boundary(self.source.replace(PIN, replacement))

    def test_write_permissions_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'read-only'):
            require_boundary(self.source.replace('  issues: read', '  issues: write'))

    def test_unrelated_repository_or_branch_is_rejected(self):
        for old, new in (('BenCodez/SimpleAPI', 'example/unrelated'), ('refs/heads/main', 'refs/heads/topic')):
            with self.subTest(old=old), self.assertRaisesRegex(ValueError, 'identity'):
                require_boundary(self.source.replace(old, new))

    def test_secret_inheritance_or_renaming_is_rejected(self):
        for source in (self.source.replace('    secrets:\n', '    secrets: inherit\n'),
                       self.source.replace('secrets.OPENAI_API_KEY', 'secrets.OTHER_KEY')):
            with self.subTest(source=source[-100:]), self.assertRaises(ValueError):
                require_boundary(source)

    def test_posting_or_inline_engine_is_rejected(self):
        for addition in ('      post_comment: true\n', '    steps: []\n'):
            with self.subTest(addition=addition), self.assertRaises(ValueError):
                require_boundary(self.source.replace('    secrets:\n', addition + '    secrets:\n'))

    def test_missing_project_and_swapped_input_are_rejected(self):
        for source in (self.source.replace('vars.OPENAI_PROJECT_ID', 'vars.OTHER_PROJECT'),
                       self.source.replace('inputs.issue_number', 'inputs.timeout_minutes')):
            with self.subTest(source=source[-260:]), self.assertRaises(ValueError):
                require_boundary(source)


if __name__ == '__main__':
    unittest.main()
