from __future__ import annotations

from app import schemas
from app.routers import journal


def test_journal_import_preview_splits_text_pages(tmp_path):
    path = tmp_path / "planning-notes.md"
    path.write_text("First page\n\fSecond page", encoding="utf-8")

    preview = journal.import_preview(schemas.JournalImportPreviewRequest(path=str(path)))

    assert preview.source_filename == "planning-notes.md"
    assert [draft.page_number for draft in preview.drafts] == [1, 2]
    assert [draft.body_markdown for draft in preview.drafts] == ["First page", "Second page"]
