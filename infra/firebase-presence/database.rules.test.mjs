import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { readFile } from "node:fs/promises";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import { get, ref, remove, serverTimestamp, set, update } from "firebase/database";

const projectId = process.env.GCLOUD_PROJECT;
const emulatorHost = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
if (process.versions.node.split(".")[0] !== "24") throw new Error("Rules tests require Node 24.");
if (!projectId?.startsWith("demo-")) throw new Error("GCLOUD_PROJECT must be an explicit demo-* project.");
if (!emulatorHost || !/^(127\.0\.0\.1|localhost|\[::1\]):\d+$/.test(emulatorHost)) {
  throw new Error("FIREBASE_DATABASE_EMULATOR_HOST must be an explicit loopback emulator.");
}
const [host, portText] = emulatorHost.replace("[::1]", "localhost").split(":");
const rules = await readFile(new URL("./database.rules.json", import.meta.url), "utf8");
let environment;

before(async () => {
  environment = await initializeTestEnvironment({
    projectId,
    database: { host, port: Number(portText), rules },
  });
});

after(async () => environment?.cleanup());

async function seedControl({ uid = "opaqueUid", lease = "leaseA", connection = "connectionA", expiresAt, active = true }) {
  await environment.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), `__presenceControls/byCapability/${uid}/${lease}`), {
      active,
      expiresAtUtc: expiresAt,
      connectionId: connection,
    });
  });
}

function client({ uid = "opaqueUid", lease = "leaseA", connection = "connectionA", leaseExp }) {
  return environment.authenticatedContext(uid, { lease, connection, leaseExp }).database();
}

test("only the claimed active connection leaf accepts the fixed timestamp schema", async () => {
  const expiresAt = Date.now() + 60_000;
  await seedControl({ expiresAt });
  const database = client({ leaseExp: expiresAt });
  await assertSucceeds(set(ref(database, "presenceConnections/opaqueUid/leaseA/connectionA"), {
    state: "connected",
    observedAt: serverTimestamp(),
  }));
  await assertFails(set(ref(database, "presenceConnections/opaqueUid/leaseA/sibling"), {
    state: "connected",
    observedAt: serverTimestamp(),
  }));
  await assertFails(get(ref(database, "presenceConnections/opaqueUid/leaseA/connectionA")));
});

test("extra fields parent writes deletion and lease extension are denied", async () => {
  const expiresAt = Date.now() + 60_000;
  await seedControl({ expiresAt });
  const database = client({ leaseExp: expiresAt });
  const leaf = ref(database, "presenceConnections/opaqueUid/leaseA/connectionA");
  await assertFails(set(leaf, { state: "connected", observedAt: serverTimestamp(), extra: true }));
  await assertFails(set(ref(database, "presenceConnections/opaqueUid/leaseA"), {
    connectionA: { state: "connected", observedAt: serverTimestamp() },
  }));
  await assertFails(remove(leaf));
  await assertFails(update(ref(database), {
    "presenceConnections/opaqueUid/leaseA/connectionA": null,
  }));
  await assertFails(get(ref(database, "presenceConnections")));
  await assertFails(get(ref(database, "presenceConnections/opaqueUid")));
  await assertFails(get(ref(database, "presenceConnections/opaqueUid/leaseA")));
  await assertFails(set(ref(database, "__presenceControls/byCapability/opaqueUid/leaseA/expiresAtUtc"), Date.now() + 600_000));
});

test("expired disabled and forged claims cannot publish", async () => {
  const expired = Date.now() - 1_000;
  await seedControl({ expiresAt: expired });
  await assertFails(set(ref(client({ leaseExp: Date.now() + 60_000 }), "presenceConnections/opaqueUid/leaseA/connectionA"), {
    state: "connected", observedAt: serverTimestamp(),
  }));
  const inactiveExpiry = Date.now() + 60_000;
  await seedControl({ lease: "inactive", connection: "connectionI", expiresAt: inactiveExpiry, active: false });
  await assertFails(set(ref(client({ lease: "inactive", connection: "connectionI", leaseExp: inactiveExpiry }), "presenceConnections/opaqueUid/inactive/connectionI"), {
    state: "connected", observedAt: serverTimestamp(),
  }));
  const future = Date.now() + 60_000;
  await seedControl({ lease: "leaseB", connection: "connectionB", expiresAt: future });
  await assertFails(set(ref(client({ lease: "leaseB", connection: "forged", leaseExp: future }), "presenceConnections/opaqueUid/leaseB/connectionB"), {
    state: "connected", observedAt: serverTimestamp(),
  }));
  assert.ok(true);
});
