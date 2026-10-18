import ctypes
import ctypes.util
import unittest

from prepare import routing_lua


class SpeedProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lua = None
        for name in ('luajit-5.1', 'lua5.3', 'lua5.4', 'lua'):
            library = ctypes.util.find_library(name)
            if library:
                try:
                    cls.lua = ctypes.CDLL(library)
                    break
                except OSError:
                    continue
        if cls.lua is None:
            raise unittest.SkipTest('Lua library not installed; run this test in the Valhalla image.')
        cls.lua.luaL_newstate.restype = ctypes.c_void_p
        cls.lua.luaL_openlibs.argtypes = [ctypes.c_void_p]
        cls.lua.luaL_loadstring.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        if hasattr(cls.lua, 'lua_pcall'):
            cls.lua.lua_pcall.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int]
        else:
            cls.lua.lua_pcallk.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                         ctypes.c_longlong, ctypes.c_void_p]
        cls.lua.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        cls.lua.lua_tolstring.restype = ctypes.c_char_p
        cls.lua.lua_close.argtypes = [ctypes.c_void_p]

    def check(self, code):
        state = self.lua.luaL_newstate()
        try:
            self.lua.luaL_openlibs(state)
            status = self.lua.luaL_loadstring(state, (routing_lua() + '\n' + code).encode())
            if status == 0:
                status = (self.lua.lua_pcall(state, 0, 0, 0) if hasattr(self.lua, 'lua_pcall') else
                          self.lua.lua_pcallk(state, 0, 0, 0, 0, None))
            self.assertEqual(0, status, self.lua.lua_tolstring(state, -1, None))
        finally:
            self.lua.lua_close(state)

    def test_missing_speed_uses_road_class_without_inventing_limit(self):
        self.check('''
          for road, speed in pairs({trunk=50, primary=45, secondary=40,
                                   motorway=90, residential=25, service=15}) do
            local filter, tags = ways_proc({highway=road}, 1)
            assert(filter == 0)
            assert(tonumber(tags.average_speed) == speed, road)
            assert(tags.max_speed == nil, road)
          end
        ''')

    def test_existing_speeds_and_conditional_tags_are_not_overridden(self):
        self.check('''
          for _, key in ipairs({"maxspeed", "maxspeed:forward", "maxspeed:backward",
                                "maxspeed:advisory", "maxspeed:practical", "maxspeed:conditional"}) do
            local _, tags = ways_proc({highway="trunk", [key]="40"}, 2)
            if key == "maxspeed:practical" then
              assert(tonumber(tags.average_speed) == 40)
            else
              assert(tags.average_speed == nil, key)
            end
          end
          local _, tags = ways_proc({highway="trunk", maxspeed="40"}, 2)
          assert(tonumber(tags.max_speed) == 40)
        ''')

    def test_tracks_and_ferries_keep_upstream_processing(self):
        self.check('''
          local _, track = ways_proc({highway="track"}, 1)
          assert(track.average_speed == nil)
          local _, ferry = ways_proc({route="ferry", highway="trunk"}, 2)
          assert(ferry.average_speed == nil)
          local filter = ways_proc({}, 0)
          assert(filter == 1)
        ''')


if __name__ == '__main__':
    unittest.main()
