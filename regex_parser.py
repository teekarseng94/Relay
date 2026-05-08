"""
Lightweight regex extraction for Shopee / Grab style notification text.
No browser automation — string only.
"""

from __future__ import annotations

import re
from typing import Literal

from models_print import ParsedLineItem, ParsedNotification

# --- Order id candidates (first match wins) ---
_ORDER_PATTERNS: list[tuple[str, Literal["shopee", "grab"]]] = [
    # Shopee: "Order ID: 123456789" / "#1234567890"
    (r"(?i)shopee[^\n\d]{0,40}?(?:order\s*id|order\s*no\.?)\s*[:#]?\s*([A-Z0-9\-]{6,})", "shopee"),
    (r"(?i)(?:order\s*id|order\s*no\.?)\s*[:#]?\s*([A-Z0-9\-]{8,})", "shopee"),
    (r"#(\d{8,})", "shopee"),
    # Grab: "GO-123456" / "GrabFood order 123"
    (r"(?i)grab(?:food)?[^\n]{0,30}?(?:order|#)\s*[:#]?\s*([A-Z0-9\-]{5,})", "grab"),
    (r"\b(GO-\d+)\b", "grab"),
    (r"(?i)grab[^\n\d]{0,20}(\d{6,})\b", "grab"),
]

# --- Line items: "2 x Item name" / "Item name x2" / "2x Item" ---
_QTY_NAME = re.compile(
    r"^\s*(\d+)\s*[x×]\s*(.+?)\s*$",
    re.IGNORECASE,
)
_NAME_QTY = re.compile(
    r"^\s*(.+?)\s*[x×]\s*(\d+)\s*$",
    re.IGNORECASE,
)


def _detect_source(text: str) -> Literal["shopee", "grab", "unknown"]:
    t = text.lower()
    if "shopee" in t or "shopeefood" in t:
        return "shopee"
    if "grab" in t or "grabfood" in t:
        return "grab"
    return "unknown"


def _extract_order_id(text: str) -> tuple[str | None, Literal["shopee", "grab", "unknown"]]:
    src_guess: Literal["shopee", "grab", "unknown"] = _detect_source(text)
    for pattern, src in _ORDER_PATTERNS:
        m = re.search(pattern, text, re.MULTILINE | re.DOTALL)
        if m:
            oid = m.group(1).strip()
            if oid:
                return oid, src
    return None, src_guess


def _extract_line_items(text: str) -> list[ParsedLineItem]:
    items: list[ParsedLineItem] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or len(line) < 3:
            continue
        # skip obvious headers
        if re.match(r"(?i)^(new order|order|total|subtotal|tax|delivery|pickup)\b", line):
            continue

        m = _QTY_NAME.match(line)
        if m:
            q = int(m.group(1))
            name = m.group(2).strip(" -•\t")
            if q >= 1 and name:
                items.append(ParsedLineItem(quantity=q, name=name[:200]))
            continue

        m2 = _NAME_QTY.match(line)
        if m2:
            name = m2.group(1).strip(" -•\t")
            q = int(m2.group(2))
            if q >= 1 and name:
                items.append(ParsedLineItem(quantity=q, name=name[:200]))
            continue

    return items


def parse_notification_text(raw_text: str) -> ParsedNotification:
    """
    Parse raw notification string into order id, inferred source, and line items.
    Heuristic — tune patterns to your real MacroDroid strings.
    """
    text = raw_text.strip()
    if not text:
        return ParsedNotification(source="unknown", order_id=None, items=[])

    order_id, pat_src = _extract_order_id(text)
    src = _detect_source(text)
    if src == "unknown" and pat_src != "unknown":
        src = pat_src  # type: ignore[assignment]

    items = _extract_line_items(text)

    return ParsedNotification(source=src, order_id=order_id, items=items)
