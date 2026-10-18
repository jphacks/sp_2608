import unittest

from recommendation.candidate_area import candidate_tiles, reference_samples


class CandidateAreaTest(unittest.TestCase):
    def test_reference_samples_follow_distance_not_vertex_density(self):
        sparse = [(43, 141), (43, 141.5)]
        dense = [(43, 141 + i / 1000) for i in range(501)]
        first, second = reference_samples(sparse), reference_samples(dense)
        self.assertEqual(len(first), len(second))
        for a, b in zip(first, second):
            self.assertAlmostEqual(a[0], b[0], places=6)
            self.assertAlmostEqual(a[1], b[1], places=6)

    def test_tiles_partition_the_full_bounds_without_dropping_farther_areas(self):
        bounds = (140.05, 42.05, 141.95, 43.95)
        tiles = candidate_tiles(bounds, [(42.1, 140.1), (43.9, 141.9)])
        self.assertEqual(len(tiles), len(set(tiles)))
        self.assertAlmostEqual((bounds[2] - bounds[0]) * (bounds[3] - bounds[1]),
                               sum((e - w) * (n - s) for w, s, e, n in tiles))
        self.assertTrue(all(bounds[0] <= w < e <= bounds[2] and bounds[1] <= s < n <= bounds[3]
                            for w, s, e, n in tiles))

    def test_early_tiles_cover_both_ends_of_a_long_trip(self):
        points = [(42.1, 140.5), (43.9, 140.5)]
        tiles = candidate_tiles((140, 42, 142, 44), points)
        first = tiles[:10]
        self.assertTrue(any(s < 42.3 for _, s, _, _ in first))
        self.assertTrue(any(n > 43.7 for _, _, _, n in first))
        self.assertTrue(all(w <= 140.5 <= e for w, _, e, _ in first))


if __name__ == '__main__':
    unittest.main()
