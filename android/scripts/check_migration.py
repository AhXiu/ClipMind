"""Host-side SQLite checks of the actual Kotlin migration/query statements.

No Android runtime, network, third-party packages or persistent user database.
This complements, but does not replace, Room's on-device schema validation.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest


DATA = Path(__file__).resolve().parents[1] / "app/src/main/java/com/clipmind/android/data"


def migration(name):
    source = (DATA / "ClipMindDatabase.kt").read_text()
    match = re.search(rf"internal val {name} = listOf\((.*?)\n\)", source, re.S)
    return [json.loads(value) for value in re.findall(r'"(?:[^"\\]|\\.)*"', match[1])]


def query(method, filename="CaptureOutboxDao.kt"):
    source = (DATA / filename).read_text()
    matches = re.findall(r'@Query\((""".*?"""|"[^\n]*")\)\s+(?:suspend )?fun (\w+)', source, re.S)
    value = next(value for value, name in matches if name == method)
    return value[3:-3] if value.startswith('"""') else json.loads(value)


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.execute("""CREATE TABLE capture_outbox (
            id INTEGER PRIMARY KEY, clientCaptureId TEXT, encryptedRawText TEXT,
            hash TEXT, sourceApp TEXT, sourceUrl TEXT, mode TEXT, state TEXT,
            capturedAt INTEGER, updatedAt INTEGER, retryCount INTEGER, nextRetryAt INTEGER,
            lastErrorCode TEXT, serverCardId TEXT, serverCardStatus TEXT,
            serverLastError TEXT, aiProvider TEXT, aiModel TEXT, encryptedClientAnalysis TEXT)""")
        for row_id, state in [(1, "READY"), (2, "DISCARDED")]:
            self.db.execute("""INSERT INTO capture_outbox VALUES
                (?, ?, 'encrypted-original', 'hash', NULL, NULL, 'confirm', ?, 100, 200,
                0, 0, NULL, 'server-card', 'awaiting_confirm', NULL, 'ark', 'model', 'encrypted-analysis')""",
                (row_id, f"task-{row_id}", state))
        for statement in migration("MIGRATION_3_4_STATEMENTS") + migration("MIGRATION_4_5_STATEMENTS") + migration("MIGRATION_5_6_STATEMENTS") + migration("MIGRATION_6_7_STATEMENTS"):
            self.db.execute(statement)

    def test_migration_preserves_ciphertext_and_sync_metadata(self):
        self.assertEqual(
            ("encrypted-original", 1, None),
            self.db.execute("SELECT encryptedContent, contentRevision, deletedAt FROM local_cards WHERE id = 1").fetchone(),
        )
        self.assertEqual(
            ("server-card", "encrypted-analysis", None),
            self.db.execute("SELECT serverCardId, encryptedClientAnalysis, encryptedServerAnalysis FROM sync_metadata WHERE cardId = 1").fetchone(),
        )
        self.assertEqual((200,), self.db.execute("SELECT deletedAt FROM local_cards WHERE id = 2").fetchone())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_claim_rejects_changed_task_and_deleted_card(self):
        self.assertEqual(0, self.db.execute(query("claim"), {"id": 1, "taskId": "old-task", "now": 300}).rowcount)
        self.assertEqual(1, self.db.execute(query("claim"), {"id": 1, "taskId": "task-1", "now": 300}).rowcount)
        self.assertEqual(0, self.db.execute(query("claim"), {"id": 1, "taskId": "task-1", "now": 300}).rowcount)
        self.db.execute("UPDATE sync_metadata SET uploadState = 'READY' WHERE cardId = 2")
        self.assertEqual(0, self.db.execute(query("claim"), {"id": 2, "taskId": "task-2", "now": 300}).rowcount)

    def test_late_result_cannot_overwrite_new_task(self):
        self.db.execute("UPDATE sync_metadata SET uploadState = 'SUCCEEDED' WHERE cardId = 1")
        values = {"id": 1, "cardId": "server-card", "taskId": "task-1", "status": "synced",
                  "serverLastError": None, "analysis": "encrypted-result", "now": 300}
        self.assertEqual(1, self.db.execute(query("updateServerCard"), values).rowcount)
        self.db.execute("UPDATE local_cards SET clientCaptureId = 'task-new' WHERE id = 1")
        self.assertEqual(0, self.db.execute(query("updateServerCard"), values).rowcount)

    def test_relation_confirmation_requires_current_non_deleted_sources(self):
        self.db.execute("UPDATE local_cards SET deletedAt = NULL WHERE id = 2")
        self.db.execute("""INSERT INTO card_relations (sourceCardId,targetCardId,relationType,status,createdAt,updatedAt,sourceRevision,targetRevision)
            VALUES (1,2,'same_topic','CANDIDATE',1,1,1,1)""")
        values = {"relationId": 1, "status": "CONFIRMED", "now": 2}
        self.db.execute("UPDATE local_cards SET contentRevision = 2 WHERE id = 1")
        self.assertEqual(0, self.db.execute(query("resolveRelation", "LocalCardDao.kt"), values).rowcount)
        self.db.execute("UPDATE local_cards SET contentRevision = 1 WHERE id = 1")
        self.assertEqual(1, self.db.execute(query("resolveRelation", "LocalCardDao.kt"), values).rowcount)
        self.db.execute("UPDATE card_relations SET status = 'CANDIDATE'")
        self.db.execute("UPDATE local_cards SET deletedAt = 2 WHERE id = 2")
        self.assertEqual(0, self.db.execute(query("resolveRelation", "LocalCardDao.kt"), values).rowcount)

    def test_note_and_evidence_schema_only_stores_encrypted_payload(self):
        columns = [row[1] for row in self.db.execute("PRAGMA table_info(knowledge_notes)")]
        self.assertEqual(["id", "encryptedPayload", "createdAt"], columns)
        self.db.execute("INSERT INTO knowledge_notes VALUES ('note','encrypted-test-data',1)")
        self.assertEqual(("encrypted-test-data",), self.db.execute("SELECT encryptedPayload FROM knowledge_notes").fetchone())

    def test_tag_merge_preserves_references_and_delete_protects_primary(self):
        self.db.execute("INSERT INTO tags VALUES (1,'source','source',1,2,'pending')")
        self.db.execute("INSERT INTO tags VALUES (2,'target','target',1,2,'confirmed')")
        self.db.execute("INSERT INTO tags VALUES (3,'技术','技术',1,1,'confirmed')")
        self.db.execute("INSERT INTO card_tag_refs VALUES (1,1)")
        self.db.execute("INSERT INTO card_tag_refs VALUES (1,2)")
        self.db.execute(query("copyTagRefs", "LocalCardDao.kt"), {"source": 1, "target": 2})
        self.db.execute(query("deleteTag", "LocalCardDao.kt"), {"id": 1})
        self.db.execute(query("deleteTag", "LocalCardDao.kt"), {"id": 3})
        self.assertEqual([(1, 2)], self.db.execute("SELECT * FROM card_tag_refs").fetchall())
        self.assertEqual([(3,)], self.db.execute("SELECT id FROM tags WHERE level = 1").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_v7_encrypted_payload_and_review_state_survive_queries(self):
        self.db.execute("INSERT INTO card_vectors VALUES (1,1,'model-v1','encrypted-vector')")
        self.db.execute("INSERT INTO card_reading VALUES (1,1,'encrypted-analysis',1)")
        self.db.execute("INSERT INTO reader_documents VALUES ('weekly:2026-09-07','weekly','encrypted-report',1)")
        self.db.execute("INSERT INTO review_schedule VALUES (1,1,123,6,2,2.5,'2026-09-08')")
        self.assertEqual((6, 2, 2.5), self.db.execute("SELECT intervalDays,repetitions,ease FROM review_schedule").fetchone())
        self.assertNotIn('text', [c[1] for c in self.db.execute("PRAGMA table_info(card_vectors)")])

    def test_existing_tags_are_classified_without_changing_ids(self):
        legacy = sqlite3.connect(':memory:')
        self.addCleanup(legacy.close)
        legacy.execute("CREATE TABLE tags(id INTEGER PRIMARY KEY,name TEXT,normalizedName TEXT,createdAt INTEGER)")
        legacy.execute("INSERT INTO tags VALUES (8,'认知','认知',99)")
        legacy.execute("INSERT INTO tags VALUES (9,'长期主义','长期主义',100)")
        for statement in migration('MIGRATION_6_7_STATEMENTS'):
            legacy.execute(statement)
        self.assertEqual([(8,1,'confirmed'),(9,2,'confirmed')], legacy.execute("SELECT id,level,status FROM tags ORDER BY id").fetchall())


if __name__ == "__main__":
    unittest.main()
