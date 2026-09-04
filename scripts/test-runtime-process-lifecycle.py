#!/usr/bin/env python3
"""Exercise gate cleanup with real Linux process groups, outside Minecraft."""
import os
from pathlib import Path
import re
import signal
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'scripts/run-dedicated-server-gate.sh').read_text()
NAMES = ('group_alive', 'wait_for_group_start', 'stop_group', 'process_tree_pids',
         'terminate_client_launch')
FUNCTIONS = '\n'.join(re.search(r'^' + name + r'\(\) \{\n.*?^\}\n', SOURCE,
                               re.MULTILINE | re.DOTALL).group() for name in NAMES)


class ProcessLifecycleTests(unittest.TestCase):
    def run_shell(self, scenario):
        # Keep this test in its own session: the old implementation kills its
        # supervisor's entire process group when cancellation beats setsid.
        process = subprocess.Popen(['bash', '-c', FUNCTIONS + '\n' + scenario],
                                   start_new_session=True, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        output = ''
        try:
            output, _ = process.communicate(timeout=12)
            self.assertEqual(process.returncode, 0, output)
            self.assertIn('SUPERVISOR_SURVIVED', output)
        except subprocess.TimeoutExpired as error:
            output = error.output or ''
            if isinstance(output, bytes):
                output = output.decode(errors='replace')
            self.fail('Gate cleanup timed out:\n' + output)
        finally:
            # Only these test-owned groups may survive a failed assertion.
            for pid in [process.pid] + [int(p) for p in re.findall(r'ISOLATED_GROUP=(\d+)', output)]:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait()

    def test_cancel_before_setsid_preserves_supervisor_and_sibling(self):
        self.run_shell('''
set -eu
sleep 30 & sibling=$!
(sleep 30) & child=$!
terminate_client_launch "$child" 2
kill -0 "$sibling"
! kill -0 "$child" 2>/dev/null
kill "$sibling"
wait "$sibling" 2>/dev/null || true
echo SUPERVISOR_SURVIVED
''')

    def test_wait_for_setsid_then_remove_separate_client_group(self):
        self.run_shell('''
set -eu
sleep 30 & sibling=$!
(sleep .3; exec setsid bash -c 'sleep 30 & wait') & child=$!
echo ISOLATED_GROUP=$child
wait_for_group_start "$child" 3
terminate_client_launch "$child" 2
! group_alive "$child"
kill -0 "$sibling"
kill "$sibling"
wait "$sibling" 2>/dev/null || true
echo SUPERVISOR_SURVIVED
''')


if __name__ == '__main__':
    unittest.main()
