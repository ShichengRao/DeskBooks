from __future__ import annotations

from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

from fastapi import APIRouter, HTTPException
from sqlalchemy import select

from .. import models, schemas
from .common import DbSession, get_or_404

router = APIRouter(prefix="/api/journal", tags=["journal"])


@router.get("", response_model=list[schemas.JournalEntryOut])
def list_entries(db: DbSession, goal_id: int | None = None):
    stmt = select(models.JournalEntry).order_by(models.JournalEntry.entry_date.desc())
    if goal_id is not None:
        stmt = stmt.where(models.JournalEntry.goal_id == goal_id)
    return list(db.scalars(stmt))


def _snapshot(obj: models.JournalEntry, change_summary: str | None) -> models.JournalEntryRevision:
    return models.JournalEntryRevision(
        entry_id=obj.id,
        title=obj.title,
        body_markdown=obj.body_markdown,
        entry_date=obj.entry_date,
        goal_id=obj.goal_id,
        change_summary=change_summary,
    )


@router.post("", response_model=schemas.JournalEntryOut)
def create_entry(body: schemas.JournalEntryIn, db: DbSession):
    obj = models.JournalEntry(**body.model_dump())
    db.add(obj)
    db.flush()
    db.add(_snapshot(obj, "created"))
    db.commit()
    db.refresh(obj)
    return obj


@router.post("/import-preview", response_model=schemas.JournalImportPreview)
def import_preview(body: schemas.JournalImportPreviewRequest):
    path = Path(body.path).expanduser()
    if not path.exists() or not path.is_file():
        raise HTTPException(404, "file not found")
    pages = _document_pages(path)
    drafts = [
        schemas.JournalImportDraft(
            page_number=i + 1,
            title=f"{path.stem} page {i + 1}",
            body_markdown=page,
        )
        for i, page in enumerate(pages)
        if page.strip()
    ]
    if not drafts:
        raise HTTPException(400, "no journal text found")
    return schemas.JournalImportPreview(source_filename=path.name, drafts=drafts)


def _document_pages(path: Path) -> list[str]:
    suffix = path.suffix.lower()
    if suffix in {".txt", ".md", ".markdown"}:
        text = path.read_text(encoding="utf-8", errors="replace")
        return _split_text_pages(text)
    if suffix == ".docx":
        return _docx_pages(path)
    raise HTTPException(400, "supported journal imports: .txt, .md, .markdown, .docx")


def _split_text_pages(text: str) -> list[str]:
    if "\f" in text:
        parts = text.split("\f")
    else:
        parts = []
        current: list[str] = []
        for line in text.splitlines():
            if line.strip().lower() in {"--- page ---", "=== page ==="}:
                parts.append("\n".join(current))
                current = []
            else:
                current.append(line)
        parts.append("\n".join(current))
    return [part.strip() for part in parts if part.strip()]


def _docx_pages(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as docx:
            xml = docx.read("word/document.xml")
    except (KeyError, zipfile.BadZipFile, OSError):
        raise HTTPException(400, "could not read docx document text")

    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    root = ET.fromstring(xml)
    pages: list[str] = []
    current: list[str] = []
    for paragraph in root.findall(".//w:p", ns):
        text = "".join(node.text or "" for node in paragraph.findall(".//w:t", ns)).strip()
        if text:
            current.append(text)
        has_page_break = (
            paragraph.find(".//w:br[@w:type='page']", ns) is not None
            or paragraph.find(".//w:lastRenderedPageBreak", ns) is not None
        )
        if has_page_break and current:
            pages.append("\n\n".join(current))
            current = []
    if current:
        pages.append("\n\n".join(current))
    return [page.strip() for page in pages if page.strip()]


@router.get("/{entry_id}", response_model=schemas.JournalEntryOut)
def get_entry(entry_id: int, db: DbSession):
    return get_or_404(db, models.JournalEntry, entry_id)


@router.patch("/{entry_id}", response_model=schemas.JournalEntryOut)
def update_entry(entry_id: int, body: schemas.JournalEntryUpdate, db: DbSession):
    obj = get_or_404(db, models.JournalEntry, entry_id)
    data = body.model_dump(exclude_unset=True)
    change_summary = data.pop("change_summary", None)
    before = (obj.title, obj.body_markdown, obj.entry_date, obj.goal_id)
    for k, v in data.items():
        setattr(obj, k, v)
    after = (obj.title, obj.body_markdown, obj.entry_date, obj.goal_id)
    if after != before:
        db.add(_snapshot(obj, change_summary or "edited"))
    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{entry_id}")
def delete_entry(entry_id: int, db: DbSession):
    obj = get_or_404(db, models.JournalEntry, entry_id)
    db.delete(obj)
    db.commit()
    return {"status": "deleted"}


@router.get("/{entry_id}/revisions", response_model=list[schemas.JournalEntryRevisionOut])
def list_revisions(entry_id: int, db: DbSession):
    return list(
        db.scalars(
            select(models.JournalEntryRevision)
            .where(models.JournalEntryRevision.entry_id == entry_id)
            .order_by(models.JournalEntryRevision.changed_at.desc())
        )
    )
