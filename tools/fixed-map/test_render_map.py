"""地図の範囲指定、出力の保護、標本ファイルの整合性を検査する。"""

import contextlib
import io
import struct
import tempfile
from pathlib import Path
import unittest

from map_image import clip_segment, read_samples
from render_map import ROOT, arguments, prepare_output


class RenderMapTests(unittest.TestCase):
    def test_default_maps_cover_world_and_northwest(self):
        _, jobs = arguments([])
        self.assertEqual([job["step"] for job in jobs], [16, 8])
        self.assertEqual(jobs[0]["bounds"], (-16384, -16384, 16384, 16384))
        self.assertEqual(jobs[1]["bounds"], (-16384, -16384, 7168, 1024))

    def test_named_preset_and_interval(self):
        _, jobs = arguments(["--preset", "northwest", "--step", "16"])
        self.assertEqual(len(jobs), 1)
        self.assertEqual(jobs[0]["step"], 16)

    def test_custom_bounds_are_not_rounded(self):
        _, jobs = arguments(["--bounds", "-101", "33", "-65", "69", "--step", "3"])
        self.assertEqual(jobs[0]["bounds"], (-101, 33, -65, 69))
        self.assertEqual(jobs[0]["step"], 3)

    def test_invalid_ranges_fail_before_running_gradle(self):
        cases = [
            ["--step", "0"], ["--step", "-1"],
            ["--bounds", "10", "0", "0", "20"],
            ["--bounds", "0", "0", "16385", "20"],
            ["--bounds", "0", "0", "31", "32", "--step", "16"],
            ["--bounds", "0", "0", "16", "32", "--step", "16"],
            ["--preset", "world", "--bounds", "0", "0", "32", "32"],
        ]
        for case in cases:
            with self.subTest(case=case), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    arguments(case)
                self.assertEqual(error.exception.code, 2)

    def test_existing_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            saved = output / "keep.txt"
            saved.write_text("保存する", encoding="utf-8")
            with self.assertRaises(ValueError):
                prepare_output(output)
            self.assertEqual(saved.read_text(encoding="utf-8"), "保存する")

    def test_output_in_repository_must_be_ignored(self):
        output = ROOT / "map-render-unignored-output"
        self.assertFalse(output.exists())
        with self.assertRaises(ValueError):
            prepare_output(output)
        self.assertFalse(output.exists())

    def test_sampling_record_and_header_match_request(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            job = {"name": "custom-3", "bounds": (-101, 33, -95, 39), "step": 3}
            header = struct.pack(">6i", 0x4d415031, -101, 33, 3, 2, 2)
            record = struct.pack(">hhHHB", -112, 63, 0, 1, 17)
            (output / "custom-3.bin").write_bytes(header + record * 4)
            (output / "custom-3-palette.txt").write_text("minecraft:clay\nashen_frontier_core:sea_ice\n", encoding="utf-8")
            data, palette = read_samples(output, job)
            self.assertEqual(data.shape, (2, 2))
            self.assertEqual(int(data[0, 0]["ground"]), -112)
            self.assertEqual(int(data[0, 0]["flags"]), 17)
            self.assertEqual(palette[1], "ashen_frontier_core:sea_ice")
            (output / "custom-3.bin").write_bytes(header + record * 3)
            with self.assertRaises(ValueError):
                read_samples(output, job)

    def test_mountain_axis_is_clipped_to_requested_map(self):
        self.assertEqual(clip_segment((-10, 5), (20, 5), (0, 0, 10, 10)), ((0., 5.), (10., 5.)))
        self.assertIsNone(clip_segment((-10, -2), (20, -2), (0, 0, 10, 10)))
        self.assertIsNone(clip_segment((-10, 0), (-10, 20), (0, 0, 10, 10)))


if __name__ == "__main__":
    unittest.main()
