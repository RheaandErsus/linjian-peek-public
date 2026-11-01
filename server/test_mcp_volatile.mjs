import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { createHash } from "node:crypto";

const origin = process.env.P0_LOCAL_ORIGIN;
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
const token = process.env.P0_LOCAL_TOKEN;
const source = fs.readFileSync(new URL("../mcp/server.js", import.meta.url), "utf8");
const helpers = source.slice(source.indexOf("function requireConfig"), source.indexOf("function makeServer"));
const context = vm.createContext({
  LINJIAN_URL: origin, LINJIAN_TOKEN: token, DEFAULT_DEVICE: "test-phone",
  fetch, Buffer, Date, setTimeout, URL, console
});
vm.runInContext(helpers, context);
const queued = await context.postCommand({action:"send_notification", device_id:"test-phone", message:"synthetic"});
const poll = await fetch(origin + "/api/poll?device_id=test-phone", {headers:{"X-Auth-Token":token}}).then(r=>r.json());
assert.equal(poll.command.id, queued.command.id);
assert.ok(poll.command.id.includes("~"));
const report = await fetch(origin + "/api/device/report", {
  method:"POST", headers:{"X-Auth-Token":token,"Content-Type":"application/json"},
  body:JSON.stringify({device_id:"test-phone",command_id:poll.command.id,ok:true,result:"synthetic-result"})
});
assert.equal(report.status, 200);
const completed = await context.waitCommand(poll.command.id, 3);
assert.equal(completed.command.status,"completed");
assert.equal(completed.command.result,"synthetic-result");
const bytes = Buffer.from(process.env.P0_SYNTHETIC_PNG, "hex");
const response = await fetch(origin + "/api/screenshot", {
  method:"POST",headers:{"X-Auth-Token":token,"Content-Type":"image/jpeg"},body:bytes
});
assert.equal(response.status,200);
const info = await context.latestInfo();
assert.equal(info.size,bytes.length);
const image = await context.fetchLatestImage();
assert.equal(image.mimeType,"image/png");
assert.equal(createHash("sha256").update(Buffer.from(image.data,"base64")).digest("hex"),
             createHash("sha256").update(bytes).digest("hex"));
await assert.rejects(context.fetchLatestImage(), /HTTP 404/);
const status = await context.commandStatus("previous-session-id");
assert.equal(status.command.status,"failed");
assert.equal(status.command.invalidated,true);
console.log("PASS: real existing MCP helpers, candidate APK poll/report/upload shapes, one-read erasure");
