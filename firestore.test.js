const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-katem-a68b2583-0ae9-4f7c-941d-d46944a489f8";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";
const MALLORY_UID = "mallory_uid_003";
const ALICE_PID = "KTM-482917A";
const BOB_PID = "KTM-739104B";
const CONV_ID = `${ALICE_UID}_${BOB_UID}`;

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msAgo = 5000) {
  return new Date(Date.now() - msAgo);
}

function futureDate(msAhead = 120000) {
  return new Date(Date.now() + msAhead);
}

function makeUserPayload(uid, publicId, ts = pastDate()) {
  return {
    userId: uid,
    publicId,
    displayName: "مستخدم كاتم",
    bio: "",
    avatarUrl: "",
    avatarColorIndex: 2,
    hideLastSeen: false,
    showOnlineStatus: true,
    hideNotificationPreview: true,
    screenshotProtection: true,
    blurOnBackground: true,
    disappearingDefaultSec: 0,
    lastSeenAt: ts,
    createdAt: ts,
    updatedAt: ts,
  };
}

function makePublicIdPayload(uid, publicId, ts = pastDate()) {
  return {
    publicId,
    ownerUid: uid,
    displayName: "مستخدم كاتم",
    bio: "",
    avatarUrl: "",
    avatarColorIndex: 2,
    onlineVisible: true,
    online: true,
    lastSeenVisible: true,
    lastSeenAt: ts,
    createdAt: ts,
    updatedAt: ts,
  };
}

function makeConversationPayload(ts = pastDate()) {
  return {
    memberUids: [ALICE_UID, BOB_UID],
    member1Uid: ALICE_UID,
    member1PublicId: ALICE_PID,
    member1Name: "أحمد",
    member1Avatar: "",
    member1ColorIndex: 1,
    member2Uid: BOB_UID,
    member2PublicId: BOB_PID,
    member2Name: "سارة",
    member2Avatar: "",
    member2ColorIndex: 3,
    lastMessageLabel: "بدأت محادثة مشفّرة",
    lastMessageType: "system",
    lastMessageSenderUid: ALICE_UID,
    lastMessageAt: ts,
    typingUid: "",
    archivedByUids: [],
    deletedByUids: [],
    disappearingSeconds: 0,
    createdByUid: ALICE_UID,
    createdAt: ts,
    updatedAt: ts,
  };
}

function makeMessagePayload(senderUid = ALICE_UID, senderPid = ALICE_PID, ts = pastDate()) {
  return {
    conversationId: CONV_ID,
    memberUids: [ALICE_UID, BOB_UID],
    senderUid,
    senderPublicId: senderPid,
    messageType: "text",
    ciphertext: "V2V0aGVyRW5jcnlwdGVkUGF5bG9hZEJhc2U2NA==",
    iv: "MTIzNDU2Nzg5MDEy",
    encryptionScheme: "ECDH-P256-AES256-GCM",
    mediaFileId: "",
    mediaUrl: "",
    mediaMimeType: "",
    mediaFileName: "",
    mediaSizeBytes: 0,
    mediaDurationSec: 0,
    replyToMessageId: "",
    replyPreviewCiphertext: "",
    replyPreviewIv: "",
    deliveryStatus: "sent",
    edited: false,
    deleted: false,
    expiresAtEpochMs: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

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

test("1. Unauthenticated requests are denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("publicIds").doc(ALICE_PID).get());
  await assertFails(unauthDb.collection("encryptionKeys").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("conversations").get());
  await assertFails(unauthDb.collection("mediaFiles").get());
  await assertFails(unauthDb.collection("reports").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(makeUserPayload(ALICE_UID, ALICE_PID))
  );
});

test("2. Users collection: owner CRUD, immutable publicId/createdAt, ghost-field & cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(20000);

  // Valid create matching KatemRepository
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(makeUserPayload(ALICE_UID, ALICE_PID, created))
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Valid profile update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "أحمد علي",
      bio: "مراسلة خاصة",
      avatarUrl: "",
      avatarColorIndex: 4,
      updatedAt: pastDate(1000),
    })
  );

  // Valid privacy update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      hideLastSeen: true,
      showOnlineStatus: false,
      hideNotificationPreview: true,
      screenshotProtection: true,
      blurOnBackground: true,
      disappearingDefaultSec: 3600,
      updatedAt: pastDate(500),
    })
  );

  // Reject mutating immutable publicId
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      publicId: "KTM-999999Z",
      updatedAt: pastDate(400),
    })
  );

  // Reject ghost field (Shadow Update) & @DocumentId 'id' leakage
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      ...makeUserPayload(BOB_UID, BOB_PID),
      id: BOB_UID,
    })
  );

  // Reject future timestamp
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set(makeUserPayload(BOB_UID, BOB_PID, futureDate()))
  );

  // Cross-user read/update & blanket list denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());
});

test("3. PublicIds collection: exact lookup allowed, scraping list denied, anti-hijacking enforced", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("publicIds").doc(ALICE_PID).set(makePublicIdPayload(ALICE_UID, ALICE_PID))
  );

  // Bob can look up Alice's exact Public ID
  await assertSucceeds(bobDb.collection("publicIds").doc(ALICE_PID).get());

  // Scraping all publicIds via list is forbidden
  await assertFails(bobDb.collection("publicIds").get());

  // Bob cannot hijack or modify Alice's Public ID
  await assertFails(
    bobDb.collection("publicIds").doc(ALICE_PID).update({
      displayName: "مخترق",
      bio: "",
      avatarUrl: "",
      avatarColorIndex: 0,
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(bobDb.collection("publicIds").doc(ALICE_PID).delete());
});

test("4. EncryptionKeys: exact peer get allowed, list denied, owner-only write", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("encryptionKeys").doc(ALICE_UID).set({
      userId: ALICE_UID,
      publicKeyBase64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890ABCDEFGHIJKLMNOPQRST==",
      fingerprint: "A1B2 C3D4 E5F6 0718 293A 4B5C 6D7E 8F90",
      algorithm: "ECDH-P256-AES256-GCM",
      keyVersion: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(bobDb.collection("encryptionKeys").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("encryptionKeys").get());
  await assertFails(
    bobDb.collection("encryptionKeys").doc(ALICE_UID).update({
      publicKeyBase64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHACKEDKEY12345678901234567890==",
      fingerprint: "FFFF FFFF FFFF FFFF FFFF FFFF FFFF FFFF",
      algorithm: "ECDH-P256-AES256-GCM",
      keyVersion: 2,
      updatedAt: pastDate(1000),
    })
  );
});

test("5. User subcollections: contacts, devices, recoveryData, blockedUsers strictly owner-isolated", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(10000);

  // Contacts
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc(BOB_UID).set({
      userId: ALICE_UID,
      contactUid: BOB_UID,
      contactPublicId: BOB_PID,
      displayName: "سارة",
      bio: "",
      avatarUrl: "",
      avatarColorIndex: 3,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("contacts").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("contacts").get());

  // Devices
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev_12345678").set({
      userId: ALICE_UID,
      deviceName: "Google Pixel 9",
      platform: "android",
      appVersion: "1.0",
      lastActiveAt: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("devices").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("devices").get());

  // RecoveryData (PBKDF2 encrypted key vault)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recoveryData").doc("primary").set({
      userId: ALICE_UID,
      encryptedBlob: "ZW5jcnlwdGVkX3ByaXZhdGVfa2V5X2Jsb2JfMTIzNDU2Nzg5MA==",
      saltBase64: "c2FsdF8xNl9ieXRlc19hYmNk",
      ivBase64: "aXZfMTJfYnl0ZXM=",
      kdfIterations: 100000,
      keyVersion: 1,
      failedAttempts: 0,
      lockedUntilEpochMs: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recoveryData").doc("primary").update({
      failedAttempts: 1,
      lockedUntilEpochMs: 0,
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("recoveryData").doc("primary").get()
  );

  // BlockedUsers
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedUsers").doc(MALLORY_UID).set({
      userId: ALICE_UID,
      blockedUid: MALLORY_UID,
      blockedPublicId: "KTM-000111Z",
      blockedDisplayName: "حساب مزعج",
      createdAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("blockedUsers").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("blockedUsers").get());
  // Cannot self-block
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedUsers").doc(ALICE_UID).set({
      userId: ALICE_UID,
      blockedUid: ALICE_UID,
      blockedPublicId: ALICE_PID,
      blockedDisplayName: "نفسي",
      createdAt: ts,
    })
  );
});

test("6. Conversations & Messages: strict membership, Kotlin query alignment, edit/delete/read actions", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const malloryDb = testEnv.authenticatedContext(MALLORY_UID).firestore();
  const ts = pastDate(15000);

  // Alice creates conversation with Bob
  await assertSucceeds(
    aliceDb.collection("conversations").doc(CONV_ID).set(makeConversationPayload(ts))
  );

  // Exact Kotlin query: where("memberUids", "array-contains", uid)
  await assertSucceeds(
    aliceDb.collection("conversations").where("memberUids", "array-contains", ALICE_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("conversations").where("memberUids", "array-contains", BOB_UID).get()
  );
  // Unfiltered query denied
  await assertFails(aliceDb.collection("conversations").get());
  // Non-member Mallory denied
  await assertFails(malloryDb.collection("conversations").doc(CONV_ID).get());

  // Typing & archive updates
  await assertSucceeds(
    aliceDb.collection("conversations").doc(CONV_ID).update({
      typingUid: ALICE_UID,
      updatedAt: pastDate(3000),
    })
  );
  await assertSucceeds(
    bobDb.collection("conversations").doc(CONV_ID).update({
      archivedByUids: [BOB_UID],
      updatedAt: pastDate(2500),
    })
  );

  // Alice sends encrypted message
  const msgRefAlice = aliceDb
    .collection("conversations")
    .doc(CONV_ID)
    .collection("messages")
    .doc("msg_001");
  await assertSucceeds(msgRefAlice.set(makeMessagePayload(ALICE_UID, ALICE_PID, ts)));

  // Exact Kotlin message query
  await assertSucceeds(
    bobDb
      .collection("conversations")
      .doc(CONV_ID)
      .collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );
  // Non-member Mallory cannot read or send messages
  await assertFails(
    malloryDb.collection("conversations").doc(CONV_ID).collection("messages").doc("msg_001").get()
  );
  await assertFails(
    malloryDb
      .collection("conversations")
      .doc(CONV_ID)
      .collection("messages")
      .doc("msg_hack")
      .set(makeMessagePayload(MALLORY_UID, "KTM-999999Z", ts))
  );

  // Recipient Bob marks message as read
  const msgRefBob = bobDb
    .collection("conversations")
    .doc(CONV_ID)
    .collection("messages")
    .doc("msg_001");
  await assertSucceeds(
    msgRefBob.update({
      deliveryStatus: "read",
      updatedAt: pastDate(2000),
    })
  );
  // Recipient Bob CANNOT edit Alice's ciphertext
  await assertFails(
    msgRefBob.update({
      ciphertext: "SGFja2VkQ2lwaGVydGV4dA==",
      iv: "MTIzNDU2Nzg5MDEy",
      edited: true,
      updatedAt: pastDate(1800),
    })
  );

  // Sender Alice edits her own message, then soft-deletes it
  await assertSucceeds(
    msgRefAlice.update({
      ciphertext: "VXBkYXRlZEVuY3J5cHRlZFBheWxvYWQ=",
      iv: "OTg3NjU0MzIxMDEy",
      edited: true,
      updatedAt: pastDate(1500),
    })
  );
  await assertSucceeds(
    msgRefAlice.update({
      ciphertext: "",
      iv: "",
      mediaUrl: "",
      mediaFileName: "",
      deleted: true,
      updatedAt: pastDate(1000),
    })
  );
  // Terminal state lock: editing after soft-delete must fail
  await assertFails(
    msgRefAlice.update({
      ciphertext: "UmVzdXJyZWN0ZWQ=",
      iv: "MTIzNDU2Nzg5MDEy",
      edited: true,
      updatedAt: pastDate(500),
    })
  );
});

test("7. MediaFiles (Hostinger), Notifications & Reports: Parent Gate and immutability", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const malloryDb = testEnv.authenticatedContext(MALLORY_UID).firestore();
  const ts = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("conversations").doc(CONV_ID).set(makeConversationPayload(ts))
  );

  // Hostinger mediaFile record created by Alice (verified conversation member)
  await assertSucceeds(
    aliceDb.collection("mediaFiles").doc("med_001").set({
      conversationId: CONV_ID,
      memberUids: [ALICE_UID, BOB_UID],
      uploaderUid: ALICE_UID,
      storageProvider: "hostinger",
      remotePath: `katem_private_storage/${CONV_ID}/med_001.jpg`,
      downloadUrl: `https://example.com/katem-api/download.php?cid=${CONV_ID}&fid=med_001`,
      mimeType: "image/jpeg",
      fileName: "photo.jpg",
      sizeBytes: 245760,
      durationSec: 0,
      encrypted: false,
      createdAt: ts,
    })
  );
  await assertSucceeds(bobDb.collection("mediaFiles").doc("med_001").get());
  await assertFails(malloryDb.collection("mediaFiles").doc("med_001").get());
  await assertFails(aliceDb.collection("mediaFiles").doc("med_001").update({ fileName: "hack.exe" }));

  // Alice delivers privacy-safe notification to Bob (verified via Parent Gate conversation)
  await assertSucceeds(
    aliceDb.collection("users").doc(BOB_UID).collection("notifications").doc("ntf_001").set({
      userId: BOB_UID,
      conversationId: CONV_ID,
      senderUid: ALICE_UID,
      senderPublicId: ALICE_PID,
      senderName: "رسالة جديدة",
      type: "message",
      previewHidden: true,
      read: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  // Mallory cannot spam notifications to Bob (not a member of CONV_ID)
  await assertFails(
    malloryDb.collection("users").doc(BOB_UID).collection("notifications").doc("ntf_spam").set({
      userId: BOB_UID,
      conversationId: CONV_ID,
      senderUid: MALLORY_UID,
      senderPublicId: "KTM-999999Z",
      senderName: "Spam",
      type: "message",
      previewHidden: true,
      read: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  // Bob reads & marks notification read
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("notifications").doc("ntf_001").update({
      read: true,
      updatedAt: pastDate(1000),
    })
  );

  // Abuse reports: reporter can create & read own, immutable afterward
  await assertSucceeds(
    aliceDb.collection("reports").doc("rep_001").set({
      reporterUid: ALICE_UID,
      reportedUid: MALLORY_UID,
      reportedPublicId: "KTM-999999Z",
      conversationId: CONV_ID,
      reason: "spam",
      details: "رسائل مزعجة متكررة",
      status: "submitted",
      createdAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.collection("reports").where("reporterUid", "==", ALICE_UID).get()
  );
  await assertFails(bobDb.collection("reports").doc("rep_001").get());
  await assertFails(aliceDb.collection("reports").doc("rep_001").delete());
});
