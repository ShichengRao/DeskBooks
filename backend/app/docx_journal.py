"""Import a Word doc into the journal, one entry per page.

The user keeps a "Current Savings Plan" Word doc with manual page
breaks separating versioned updates ("June 16th, 2020 initial plan",
"Dec 9th, 2023 update", …). Each page is imported as its own
JournalEntry so the planning view shows the full revision history.

Title comes from the first non-empty line of the page; the entry_date
is parsed out of that title (it always leads with a date). Body is the
rest of the page joined with newlines.

Idempotent: an entry is skipped if a JournalEntry with the same title
already exists.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date as _date
from pathlib import Path

from dateutil import parser as dateparser
from docx import Document
from docx.oxml.ns import qn
from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import JournalEntry, JournalEntryRevision


@dataclass
class ParsedPage:
    title: str
    entry_date: _date
    body_markdown: str


def _pages_from_docx(path: Path) -> list[list[str]]:
    doc = Document(str(path))
    pages: list[list[str]] = [[]]
    for p in doc.paragraphs:
        # Word page breaks live inside a run as a <w:br w:type="page"/>.
        has_break = False
        for run in p.runs:
            for br in run._element.findall(qn("w:br")):
                if br.get(qn("w:type")) == "page":
                    has_break = True
        if has_break:
            pages.append([])
        pages[-1].append(p.text)
    return pages


_DATE_LEAD = re.compile(
    r"""^\s*
        (?P<month>jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|
         jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)
        \s+
        (?P<day>\d{1,2})
        (?:st|nd|rd|th)?
        ,?\s*
        (?P<year>\d{4})
    """,
    re.IGNORECASE | re.VERBOSE,
)


def _parse_page(lines: list[str]) -> ParsedPage | None:
    non_empty = [l for l in lines if l.strip()]
    if not non_empty:
        return None
    title = non_empty[0].strip()
    body = "\n".join(non_empty[1:]).strip()
    m = _DATE_LEAD.search(title)
    if m:
        try:
            entry_date = dateparser.parse(
                f"{m.group('month')} {m.group('day')} {m.group('year')}"
            ).date()
        except Exception:
            entry_date = _date.today()
    else:
        entry_date = _date.today()
    return ParsedPage(title=title, entry_date=entry_date, body_markdown=body)


def import_savings_plan(db: Session, path: Path) -> int:
    """Returns the number of entries inserted."""
    if not path.exists():
        return 0
    pages = _pages_from_docx(path)
    inserted = 0
    for raw in pages:
        parsed = _parse_page(raw)
        if parsed is None or not parsed.body_markdown:
            continue
        existing = db.scalar(select(JournalEntry).where(JournalEntry.title == parsed.title))
        if existing:
            continue
        entry = JournalEntry(
            entry_date=parsed.entry_date,
            title=parsed.title,
            body_markdown=parsed.body_markdown,
        )
        db.add(entry)
        db.flush()
        db.add(
            JournalEntryRevision(
                entry_id=entry.id,
                title=entry.title,
                body_markdown=entry.body_markdown,
                entry_date=entry.entry_date,
                goal_id=entry.goal_id,
                change_summary=f"imported from {path.name}",
            )
        )
        inserted += 1
    if inserted:
        db.commit()
    return inserted
