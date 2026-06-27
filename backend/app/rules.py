"""Rule engine.

Rules apply category / kind / merchant / tags to transactions based on
description-pattern (regex, case-insensitive) and optional account /
amount-range matches.

Semantics:
- Lowest priority number wins (1 before 100).
- First match sets the suggested fields; subsequent matches are ignored.
- Rules NEVER overwrite a transaction with `is_user_categorized=True`.
- A re-apply pass can be requested over already-imported transactions.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models


@dataclass
class RuleEval:
    category_id: int | None = None
    kind: models.TransactionKind | None = None
    merchant: str | None = None
    tags: list[str] | None = None
    matched_rule_id: int | None = None


@dataclass(frozen=True)
class _RuleProposalContext:
    labeled_txs: list[models.Transaction]
    all_txs: list[models.Transaction]
    total_labeled: int
    total_transactions: int
    active_signatures: set[tuple]
    active_rules: list[models.Rule]
    rejected_signatures: set[str]
    min_support: int


def _matches(rule: models.Rule, *, account_id: int, description: str, amount: Decimal) -> bool:
    if rule.match_account_id is not None and rule.match_account_id != account_id:
        return False
    if rule.match_description_pattern:
        try:
            if not re.search(rule.match_description_pattern, description, flags=re.IGNORECASE):
                return False
        except re.error:
            return False
    if rule.match_amount_min is not None and amount < rule.match_amount_min:
        return False
    if rule.match_amount_max is not None and amount > rule.match_amount_max:
        return False
    return True


def load_active_rules(db: Session) -> list[models.Rule]:
    stmt = select(models.Rule).where(models.Rule.is_active.is_(True)).order_by(models.Rule.priority.asc())
    return list(db.scalars(stmt))


def evaluate(
    rules: list[models.Rule], *, account_id: int, description: str, amount: Decimal
) -> RuleEval:
    for r in rules:
        if _matches(r, account_id=account_id, description=description, amount=amount):
            return RuleEval(
                category_id=r.set_category_id,
                kind=r.set_kind,
                merchant=r.set_merchant,
                tags=list(r.set_tags) if r.set_tags else None,
                matched_rule_id=r.id,
            )
    return RuleEval()


def reapply_to_unreviewed(db: Session) -> tuple[int, int]:
    """Re-run rules over transactions that the user hasn't manually
    categorized. Returns (rows_changed, rules_applied)."""
    rules = load_active_rules(db)
    if not rules:
        return 0, 0
    rules_by_id = {r.id: r for r in rules}
    fires = Counter()  # rule_id -> rows changed by that rule
    stmt = select(models.Transaction).where(models.Transaction.is_user_categorized.is_(False))
    for tx in db.scalars(stmt):
        ev = evaluate(
            rules,
            account_id=tx.account_id,
            description=tx.description_normalized or tx.description_raw or "",
            amount=tx.amount,
        )
        changed = False
        if ev.category_id is not None and tx.category_id != ev.category_id:
            tx.category_id = ev.category_id
            changed = True
        if ev.kind is not None and tx.kind != ev.kind:
            tx.kind = ev.kind
            changed = True
        if ev.merchant and tx.merchant != ev.merchant:
            tx.merchant = ev.merchant
            changed = True
        if changed and ev.matched_rule_id is not None:
            tx.matched_rule_id = ev.matched_rule_id
            fires[ev.matched_rule_id] += 1
    # Stamp only the rules that actually changed something.
    now = datetime.now(UTC)
    for rule_id, n in fires.items():
        rule = rules_by_id.get(rule_id)
        if rule is None:
            continue
        rule.apply_count = (rule.apply_count or 0) + n
        rule.last_applied_at = now
    rows_changed = sum(fires.values())
    if rows_changed:
        db.commit()
    return rows_changed, len(fires)


def coverage_summary(db: Session) -> dict:
    rules = load_active_rules(db)
    txs = list(db.scalars(select(models.Transaction)))
    total = len(txs)
    labeled = [
        tx
        for tx in txs
        if tx.category_id is not None and tx.kind != models.TransactionKind.uncategorized
    ]

    matched = 0
    labeled_matched = 0
    labeled_correct = 0
    labeled_incorrect = 0
    for tx in txs:
        ev = evaluate(
            rules,
            account_id=tx.account_id,
            description=tx.description_normalized or tx.description_raw or "",
            amount=tx.amount,
        )
        if ev.matched_rule_id is None:
            continue
        matched += 1
        if tx.category_id is not None and tx.kind != models.TransactionKind.uncategorized:
            labeled_matched += 1
            category_ok = ev.category_id is None or ev.category_id == tx.category_id
            kind_ok = ev.kind is None or ev.kind == tx.kind
            if category_ok and kind_ok:
                labeled_correct += 1
            else:
                labeled_incorrect += 1

    labeled_accuracy = (
        labeled_correct / labeled_matched if labeled_matched else None
    )
    return {
        "active_rule_count": len(rules),
        "total_transactions": total,
        "matched_transactions": matched,
        "coverage_percent": (matched / total * 100) if total else 0.0,
        "labeled_transactions": len(labeled),
        "labeled_matched_transactions": labeled_matched,
        "labeled_correct_matches": labeled_correct,
        "labeled_incorrect_matches": labeled_incorrect,
        "labeled_accuracy": labeled_accuracy,
    }


def stamp_rule_fires(db: Session, rule_ids: list[int]) -> None:
    """Bulk-increment apply_count for the given rule IDs (one per fire).
    Call from import flows where we want the counter to reflect every
    transaction the rule pre-categorized.
    """
    if not rule_ids:
        return
    counts = Counter(rule_ids)
    now = datetime.now(UTC)
    rules = db.scalars(select(models.Rule).where(models.Rule.id.in_(list(counts.keys())))).all()
    for r in rules:
        n = counts.get(r.id, 0)
        if not n:
            continue
        r.apply_count = (r.apply_count or 0) + n
        r.last_applied_at = now


def _proposal_key(tx: models.Transaction) -> str:
    """Stable-ish merchant key for proposal generation.

    Importers already do the useful cleanup work in `merchant`; fall back to
    normalized description when merchant is missing. We intentionally avoid
    inventing fuzzy NLP here: the proposal UI is meant to show obvious
    automation candidates and let the user decide.
    """
    return _generalize_description(tx.merchant or tx.description_normalized or tx.description_raw or "")


def _raw_proposal_text(tx: models.Transaction) -> str:
    return (tx.merchant or tx.description_normalized or tx.description_raw or "").strip()


def _generalize_description(value: str) -> str:
    """Collapse volatile transaction refs into reusable merchant-ish keys."""
    s = (value or "").strip()
    if not s:
        return ""
    # Masked account suffixes and long transfer/reference numbers are almost
    # never useful for a future rule.
    s = re.sub(r"\bX+X*\d{3,}\b", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\b[Xx]{2,}\d{3,}\b", "", s)
    s = re.sub(r"\b\d{10,}\b", "", s)
    # Bank-transfer exports often include YYMMDD or YYYYMMDD in the middle of
    # otherwise stable descriptions: "Brokerage Funds 251201 ...".
    s = re.sub(r"\b\d{6,8}\b", "", s)
    # Strip likely person-name tokens from bank descriptions.
    s = re.sub(r"\b[A-Z][a-z]+\s+[A-Z][a-z]+\b", "", s)
    s = re.sub(r"\b[A-Z][a-z]+,?\s*[A-Z][a-z]+\b", "", s)
    # Some processors prepend their own abbreviation before the merchant.
    # Keep the merchant, drop the processor-ish duplicate token.
    s = re.sub(r"^\s*DD\s+(?=DoorDash\b)", "", s, flags=re.IGNORECASE)
    s = re.sub(r"^\s*(Aplpay|Apple\s+Pay)\s+", "", s, flags=re.IGNORECASE)
    # Location suffixes from card rails are usually less stable than the
    # merchant/service itself.
    s = re.sub(r"\s+New\s+York\s*$", "", s, flags=re.IGNORECASE)
    # Remove noisy punctuation left behind by the substitutions.
    s = re.sub(r"[*#:;-]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    if re.search(r"\bNYCT\b", s, flags=re.IGNORECASE) and re.search(
        r"\bPAYGO\b", s, flags=re.IGNORECASE
    ):
        return "Nyct Paygo"
    return s


def _proposal_pattern(key: str) -> str:
    tokens = [t for t in re.split(r"\s+", key.strip()) if t]
    if not tokens:
        return ""
    # Keep rules readable but flexible about whitespace/noise between stable
    # tokens. Example: "Brokerage Funds" -> "Brokerage.*Funds".
    return r".*".join(re.escape(t) for t in tokens)


def _rule_signature(rule: models.Rule) -> tuple[str | None, int | None, int | None, models.TransactionKind | None]:
    return (
        rule.match_description_pattern,
        rule.match_account_id,
        rule.set_category_id,
        rule.set_kind,
    )


def proposal_signature(
    *,
    key: str,
    match_description_pattern: str,
    match_account_id: int | None,
    set_category_id: int | None,
    set_kind: models.TransactionKind,
) -> str:
    return "|".join(
        [
            key.strip().lower(),
            match_description_pattern.strip(),
            str(match_account_id or ""),
            str(set_category_id or ""),
            set_kind.value,
        ]
    )


def _is_rejected(
    rejected_signatures: set[str],
    *,
    key: str,
    match_description_pattern: str,
    match_account_id: int | None,
    set_category_id: int | None,
    set_kind: models.TransactionKind,
) -> bool:
    return (
        proposal_signature(
            key=key,
            match_description_pattern=match_description_pattern,
            match_account_id=match_account_id,
            set_category_id=set_category_id,
            set_kind=set_kind,
        )
        in rejected_signatures
    )


def _labeled_training_transactions(db: Session) -> list[models.Transaction]:
    return list(
        db.scalars(
            select(models.Transaction)
            .where(models.Transaction.category_id.is_not(None))
            .where(models.Transaction.kind != models.TransactionKind.uncategorized)
            .where(models.Transaction.matched_rule_id.is_(None))
        )
    )


def _rule_proposal_context(db: Session, min_support: int) -> _RuleProposalContext:
    labeled_txs = _labeled_training_transactions(db)
    all_txs = list(db.scalars(select(models.Transaction)))
    active_rules = load_active_rules(db)
    return _RuleProposalContext(
        labeled_txs=labeled_txs,
        all_txs=all_txs,
        total_labeled=len(labeled_txs),
        total_transactions=len(all_txs),
        active_signatures={
            _rule_signature(rule)
            for rule in db.scalars(select(models.Rule).where(models.Rule.is_active.is_(True)))
        },
        active_rules=active_rules,
        rejected_signatures=set(db.scalars(select(models.RuleProposalRejection.signature))),
        min_support=min_support,
    )


def reject_rule_proposal(
    db: Session,
    *,
    key: str,
    name: str,
    match_description_pattern: str,
    match_account_id: int | None,
    set_category_id: int | None,
    set_kind: models.TransactionKind,
    set_merchant: str | None,
) -> bool:
    signature = proposal_signature(
        key=key,
        match_description_pattern=match_description_pattern,
        match_account_id=match_account_id,
        set_category_id=set_category_id,
        set_kind=set_kind,
    )
    if db.scalar(select(models.RuleProposalRejection).filter_by(signature=signature)):
        return False
    db.add(
        models.RuleProposalRejection(
            signature=signature,
            key=key,
            name=name,
            match_account_id=match_account_id,
            match_description_pattern=match_description_pattern,
            set_category_id=set_category_id,
            set_kind=set_kind,
            set_merchant=set_merchant,
        )
    )
    db.commit()
    return True


def _proposal_matches(pattern: str, tx: models.Transaction) -> bool:
    try:
        compiled = re.compile(pattern, flags=re.IGNORECASE)
    except re.error:
        return False
    desc = tx.description_normalized or tx.description_raw or ""
    merchant = tx.merchant or ""
    desc_general = _generalize_description(desc)
    merchant_general = _generalize_description(merchant)
    return (
        bool(compiled.search(desc))
        or bool(compiled.search(merchant))
        or bool(compiled.search(desc_general))
        or bool(compiled.search(merchant_general))
    )


def backtest_rule_proposal(
    db: Session,
    *,
    key: str,
    name: str,
    match_description_pattern: str,
    match_account_id: int | None,
    set_category_id: int | None,
    set_kind: models.TransactionKind,
    set_merchant: str | None,
) -> dict:
    labeled_txs = _labeled_training_transactions(db)
    all_txs = list(db.scalars(select(models.Transaction)))
    total_labeled = len(labeled_txs)
    total_transactions = len(all_txs)
    active_rules = load_active_rules(db)

    def account_ok(tx: models.Transaction) -> bool:
        return match_account_id is None or tx.account_id == match_account_id

    matches = [
        tx
        for tx in labeled_txs
        if account_ok(tx) and _proposal_matches(match_description_pattern, tx)
    ]
    all_matches = sum(
        1
        for tx in all_txs
        if account_ok(tx) and _proposal_matches(match_description_pattern, tx)
    )
    added_matches = sum(
        1
        for tx in all_txs
        if account_ok(tx)
        and _proposal_matches(match_description_pattern, tx)
        and evaluate(
            active_rules,
            account_id=tx.account_id,
            description=tx.description_normalized or tx.description_raw or "",
            amount=tx.amount,
        ).matched_rule_id
        is None
    )
    correct = [
        tx
        for tx in matches
        if tx.category_id == set_category_id and tx.kind == set_kind
    ]
    incorrect = [
        tx
        for tx in matches
        if tx.category_id != set_category_id or tx.kind != set_kind
    ]
    breakdown_counts = Counter((tx.category_id, tx.kind) for tx in matches)
    return {
        "key": key,
        "name": name,
        "match_description_pattern": match_description_pattern,
        "match_account_id": match_account_id,
        "set_category_id": set_category_id,
        "set_kind": set_kind,
        "set_merchant": set_merchant,
        "support": len(correct),
        "total_user_labeled_matches": len(matches),
        "all_transaction_matches": all_matches,
        "added_transaction_matches": added_matches,
        "correct_matches": len(correct),
        "incorrect_matches": len(incorrect),
        "accuracy": len(correct) / len(matches) if matches else 0.0,
        "labeled_coverage_percent": len(matches) / total_labeled * 100 if total_labeled else 0.0,
        "all_coverage_percent": all_matches / total_transactions * 100 if total_transactions else 0.0,
        "added_coverage_percent": added_matches / total_transactions * 100 if total_transactions else 0.0,
        "breakdown": [
            {"category_id": cat_id, "kind": tx_kind, "count": count}
            for (cat_id, tx_kind), count in breakdown_counts.most_common()
        ],
        "examples": _proposal_examples(correct, incorrect, set_category_id, set_kind),
    }


def _group_proposal_candidates(
    labeled_txs: list[models.Transaction],
) -> dict[str, list[models.Transaction]]:
    by_key: dict[str, list[models.Transaction]] = defaultdict(list)
    for tx in labeled_txs:
        key = _proposal_key(tx)
        # Single-token proposals tend to be too broad ("Payment", "Transfer")
        # unless the importer's merchant extraction has already made them
        # specific. Two stable tokens is a useful floor for this local data.
        if key and len(key.split()) >= 2:
            by_key[key].append(tx)
    return by_key


def _majority_outcome(
    txs: list[models.Transaction],
    min_support: int,
) -> tuple[int | None, models.TransactionKind, int] | None:
    if len(txs) < min_support:
        return None
    outcome_counts = Counter((tx.category_id, tx.kind) for tx in txs)
    (category_id, kind), support = outcome_counts.most_common(1)[0]
    if support < min_support:
        return None
    return category_id, kind, support


def _candidate_is_available(
    context: _RuleProposalContext,
    *,
    key: str,
    pattern: str,
    category_id: int | None,
    kind: models.TransactionKind,
) -> bool:
    signature = (pattern, None, category_id, kind)
    if signature in context.active_signatures:
        return False
    return not _is_rejected(
        context.rejected_signatures,
        key=key,
        match_description_pattern=pattern,
        match_account_id=None,
        set_category_id=category_id,
        set_kind=kind,
    )


def _valid_proposal_pattern(pattern: str) -> bool:
    if not pattern:
        return False
    try:
        re.compile(pattern, flags=re.IGNORECASE)
    except re.error:
        return False
    return True


def _proposal_matches_for(pattern: str, txs: list[models.Transaction]) -> list[models.Transaction]:
    return [tx for tx in txs if _proposal_matches(pattern, tx)]


def _all_and_added_matches(
    pattern: str,
    context: _RuleProposalContext,
) -> tuple[int, int]:
    all_matches = 0
    added_matches = 0
    for tx in context.all_txs:
        if not _proposal_matches(pattern, tx):
            continue
        all_matches += 1
        ev = evaluate(
            context.active_rules,
            account_id=tx.account_id,
            description=tx.description_normalized or tx.description_raw or "",
            amount=tx.amount,
        )
        if ev.matched_rule_id is None:
            added_matches += 1
    return all_matches, added_matches


def _proposal_examples(
    correct: list[models.Transaction],
    incorrect: list[models.Transaction],
    category_id: int | None,
    kind: models.TransactionKind,
) -> list[dict]:
    return [
        {
            "transaction_id": tx.id,
            "date": tx.date,
            "description": tx.description_normalized or tx.description_raw,
            "amount": tx.amount,
            "category_id": tx.category_id,
            "kind": tx.kind,
            "correct": tx.category_id == category_id and tx.kind == kind,
        }
        for tx in (incorrect[:3] + correct[:3])[:6]
    ]


def _build_rule_proposal(
    key: str,
    txs: list[models.Transaction],
    context: _RuleProposalContext,
) -> dict | None:
    outcome = _majority_outcome(txs, context.min_support)
    if outcome is None:
        return None
    category_id, kind, support = outcome
    pattern = _proposal_pattern(key)
    if not _valid_proposal_pattern(pattern):
        return None
    if not _candidate_is_available(context, key=key, pattern=pattern, category_id=category_id, kind=kind):
        return None

    matches = _proposal_matches_for(pattern, context.labeled_txs)
    if not matches:
        return None
    all_matches, added_matches = _all_and_added_matches(pattern, context)
    correct = [tx for tx in matches if tx.category_id == category_id and tx.kind == kind]
    incorrect = [tx for tx in matches if tx.category_id != category_id or tx.kind != kind]
    accuracy = len(correct) / len(matches) if matches else 0.0
    # Hide rules that would mostly encode inconsistency. They still surface
    # in breakdowns of broader proposals, but shouldn't rank as suggestions.
    if accuracy < 0.75:
        return None

    breakdown_counts = Counter((tx.category_id, tx.kind) for tx in matches)
    return {
        "key": key,
        "name": key,
        "match_description_pattern": pattern,
        "match_account_id": None,
        "set_category_id": category_id,
        "set_kind": kind,
        "set_merchant": key[:255],
        "support": support,
        "total_user_labeled_matches": len(matches),
        "all_transaction_matches": all_matches,
        "added_transaction_matches": added_matches,
        "correct_matches": len(correct),
        "incorrect_matches": len(incorrect),
        "accuracy": accuracy,
        "labeled_coverage_percent": len(matches) / context.total_labeled * 100,
        "all_coverage_percent": all_matches / context.total_transactions * 100 if context.total_transactions else 0.0,
        "added_coverage_percent": added_matches / context.total_transactions * 100 if context.total_transactions else 0.0,
        "breakdown": [
            {"category_id": cat_id, "kind": tx_kind, "count": count}
            for (cat_id, tx_kind), count in breakdown_counts.most_common()
        ],
        "examples": _proposal_examples(correct, incorrect, category_id, kind),
    }


def generate_rule_proposals(
    db: Session,
    *,
    min_support: int = 3,
    limit: int = 50,
) -> list[dict]:
    """Generate rule candidates from labeled transactions.

    The training pool is categorized rows that were not attributed to an
    existing rule. That includes explicit manual edits and imported historical
    truth, while avoiding circular "learn the current rules back to me"
    proposals. For every recurring merchant/description key, propose the
    majority (category_id, kind), then backtest that candidate against the
    same labeled pool.
    """
    context = _rule_proposal_context(db, min_support)
    if context.total_labeled == 0:
        return []

    proposals: list[dict] = []
    for key, txs in _group_proposal_candidates(context.labeled_txs).items():
        proposal = _build_rule_proposal(key, txs, context)
        if proposal is not None:
            proposals.append(proposal)

    proposals.sort(
        key=lambda p: (
            p["all_transaction_matches"],
            p["correct_matches"],
            p["accuracy"],
            p["total_user_labeled_matches"],
        ),
        reverse=True,
    )
    return proposals[:limit]
