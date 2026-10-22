import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import opencode_start as launcher


class StartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = Path(self.temp.name)
        for name in ('password', 'pairing-token'):
            p = self.config / name
            p.write_text('private-test-secret-123456789')
            p.chmod(0o600)
        self.patches = [patch.object(launcher, 'CONFIG', self.config),
                        patch.object(launcher, 'sanitized_status', return_value={'healthy': True}),
                        patch.object(launcher, 'gateway_status', return_value=True),
                        patch.object(launcher, 'occupied', return_value=False),
                        patch.object(launcher, 'spawn'),
                        patch.object(launcher, 'wait_ready'),
                        patch.object(launcher.shutil, 'which', return_value='/termux/bin/opencode')]
        self.config_mock, self.health, self.gateway, self.port, self.spawn, self.wait, self.binary = [
            p.start() for p in self.patches]
        for p in self.patches:
            self.addCleanup(p.stop)

    def test_healthy_processes_are_reused_without_spawning_or_model_requests(self):
        launcher.start(True)
        self.spawn.assert_not_called()
        self.wait.assert_not_called()

    def test_launches_both_with_private_environment_and_explicit_task_flag(self):
        self.health.return_value = {'healthy': False}
        self.gateway.return_value = False
        launcher.start(True)
        self.assertEqual(self.spawn.call_count, 2)
        command, env, log, cwd = self.spawn.call_args_list[0].args
        self.assertEqual(command, ['/termux/bin/opencode', 'serve', '--hostname', '127.0.0.1', '--port', '4096'])
        self.assertNotIn('private-test-secret-123456789', ' '.join(command))
        self.assertEqual(env['OPENCODE_SERVER_PASSWORD'], 'private-test-secret-123456789')
        self.assertTrue(env['OPENCODE_CONFIG'].endswith('opencode_mcp_profile.json'))
        self.assertEqual(self.spawn.call_args_list[1].args[0][-1], '--enable-tasks')

    def test_default_start_does_not_enable_task_dispatch(self):
        self.gateway.return_value = False
        launcher.start()
        self.assertNotIn('--enable-tasks', self.spawn.call_args.args[0])

    def test_conflicting_port_is_not_killed_or_replaced(self):
        self.health.return_value = {'healthy': False}
        self.port.return_value = True
        with self.assertRaisesRegex(launcher.StartupError, '4096'):
            launcher.start()
        self.spawn.assert_not_called()

    def test_failed_gateway_cleans_up_only_new_process(self):
        self.gateway.return_value = False
        child = Mock()
        child.poll.return_value = None
        self.spawn.return_value = child
        self.wait.side_effect = launcher.StartupError('Gateway readiness timeout')
        with self.assertRaises(launcher.StartupError):
            launcher.start()
        self.assertEqual(self.spawn.call_count, 1)  # Existing OpenCode was untouched.
        child.terminate.assert_called_once()

    def test_unsafe_secret_permissions_prevent_any_start(self):
        (self.config / 'password').chmod(0o644)
        with self.assertRaises(RuntimeError):
            launcher.start()
        self.spawn.assert_not_called()

    def test_startup_lock_prevents_duplicate_start(self):
        with patch.object(launcher.fcntl, 'flock', side_effect=BlockingIOError):
            with self.assertRaisesRegex(launcher.StartupError, 'already in progress'):
                launcher.start()
        self.spawn.assert_not_called()


if __name__ == '__main__':
    unittest.main()
