"""Parser checks prevent changed Android diagnostics becoming invented metrics."""
import unittest

from measure_performance import frame_times_ms, pss_kib, startup_times, summary


class MeasurementParserTest(unittest.TestCase):
    def test_successful_launch_is_required(self):
        self.assertEqual(184, startup_times('Status: ok\nTotalTime: 184\nWaitTime: 190\n')['TotalTime'])
        with self.assertRaises(ValueError):
            startup_times('Status: timeout\nWaitTime: 10000\n')
        with self.assertRaises(ValueError):
            startup_times('Warning: Activity not started, intent delivered\nStatus: ok\n')

    def test_pss_formats_and_missing_result(self):
        self.assertEqual(123456, pss_kib(' TOTAL PSS: 123,456 TOTAL RSS: 450000'))
        self.assertEqual(32100, pss_kib('  TOTAL   32100   12000  1000'))
        with self.assertRaises(ValueError):
            pss_kib('No process found for: com.os73.dosos73')

    def test_frame_header_order_and_invalid_frames(self):
        data = ('Flags,FrameCompleted,IntendedVsync\n'
                '0,17000000,1000000\n'
                '1,27000000,1000000\n'
                '0,0,1000000\n'
                '0,9223372036854775807,1000000\n'
                '0,broken,1000000\n')
        self.assertEqual([16.0], frame_times_ms(data))
        self.assertEqual([], frame_times_ms('No process found'))

    def test_nearest_rank_p95_and_missing_samples(self):
        self.assertEqual(10, summary(list(range(1, 11)))['p95'])
        self.assertEqual(5.5, summary(list(range(1, 11)))['p50'])
        self.assertIsNone(summary([])['p95'])


if __name__ == '__main__':
    unittest.main()
