import unittest

from verified_task_loop import ActionClass, Decision, Outcome, StepResult, decide


def step(**updates):
    args = dict(task_id="t1", task_revision=1, current_revision=1,
                idempotency_key="k1", action_class=ActionClass.READ_ONLY,
                outcome=Outcome.NOT_STARTED)
    args.update(updates)
    return StepResult(**args)


class VerifiedTaskLoopTests(unittest.TestCase):
    def test_readonly_can_retry_before_start(self):
        self.assertEqual(decide(step()), Decision.RETRY)

    def test_unknown_requires_verification_not_replay(self):
        self.assertEqual(decide(step(outcome=Outcome.UNKNOWN)), Decision.VERIFY)

    def test_irreversible_unknown_never_retries(self):
        self.assertEqual(decide(step(action_class=ActionClass.IRREVERSIBLE,
                                     approval_granted=True, outcome=Outcome.UNKNOWN)),
                         Decision.VERIFY)

    def test_writes_need_approval(self):
        self.assertEqual(decide(step(action_class=ActionClass.REVERSIBLE)), Decision.BLOCK)

    def test_cancelled_blocks(self):
        self.assertEqual(decide(step(cancelled=True)), Decision.BLOCK)

    def test_stale_revision_blocks(self):
        self.assertEqual(decide(step(current_revision=2)), Decision.BLOCK)

    def test_verified_success_with_evidence_done(self):
        self.assertEqual(decide(step(outcome=Outcome.SUCCEEDED,
                                     postcondition_checked=True,
                                     postcondition_passed=True,
                                     evidence_ref="sha256:abcdef")), Decision.DONE)

    def test_unsubstantiated_success_not_done(self):
        self.assertEqual(decide(step(outcome=Outcome.SUCCEEDED)), Decision.VERIFY)

    def test_no_evidence_not_done(self):
        self.assertEqual(decide(step(outcome=Outcome.SUCCEEDED,
                                     postcondition_checked=True,
                                     postcondition_passed=True)), Decision.BLOCK)

    def test_failed_verification_not_done(self):
        self.assertEqual(decide(step(outcome=Outcome.SUCCEEDED,
                                     postcondition_checked=True,
                                     postcondition_passed=False)), Decision.BLOCK)

    def test_invalid_verification_rejected(self):
        with self.assertRaises(ValueError):
            step(postcondition_passed=True)


if __name__ == "__main__":
    unittest.main()
