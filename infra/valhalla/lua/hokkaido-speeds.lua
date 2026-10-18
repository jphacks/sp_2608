local default_ways_proc = ways_proc

local fallback_speeds = {
  motorway = 90,
  motorway_link = 40,
  trunk = 50,
  trunk_link = 30,
  primary = 45,
  primary_link = 30,
  secondary = 40,
  secondary_link = 25,
  tertiary = 35,
  tertiary_link = 25,
  unclassified = 30,
  residential = 25,
  living_street = 10,
  service = 15
}

local speed_tags = {
  "maxspeed", "maxspeed:forward", "maxspeed:backward",
  "maxspeed:advisory", "maxspeed:practical",
  "maxspeed:conditional", "maxspeed:forward:conditional", "maxspeed:backward:conditional"
}

function ways_proc(kv, nokeys)
  local has_speed = false
  for _, key in ipairs(speed_tags) do
    if kv[key] ~= nil then has_speed = true end
  end
  local speed = fallback_speeds[kv["highway"]]
  local is_ferry = kv["route"] == "ferry" or kv["route"] == "shuttle_train"
  local filter, result, polygon, roads = default_ways_proc(kv, nokeys)
  if filter == 0 and speed ~= nil and not has_speed and not is_ferry then
    -- 実勢速度だけを補完し、制限速度は作らない。
    result["average_speed"] = tostring(speed)
  end
  return filter, result, polygon, roads
end
