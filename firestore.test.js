const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write bookmarks or passwords", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("bookmarks").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_1").set({
      userId: ALICE_UID,
      title: "Google",
      url: "https://www.google.com",
      createdAtMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("passwords").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("passwords").doc("pw_1").set({
      userId: ALICE_UID,
      url: "https://github.com/login",
      domain: "github.com",
      username: "alice@example.com",
      password: "secretPassword123",
      title: "GitHub",
      importedFrom: "Browser",
      createdAtMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Authenticated owner: valid Kotlin bookmark create, list, partial update, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const bmRef = aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_google");

  // Create
  await assertSucceeds(
    bmRef.set({
      userId: ALICE_UID,
      title: "Google Search",
      url: "https://www.google.com",
      createdAtMs: 1700000000000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // List user-scoped subcollection (works even when unfiltered by document fields)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").get()
  );

  // Partial Update (preserves userId & createdAt)
  await assertSucceeds(
    bmRef.update({
      title: "Google Home",
      url: "https://www.google.com/",
      createdAtMs: 1700000005000,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / shadow update
  await assertFails(
    bmRef.update({
      title: "Hacked",
      isVerified: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject mutating immutable createdAt
  await assertFails(
    bmRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject extra 'id' field in create payload
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_extra").set({
      id: "bm_extra",
      userId: ALICE_UID,
      title: "Invalid",
      url: "https://example.com",
      createdAtMs: 1700000000000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Delete
  await assertSucceeds(bmRef.delete());
});

test("Authenticated owner: valid Kotlin password create, list, partial update, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const pwRef = aliceDb.collection("users").doc(ALICE_UID).collection("passwords").doc("pw_github");

  // Create
  await assertSucceeds(
    pwRef.set({
      userId: ALICE_UID,
      url: "https://github.com/login",
      domain: "github.com",
      username: "alice_dev",
      password: "superSecretPassword!1",
      title: "github.com",
      importedFrom: "Browser",
      createdAtMs: 1700000000000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // List user-scoped passwords
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("passwords").get()
  );

  // Partial update (password rotation)
  await assertSucceeds(
    pwRef.update({
      password: "newRotatedPassword!2",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject empty username AND empty password
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("passwords").doc("pw_empty").set({
      userId: ALICE_UID,
      url: "https://github.com/login",
      domain: "github.com",
      username: "",
      password: "",
      title: "github.com",
      importedFrom: "Browser",
      createdAtMs: 1700000000000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("passwords").doc("pw_future").set({
      userId: ALICE_UID,
      url: "https://github.com/login",
      domain: "github.com",
      username: "alice",
      password: "pw",
      title: "github.com",
      importedFrom: "Browser",
      createdAtMs: 1700000000000,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Delete
  await assertSucceeds(pwRef.delete());
});

test("Cross-user isolation: Alice cannot read, list, update, or delete Bob's bookmarks or passwords", async () => {
  const created = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(BOB_UID).collection("bookmarks").doc("bm_bob").set({
      userId: BOB_UID,
      title: "Bob Private Bookmark",
      url: "https://internal.bob.dev",
      createdAtMs: 1700000000000,
      createdAt: created,
      updatedAt: created,
    });
    await db.collection("users").doc(BOB_UID).collection("passwords").doc("pw_bob").set({
      userId: BOB_UID,
      url: "https://internal.bob.dev/login",
      domain: "internal.bob.dev",
      username: "bob",
      password: "bobSecretPassword",
      title: "internal.bob.dev",
      importedFrom: "Browser",
      createdAtMs: 1700000000000,
      createdAt: created,
      updatedAt: created,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("bookmarks").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("bookmarks").doc("bm_bob").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("bookmarks").doc("bm_bob").update({
      title: "Hijacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("bookmarks").doc("bm_bob").delete()
  );

  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("passwords").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("passwords").doc("pw_bob").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("passwords").doc("pw_bob").delete()
  );
});
