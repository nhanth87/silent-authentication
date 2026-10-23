-- Wireshark post-dissector for the demo screencast: what is safe to show on screen.
--   camara.path  the request path without its query string (state, nonce, PKCE stay off screen)
--   camara.body  JSON members and form fields, with every secret value replaced by •••
--   camara.msg   what the packet is: HTTP method / status, Diameter command, SCTP association setup
-- Loaded from the screencast's private Wireshark profile (plugins/); never part of a product.

local proto = Proto("camara", "CAMARA demo view (secrets masked)")
local f_path = ProtoField.string("camara.path", "Path")
local f_body = ProtoField.string("camara.body", "Body (secrets masked)")
local f_msg = ProtoField.string("camara.msg", "Message")
proto.fields = { f_path, f_body, f_msg }

local uri_f = Field.new("http.request.uri")
local member_f = Field.new("json.member_with_value")
local form_key_f = Field.new("urlencoded-form.key")
local form_value_f = Field.new("urlencoded-form.value")
local method_f = Field.new("http.request.method")
local status_f = Field.new("http.response.code")
local phrase_f = Field.new("http.response.phrase")
local dia_cmd_f = Field.new("diameter.cmd.code")
local dia_req_f = Field.new("diameter.flags.request")
local dia_rc_f = Field.new("diameter.Result-Code")
local chunk_f = Field.new("sctp.chunk_type")
local DIAMETER = { [257] = "Capabilities-Exchange", [272] = "Credit-Control", [280] = "Device-Watchdog",
                   [282] = "Disconnect-Peer", [316] = "Update-Location" }

local SECRET = {
  access_token = true, id_token = true, refresh_token = true, code = true, state = true,
  nonce = true, authorizeUrl = true, code_verifier = true, code_challenge = true,
  client_assertion = true,
}

local function masked(key, value)
  if SECRET[key] then return key .. ":•••" end
  return key .. ":" .. value
end

local function message()
  local m, s = method_f(), status_f()
  if m then return tostring(m.value) end
  if s then
    local p = phrase_f()
    return tostring(s.value) .. (p and (" " .. tostring(p.value)) or "")
  end
  local c = dia_cmd_f()
  if c then
    local r, rc = dia_req_f(), dia_rc_f()
    local name = DIAMETER[c.value] or ("command " .. tostring(c.value))
    if r and r.value then return name .. " Request" end
    return name .. " Answer" .. (rc and (" " .. tostring(rc.value)) or "")
  end
  for _, ch in ipairs({ chunk_f() }) do
    if ch.value == 1 then return "SCTP INIT (association setup)" end
    if ch.value == 2 then return "SCTP INIT ACK" end
  end
  return nil
end

function proto.dissector(tvb, pinfo, tree)
  local node
  local msg = message()
  if msg then
    node = tree:add(proto)
    node:add(f_msg, msg)
  end
  local uri = uri_f()
  if uri then
    node = node or tree:add(proto)
    node:add(f_path, (tostring(uri.value):gsub("%?.*$", "")))
  end
  local parts = {}
  for _, m in ipairs({ member_f() }) do
    local s = tostring(m.value)
    local k, v = s:match("^([^:]+):(.*)$")
    parts[#parts + 1] = k and masked(k, v) or s
  end
  local keys, values = { form_key_f() }, { form_value_f() }
  for i, k in ipairs(keys) do
    parts[#parts + 1] = masked(tostring(k.value), values[i] and tostring(values[i].value) or "")
  end
  if #parts > 0 then
    node = node or tree:add(proto)
    node:add(f_body, table.concat(parts, "  "))
  end
end

register_postdissector(proto)
