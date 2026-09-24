"""쉬는 시간 안에 다시 켜진 수집기는 남은 시간만 기다린다."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from main import resume_wait
from request_budget import RequestBudget


class CycleResumeTest(unittest.TestCase):
    def test_restart_inside_cooldown_waits_only_the_rest(self):
        """10:21 에 수집한 혼잡도(6시간 주기)가 15:04 재시작 뒤 16:21 에 다시 받게 된다."""
        clock = [0.0]
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "budget.db")
            budget = RequestBudget(path, "key", 900, clock=lambda: clock[0])
            self.assertTrue(budget.claim_cycle("city_realtime", 21600, 121))

            clock[0] = 4 * 3600 + 43 * 60  # 재시작: 아직 쉬는 시간
            restarted = RequestBudget(path, "key", 900, clock=lambda: clock[0])
            self.assertFalse(restarted.claim_cycle("city_realtime", 21600, 121))

            wait = resume_wait(restarted.next_cycle_at("city_realtime"), clock[0], 21600)
            self.assertEqual(wait, 21600 - clock[0] + 1)  # 한 주기(6시간)가 아니라 남은 1시간 17분

            clock[0] += wait
            self.assertTrue(restarted.claim_cycle("city_realtime", 21600, 121))

    def test_never_waits_longer_than_the_interval(self):
        # 수집이 금방 끝나면 허용 시각이 한 주기 가까이 남는다. 주기보다 길게 기다리지 않는다.
        self.assertEqual(resume_wait(21600.0, 0.2, 21600), 21600)
        # 기록이 없거나 이미 지난 허용 시각이면 원래대로 한 주기를 기다린다.
        self.assertEqual(resume_wait(None, 100.0, 900), 900)
        self.assertEqual(resume_wait(50.0, 100.0, 900), 900)

    def test_normal_cycle_keeps_start_to_start_cadence(self):
        # 15분 주기 수집이 40초 걸렸으면 다음 시작은 처음 시작에서 15분 뒤(+1초)다.
        self.assertEqual(resume_wait(900.0, 40.0, 900), 861.0)


if __name__ == "__main__":
    unittest.main()
