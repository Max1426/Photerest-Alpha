// Offline only. Never reads or sends photos, calls the Worker, or prints private material.
import { generateKeyPairSync, createPrivateKey, createPublicKey, sign, verify, randomUUID, createHash } from "node:crypto";
import { readFileSync, writeFileSync, appendFileSync, existsSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";

const args = process.argv.slice(2);
const action = args.shift();
function option(name) { const i = args.indexOf(name); return i < 0 ? undefined : args[i + 1]; }
const home = option("--home");
const password = process.env.PHOTEREST_AUTH_PASSPHRASE;
function write(path, data) { writeFileSync(path, data, { flag: "wx", mode: 0o600 }); }
try {
  if (!home || !password) throw Error("Use Search-Authorization.ps1; private home and passphrase are required.");
  const privatePath = join(resolve(home), "authority-private.pem");
  const publicPath = join(resolve(home), "authority-public.json");
  if (action === "init") {
    mkdirSync(home, { recursive: true });
    if (existsSync(privatePath) || existsSync(publicPath)) throw Error("Authority already exists; refusing to overwrite.");
    const pair = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const spki = pair.publicKey.export({ type: "spki", format: "der" }).toString("base64url");
    write(privatePath, pair.privateKey.export({ type: "pkcs8", format: "pem", cipher: "aes-256-cbc", passphrase: password }));
    write(publicPath, JSON.stringify({ v: 1, key: spki, fingerprint: createHash("sha256").update(Buffer.from(spki, "base64url")).digest("hex") }, null, 2));
    console.log("Authority created. Public file: " + publicPath);
  } else if (action === "sign") {
    const source = option("--request"), target = option("--out");
    if (!source || !target) throw Error("--request and --out are required.");
    const raw = readFileSync(source);
    if (raw.length > 16_384) throw Error("Request is too large.");
    const request = JSON.parse(raw.toString("utf8"));
    const allowed = { "com.photerest.app": ["google", "baidu"], "com.photerest.app.material": ["google"] };
    const engines = Object.hasOwn(allowed, request.app) ? allowed[request.app] : null;
    if (request.v !== 1 || !engines || !/^[A-Za-z0-9-]{16,80}$/.test(request.requestId)) throw Error("Invalid request.");
    const key = createPublicKey({ key: Buffer.from(request.key, "base64url"), format: "der", type: "spki" });
    if (key.asymmetricKeyType !== "rsa" || key.asymmetricKeyDetails.modulusLength !== 2048) throw Error("Invalid device key.");
    if (!verify("RSA-SHA256", Buffer.from(["PHOTEREST-REQUEST-V1", request.app, request.requestId, request.key].join("\n")), key,
      Buffer.from(request.proof, "base64url"))) throw Error("Request proof is invalid.");
    const id = randomUUID();
    const payload = Buffer.from(JSON.stringify({ v: 1, id, app: request.app, key: request.key, engines })).toString("base64url");
    const authority = createPrivateKey({ key: readFileSync(privatePath), passphrase: password });
    const certificate = payload + "." + sign("RSA-SHA256", Buffer.from(payload), authority).toString("base64url");
    write(target, certificate + "\n");
    appendFileSync(join(home, "issued-licenses.jsonl"), JSON.stringify({ id, app: request.app,
      deviceKeyHash: createHash("sha256").update(Buffer.from(request.key, "base64url")).digest("hex"),
      issuedAt: new Date().toISOString(), certificate: resolve(target) }) + "\n", { mode: 0o600 });
    console.log("License created for " + request.app + ": " + target);
  } else throw Error("Supported actions: init, sign");
} catch (error) { console.error(error.message); process.exitCode = 1; }
