import unittest

from registry_geometry import describe_geometry, publication_issues


def road(ident, a, b, meters=100):
    return dict(id=ident, lat1=a[0], lon1=a[1], lat2=b[0], lon2=b[1], length=meters)


class RegistryGeometryTest(unittest.TestCase):
    def test_connected_chain_keeps_every_segment_in_order(self):
        rows = [road(2, (43, 141.001), (43, 141.002)), road(1, (43, 141), (43, 141.001))]
        result = describe_geometry(rows)
        self.assertEqual(1, result['component_count'])
        self.assertEqual([1, 2], result['parts'][0]['road_ids'])
        self.assertEqual([[141, 43], [141.001, 43], [141.002, 43]], result['parts'][0]['coordinates'])

    def test_gap_is_not_drawn_as_a_connected_road(self):
        result = describe_geometry([road(1, (43, 141), (43, 141.001)),
                                    road(2, (43, 141.002), (43, 141.003))])
        self.assertEqual(2, result['component_count'])
        self.assertEqual(2, len(result['parts']))
        self.assertTrue(publication_issues({}, result))
        self.assertEqual([], publication_issues({}, result, official=True))

    def test_branches_and_cycles_do_not_drop_or_repeat_edges(self):
        result = describe_geometry([road(1, (0, 0), (0, 1)), road(2, (0, 1), (1, 1)),
                                    road(3, (1, 1), (0, 0)), road(4, (0, 1), (0, 2))])
        ids = [ident for part in result['parts'] for ident in part['road_ids']]
        self.assertEqual([1, 2, 3, 4], sorted(ids))
        self.assertEqual(1, result['branch_count'])

    def test_missing_or_short_geometry_and_editorial_holds_block_publication(self):
        self.assertTrue(publication_issues({}, describe_geometry([])))
        result = describe_geometry([road(1, (43, 141), (43, 141.001))])
        self.assertTrue(publication_issues({'extent': {'minimum_meters': 1000}}, result))
        self.assertTrue(publication_issues({'max_meters': 50}, result))
        self.assertEqual(['区間の確認待ち'], publication_issues({'registration_hold_reasons': ['区間の確認待ち']}, result))
