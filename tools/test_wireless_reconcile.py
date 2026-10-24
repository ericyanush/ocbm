"""Exercise the supervisor's missing-advertiser recovery without accessing adapter hardware."""
import pathlib
import subprocess
import unittest


SOURCE = pathlib.Path(__file__).with_name("session_supervisor.sh").read_text()
START = SOURCE.index("wireless_reconcile() {")
FUNCTION = SOURCE[START:SOURCE.index("\n}\n", START) + 3]
RUNNING_START = SOURCE.index("wireless_running() {")
RUNNING_FUNCTION = SOURCE[RUNNING_START:SOURCE.index("\n}\n", RUNNING_START) + 3]
MOCKS = """
p=1; tick=100; running=0; inhibited=0; enabled=1; aa_owner=0
hot=0; wired=0; wireless_owner=0; android=0; aa=0; wireless_rebring_at=''
radio_off() { [ "$inhibited" = 1 ]; }
wireless_enabled() { [ "$enabled" = 1 ]; }
aa_owns_session() { [ "$aa_owner" = 1 ]; }
hot_handover_enabled() { [ "$hot" = 1 ]; }
phone_on_bus() { [ "$wired" = 1 ]; }
wireless_owns_session() { [ "$wireless_owner" = 1 ]; }
android_phone_on_bus() { [ "$android" = 1 ]; }
aa_enabled() { [ "$aa" = 1 ]; }
wireless_running() { [ "$running" = 1 ]; }
now() { echo "$tick"; }
wireless_up() { echo LAUNCH; }
"""


class WirelessReconcileTest(unittest.TestCase):
    def test_pending_wrapper_is_detected_without_command_line_matching(self):
        script = RUNNING_FUNCTION + """
pgrep() { return 1; }
sleep 30 &
wireless_launch_pid=$!
trap 'kill "$wireless_launch_pid" 2>/dev/null || true' EXIT
wireless_running || exit 10
kill "$wireless_launch_pid"
wait "$wireless_launch_pid" 2>/dev/null || true
if wireless_running; then exit 11; fi
"""
        subprocess.run(["sh"], input=script, text=True, capture_output=True, check=True)

    def run_scenario(self, commands):
        result = subprocess.run(["sh"], input=MOCKS + FUNCTION + commands,
                                text=True, capture_output=True, check=True)
        return result.stdout.splitlines().count("LAUNCH")

    def test_missing_advertiser_restarts_after_grace_and_rate_limits_retries(self):
        self.assertEqual(2, self.run_scenario("""
wireless_reconcile
tick=104; wireless_reconcile
tick=105; wireless_reconcile
tick=106; wireless_reconcile
tick=119; wireless_reconcile
tick=120; wireless_reconcile
"""))

    def test_live_daemon_or_launch_wrapper_is_never_restarted(self):
        self.assertEqual(0, self.run_scenario("""
running=1; wireless_reconcile
tick=200; wireless_reconcile
"""))

    def test_recovery_honors_host_radio_and_transport_ownership(self):
        for gate in ["p=0", "inhibited=1", "enabled=0", "aa_owner=1",
                     "wired=1", "android=1; aa=1", "wireless_rebring_at=150"]:
            with self.subTest(gate=gate):
                self.assertEqual(0, self.run_scenario(f"""
{gate}
wireless_reconcile
tick=200; wireless_reconcile
"""))

    def test_host_loss_resets_grace_before_next_presence(self):
        self.assertEqual(1, self.run_scenario("""
wireless_reconcile
p=0; tick=104; wireless_reconcile
p=1; tick=200; wireless_reconcile
tick=204; wireless_reconcile
tick=205; wireless_reconcile
"""))


if __name__ == "__main__":
    unittest.main()
