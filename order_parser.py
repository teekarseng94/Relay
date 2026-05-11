"""
MacroDroid Read Screen → structured order via regex (per GastroPOS spec).

Core patterns match the reference implementation; output is normalized to
`quantity` / `name` / `note` for Firestore and kitchen print.
"""

from __future__ import annotations

import re
import logging
from dataclasses import dataclass, field
from typing import Any

# --- Total (RM) — kept for receipts / Firestore --------------------------------
_RE_TOTAL_RM = re.compile(
    r"(?i)(?:^|[^\d])(?:total|grand\s*total|amount\s*due|subtotal|pay(?:able)?)\s*[:：]?\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)",
)
_RE_RM_STANDALONE = re.compile(r"(?i)\bRM\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)\b")


def _normalize_price(s: str) -> float | None:
    s = s.strip().replace(",", "")
    try:
        if s.count(".") > 1 and s.rfind(",") == len(s) - 3:
            s = s.replace(".", "").replace(",", ".")
        return float(s)
    except ValueError:
        return None


def _extract_total(text: str) -> float | None:
    for line in text.splitlines():
        m = _RE_TOTAL_RM.search(line)
        if m:
            p = _normalize_price(m.group(1))
            if p is not None:
                return p
    best: float | None = None
    for m in _RE_RM_STANDALONE.finditer(text):
        p = _normalize_price(m.group(1))
        if p is not None and p > (best or 0):
            best = p
    return best


# UI labels to drop when they appear as the entire "name" from item_pattern
_UI_ITEM_BLOCKLIST = frozenset({"back", "confirm", "cancel", "home"})
_RE_ITEM_START = re.compile(r"^\s*(\d+)\s*x\s+(.+?)\s*$", re.IGNORECASE)
_RE_PRICE_ONLY = re.compile(r"^\s*-?\s*(?:RM\s*)?(\d+(?:[.,]\d{2})?)\s*$", re.IGNORECASE)
_RE_NOTE_LINE = re.compile(r"^\s*([-*•])\s*(.+?)\s*$")
# Avoid matching "item" inside "item(s)" in footer lines like "Total 6 item(s)".
_RE_SECTION_START = re.compile(
    r"(?i)\b(order\s*summary|items\b|item\s*list|your\s*items\b|order\s*items\b)\b"
)
_RE_SECTION_END = re.compile(
    r"(?i)\b(order\s*amount|promotion\s*subsidy|subtotal|total|grand\s*total|discount|delivery\s*fee|service\s*fee|paid\s*with)\b"
)
_RE_ORDER_REF = re.compile(r"#([A-Z0-9\-]+)|\b(GF-[A-Z0-9\-]+)\b", re.IGNORECASE)
_RE_GRAB_ORDER_ID = re.compile(r"\bGF-[A-Z0-9\-]+\b", re.IGNORECASE)
_RE_BOOKING_ID = re.compile(r"(?i)\bbooking\s*id\b[:：]?\s*([A-Z0-9\-]+)\b")
_RE_SHOPEE_ORDER_ID = re.compile(r"#(\d+)\b")
_RE_SHOPEE_ID_TOKEN = re.compile(r"#([A-Za-z0-9][A-Za-z0-9\-]*)")
_RE_SHOPEE_FALLBACK_NUMERIC_FIRST = re.compile(r"#([0-9][A-Za-z0-9\-]{1,})")
# Strict Shopee dine-in / queue ID: #1, #01, #999.
# 1-3 digits, optionally zero-padded, NOT followed by another digit so we never
# accept the long numeric tail of a system reference like #MANUAL-1778206947272.
_RE_SHOPEE_QUEUE_ID = re.compile(r"#(\d{1,3})(?!\d)")
# Hash-prefixed system references that should never be used as the human-facing
# Shopee order id. Keep lower-cased; matched via .lower().startswith(...).
_SHOPEE_BLOCKED_PREFIXES: tuple[str, ...] = (
    "manual-",
    "ref-",
    "spx-",
    "shp-",
    "spm-",
    "auto-",
)
# Any `XX-12345…` style reference (2+ letters, dash, 4+ digits) is a system id,
# not a queue id. Catches MANUAL-1778206947272, REF-9988, SPX-2026XYZ etc.
_RE_SHOPEE_SYSTEM_REF = re.compile(r"^[A-Z][A-Z0-9]*-\d{4,}$", re.IGNORECASE)
_RE_GRAB_START = re.compile(r"(?i)\bitems\s*for\s*\*+")
_RE_GRAB_ITEM_SPLIT = re.compile(r"(?i)(\d+)\s*x\s*")
_RE_GRAB_ANY_PRICE = re.compile(r"(?i)(?:RM\s*)?(-?\d+(?:[.,]\d{2}))")
_RE_GRAB_SUBTOTAL = re.compile(
    r"(?i)\bsubtotal\b\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)"
)
_RE_GRAB_PROMO_HEALTHY_FRUIT = re.compile(
    r"(?i)dapatkan\s+healthy\s+fruit.*?pesanan\s+minimum\s*rm\s*\d+(?:[.,]\d{2})?"
)
_RE_GRAB_MODIFIER = re.compile(
    r"(?i)\b(choice of [a-z ]+)\s+([a-z\u4e00-\u9fff ]+?)\s+(-?\d+(?:[.,]\d{2}))(?=(?:\s+choice of [a-z ]+)|$)"
)
_RE_GRAND_TOTAL_RM = re.compile(
    r"(?i)(?:^|[^\d])(?:grand\s*total|amount\s*due|pay(?:able)?)\s*[:：]?\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)",
)
_RE_ORDER_AMOUNT_RM = re.compile(
    r"(?i)\border\s*amount\b[^\n\r]{0,40}?(-?)\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)"
)
_RE_PROMOTION_SUBSIDY_RM = re.compile(
    r"(?i)\bpromotion\s*subsidy\b[^\n\r]{0,120}?(-?)\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)"
)
_RE_PROMOTION_SUBSIDY_LINE = re.compile(
    r"(?i)^\s*promotion\s*subsidy\s*[:-]?\s*(-?)\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)\s*$"
)
_RE_META_LINE = re.compile(
    r"(?i)^\s*(?:add-?on|addon|modifier|option|note|special\s+instruction|remarks?)\b[:：-]?\s*$"
)
# Shopee merchant: grey lines under items often start with "-" (addon) or "*" (note).
_RE_SHOPEE_ITEM_PRICE_LINE = re.compile(
    r"^\s*(?:RM|MYR)\s*(\d+(?:[.,]\d{2})?)\s*$",
    re.IGNORECASE,
)
# Same-line price after item name (some layouts).
_RE_SHOPEE_ITEM_INLINE_PRICE = re.compile(
    r"(?i)^\s*(\d+)\s*x\s+(.+?)\s+(?:RM|MYR)\s*(\d+(?:[.,]\d{2})?)\s*$",
)
# Structured addon/modifier: "- Addon BIG Rice: BIG RICE" or "- Choice of Temperature: Iced"
_RE_SHOPEE_MODIFIER_KV = re.compile(r"^\s*-\s*(.+?):\s*(.+?)\s*$")
_RE_SHOPEE_NOTE_STAR = re.compile(r"^\s*\*\s*(?:Note:\s*)?(.+?)\s*$", re.IGNORECASE)
# Badge text sometimes glued to item name on same line
_RE_SHOPEE_NAME_BADGES = re.compile(
    r"\s+(Flash\s+Sales|Hot\s+Deal|Best\s+Seller|Popular)\s*$",
    re.IGNORECASE,
)
# Payable total line: "Total 6 item(s)" ... or line with RM near Total
_RE_SHOPEE_PAYABLE_TOTAL = re.compile(
    r"(?i)\btotal\b[^\n]{0,80}?(?:RM|MYR)\s*(\d+(?:[.,]\d{3})*(?:[.,]\d{2})?)",
)
_SHOPEE_IGNORED_IDS = {"details", "order", "info"}
logger = logging.getLogger(__name__)


@dataclass
class ParsedOrderText:
    """Result of parse_order_text."""

    order_id: str  # e.g. "896" or "Unknown"
    items: list[dict[str, Any]] = field(default_factory=list)
    total_price: float | None = None
    currency: str = "MYR"
    order_note: str = ""
    source_text: str = ""  # copy of raw input for debugging
    status: str = "COMPLETE_SCRAPE"
    calculated_total: float = 0.0
    grand_total: float | None = None
    subtotal: float | None = None
    booking_id: str | None = None
    order_amount: float | None = None  # Shopee: subtotal before subsidy
    promotion_subsidy: float | None = None  # Shopee: negative promo amount

    def to_json(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "booking_id": self.booking_id,
            "items": self.items,
            "calculated_total": self.calculated_total,
            "grand_total": self.grand_total,
            "subtotal": self.subtotal,
            "order_amount": self.order_amount,
            "promotion_subsidy": self.promotion_subsidy,
        }


def _extract_grand_total(text: str) -> float | None:
    for line in text.splitlines():
        m = _RE_GRAND_TOTAL_RM.search(line)
        if m:
            p = _normalize_price(m.group(1))
            if p is not None:
                return p
    return _extract_total(text)


def _extract_order_amount(text: str) -> float | None:
    for line in text.splitlines():
        m = _RE_ORDER_AMOUNT_RM.search(line)
        if not m:
            continue
        sign, amount_text = m.groups()
        amount = _normalize_price(amount_text)
        if amount is None:
            continue
        return -abs(amount) if sign == "-" else amount
    return None


def _extract_promotion_subsidy(text: str) -> float | None:
    for line in text.splitlines():
        stripped = line.strip()
        m = _RE_PROMOTION_SUBSIDY_LINE.match(stripped)
        if not m:
            m = _RE_PROMOTION_SUBSIDY_RM.search(line)
        if not m:
            continue
        sign = m.group(1)
        amount_text = m.group(2)
        amount = _normalize_price(amount_text)
        if amount is None:
            continue
        # Subsidy should reduce payable total.
        return -abs(amount) if sign == "-" else -abs(amount)
    return None


def _resolve_shopee_payable_total(
    text: str,
    order_amount: float | None,
    promotion_subsidy: float | None,
) -> float | None:
    """Prefer order_amount + subsidy; else 'Total' line with RM; else grand-total heuristics."""
    if order_amount is not None and promotion_subsidy is not None:
        return round(order_amount + promotion_subsidy, 2)
    m = _RE_SHOPEE_PAYABLE_TOTAL.search(text)
    if m:
        p = _normalize_price(m.group(1))
        if p is not None:
            return p
    return _extract_grand_total(text)


def _is_noise_between_name_and_price(line: str) -> bool:
    token = line.strip()
    if not token:
        return True
    if _RE_META_LINE.match(token):
        return True
    if _RE_NOTE_LINE.match(token):
        return True
    if token.lower() in _UI_ITEM_BLOCKLIST:
        return True
    return False


_SHOPEE_DETAIL_SKIP_LINES = frozenset(
    {
        "mark as ready",
        "cancel order",
        "contact buyer",
        "call driver",
    }
)


def _should_skip_shopee_detail_line(line: str) -> bool:
    """Skip UI chrome that sometimes appears in accessibility dumps."""
    low = line.strip().lower()
    if not low:
        return True
    if low in _SHOPEE_DETAIL_SKIP_LINES:
        return True
    if low.startswith("delivery at ") or low.startswith("ready in "):
        return True
    if "driver on the way" in low:
        return True
    return False


def _collapse_shopee_detail_lines(detail_lines: list[str]) -> tuple[str, list[dict[str, str]]]:
    """Turn grey lines under a Shopee item into note text + structured modifiers."""
    modifiers: list[dict[str, str]] = []
    note_parts: list[str] = []
    for raw in detail_lines:
        line = raw.strip()
        if not line:
            continue
        kv = _RE_SHOPEE_MODIFIER_KV.match(line)
        if kv:
            label, value = kv.group(1).strip(), kv.group(2).strip()
            modifiers.append({"label": label, "value": value})
            continue
        star = _RE_SHOPEE_NOTE_STAR.match(line)
        if star:
            note_parts.append(star.group(1).strip())
            continue
        note_parts.append(line)
    note = "\n".join(note_parts).strip()
    return note, modifiers


def _parse_shopee_items_lines(lines: list[str], start_idx: int, end_idx: int) -> list[dict[str, Any]]:
    """
    Shopee merchant layout: quantity x name, then optional grey lines (addons, notes),
    then a standalone RM price line. Captures multi-line customer instructions.
    """
    order_list: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    detail_lines: list[str] = []

    for line in lines[start_idx:end_idx]:
        inline = _RE_SHOPEE_ITEM_INLINE_PRICE.match(line)
        if inline:
            if current:
                note, mods = _collapse_shopee_detail_lines(detail_lines)
                if note:
                    current["note"] = note
                if mods:
                    current["modifiers"] = mods
                order_list.append(current)
                current = None
                detail_lines = []
            qty_s, name_s, price_s = inline.groups()
            name_clean = _RE_SHOPEE_NAME_BADGES.sub("", name_s.strip()).strip()
            order_list.append(
                {
                    "quantity": int(qty_s),
                    "name": name_clean,
                    "price": _normalize_price(price_s),
                    "note": "",
                }
            )
            continue

        item_match = _RE_ITEM_START.match(line)
        if item_match:
            if current:
                note, mods = _collapse_shopee_detail_lines(detail_lines)
                if note:
                    current["note"] = note
                if mods:
                    current["modifiers"] = mods
                order_list.append(current)
            qty_text, name_text = item_match.groups()
            cleaned_name = _RE_SHOPEE_NAME_BADGES.sub("", name_text.strip()).strip()
            if cleaned_name.lower() in _UI_ITEM_BLOCKLIST:
                current = None
                detail_lines = []
                continue
            current = {
                "quantity": int(qty_text),
                "name": cleaned_name,
                "price": None,
                "note": "",
            }
            detail_lines = []
            continue

        if current is None:
            continue

        price_val: float | None = None
        pm = _RE_SHOPEE_ITEM_PRICE_LINE.match(line)
        if pm:
            price_val = _normalize_price(pm.group(1))
        else:
            pom = _RE_PRICE_ONLY.match(line)
            if pom:
                price_val = _normalize_price(pom.group(1))

        if price_val is not None:
            note, mods = _collapse_shopee_detail_lines(detail_lines)
            current["price"] = price_val
            if note:
                current["note"] = note
            if mods:
                current["modifiers"] = mods
            order_list.append(current)
            current = None
            detail_lines = []
            continue

        if _should_skip_shopee_detail_line(line):
            continue
        # Grey addon/note/customer text — attach to current item, not the product name.
        detail_lines.append(line.strip())

    if current:
        note, mods = _collapse_shopee_detail_lines(detail_lines)
        if note:
            current["note"] = note
        if mods:
            current["modifiers"] = mods
        order_list.append(current)

    return _dedupe_items(order_list)


def _dedupe_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: list[dict[str, Any]] = []
    seen: set[tuple[str, int, float | None]] = set()
    for item in items:
        name = str(item.get("name", "")).strip().lower()
        qty = int(item.get("quantity", 0) or 0)
        price = item.get("price")
        signature = (name, qty, float(price) if price is not None else None)
        if not name or qty <= 0:
            continue
        if signature in seen:
            continue
        seen.add(signature)
        deduped.append(item)
    return deduped


def _calculate_items_total(items: list[dict[str, Any]]) -> float:
    total = 0.0
    for item in items:
        price = item.get("price")
        if price is None:
            continue
        total += float(price)
    return round(total, 2)


def _strip_grab_noise(text: str) -> str:
    cleaned_lines: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        # Remove known promo sentence, but keep the rest of the line since long orders
        # often arrive as a single merged accessibility line.
        line = _RE_GRAB_PROMO_HEALTHY_FRUIT.sub(" ", line)
        line = re.sub(r"\s+", " ", line).strip()
        if line:
            cleaned_lines.append(line)
    return "\n".join(cleaned_lines)


def _clean_raw_text(raw_text: str) -> str:
    text = (raw_text or "").replace("\ufeff", "")
    text = text.replace("\u200b", "").replace("\u200c", "").replace("\u200d", "")
    text = text.replace("\u00a0", " ")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # Common OCR/UI junk labels accidentally glued to IDs.
    text = text.replace("#Details", "").replace("#Order", "").replace("#Info", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _sanitize_shopee_id(candidate: str | None) -> str:
    if not candidate:
        return ""
    cleaned = candidate.strip()
    cleaned = cleaned.replace("#Details", "").replace("#Order", "").replace("#Info", "")
    cleaned = cleaned.lstrip("#").strip()
    cleaned = re.sub(r"[^A-Za-z0-9\-]", "", cleaned)
    return cleaned


def _is_blocked_shopee_token(cleaned: str) -> bool:
    """Return True if `cleaned` is a Shopee system reference, not a queue id."""
    if not cleaned:
        return True
    low = cleaned.lower()
    if low in _SHOPEE_IGNORED_IDS:
        return True
    if any(low.startswith(p) for p in _SHOPEE_BLOCKED_PREFIXES):
        return True
    if _RE_SHOPEE_SYSTEM_REF.fullmatch(cleaned):
        return True
    # Stand-alone long numeric (8+ digits) tokens are platform ids, not queue ids.
    if cleaned.isdigit() and len(cleaned) >= 8:
        return True
    return False


def _is_valid_shopee_id(candidate: str | None) -> bool:
    cleaned = _sanitize_shopee_id(candidate)
    if not cleaned:
        return False
    if _is_blocked_shopee_token(cleaned):
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9\-]*", cleaned))


def _extract_shopee_order_id(text: str) -> str | None:
    """
    Prefer the canonical Shopee dine-in queue id (#1 — #999). Only fall back to
    longer alphanumeric tokens when no queue id is present, and never accept a
    system reference like #MANUAL-1778206947272 even as a fallback.
    """
    # Pass 0 (preferred): strict 1-3 digit queue id like #01, #72, #999.
    queue_match = _RE_SHOPEE_QUEUE_ID.search(text)
    if queue_match:
        return queue_match.group(1)

    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

    # Pass 1: alphanumeric tokens, with the new system-reference blocklist applied.
    for idx, line in enumerate(lines):
        for token in _RE_SHOPEE_ID_TOKEN.finditer(line):
            raw = token.group(1)
            cleaned = _sanitize_shopee_id(raw)
            if not cleaned:
                continue
            if _is_blocked_shopee_token(cleaned):
                # If UI label like #Details appears, try nearby lines for a clean id.
                if cleaned.lower() in _SHOPEE_IGNORED_IDS:
                    nearby: list[str] = []
                    if idx + 1 < len(lines):
                        nearby.append(lines[idx + 1])
                    if idx - 1 >= 0:
                        nearby.append(lines[idx - 1])
                    for near in nearby:
                        near_match = _RE_SHOPEE_ID_TOKEN.search(near)
                        if not near_match:
                            continue
                        near_clean = _sanitize_shopee_id(near_match.group(1))
                        if _is_valid_shopee_id(near_clean):
                            return near_clean
                continue
            if _is_valid_shopee_id(cleaned):
                return cleaned

    # Pass 2 fallback: first # token at least 2 chars that starts with a digit,
    # but still subject to the system-reference blocklist.
    fallback = _RE_SHOPEE_FALLBACK_NUMERIC_FIRST.search(text)
    if fallback:
        cleaned = _sanitize_shopee_id(fallback.group(1))
        if _is_valid_shopee_id(cleaned) and cleaned[0].isdigit():
            return cleaned
    return None


def _parse_grab_stream(text: str) -> tuple[list[dict[str, Any]], str]:
    text = _strip_grab_noise(text)
    start = _RE_GRAB_START.search(text)
    if start:
        item_blob = text[start.end() :].strip()
    else:
        item_blob = text

    order_note = ""
    cutlery_match = re.match(r"(?i)^\s*(cutlery needed|no cutlery)\b", item_blob)
    if cutlery_match:
        order_note = cutlery_match.group(1)
        item_blob = item_blob[cutlery_match.end() :].strip()

    entries: list[dict[str, Any]] = []
    splits = list(_RE_GRAB_ITEM_SPLIT.finditer(item_blob))
    for idx, item_start in enumerate(splits):
        qty_text = item_start.group(1)
        seg_start = item_start.end()
        seg_end = splits[idx + 1].start() if idx + 1 < len(splits) else len(item_blob)
        segment = item_blob[seg_start:seg_end]
        stop = re.search(r"(?i)\bsubtotal\b", segment)
        if stop:
            segment = segment[: stop.start()]
        segment = segment.strip()
        if not segment:
            continue

        price_tokens: list[tuple[float, int, int, str]] = []
        for token in _RE_GRAB_ANY_PRICE.finditer(segment):
            value = _normalize_price(token.group(1))
            if value is None:
                continue
            ctx = segment[max(0, token.start() - 18) : token.end() + 2].lower()
            # Ignore promo threshold like "minimum RM40.00".
            if "minimum rm" in ctx:
                continue
            price_tokens.append((value, token.start(), token.end(), token.group(1)))

        discount_total = 0.0
        if price_tokens:
            base_price, base_start, base_end, _ = price_tokens[0]
            name = segment[:base_start].strip(" -:\t")
            trailing = segment[base_end:].strip()
            discount_total = sum(v for v, _, _, _ in price_tokens[1:] if v < 0)
            final_price = round(base_price + discount_total, 2)
            if final_price < 0:
                final_price = 0.0
            price = final_price
        else:
            name = segment.strip(" -:\t")
            price = None
            trailing = ""

        if not name:
            continue

        modifiers: list[dict[str, Any]] = []
        for mod in _RE_GRAB_MODIFIER.finditer(trailing):
            mod_price = _normalize_price(mod.group(3))
            modifiers.append(
                {
                    "name": mod.group(1).strip(),
                    "value": mod.group(2).strip(),
                    "price": mod_price,
                }
            )
        note = re.sub(_RE_GRAB_MODIFIER, " ", trailing)
        note = re.sub(_RE_GRAB_PROMO_HEALTHY_FRUIT, " ", note)
        note = re.sub(r"[❌⭕⛔️🔻🔺🔸🔹'\"`]+", " ", note)
        note = re.sub(r"(?i)\bchoice of [a-z ]+\b", " ", note)
        note = re.sub(r"(?i)\bRM\s*-?\d+(?:[.,]\d{2})?\b", " ", note)
        note = re.sub(r"(?<!\w)-?\d+(?:[.,]\d{2})(?!\w)", " ", note)
        note = re.sub(r"\s+", " ", note).strip(" -:\t")

        item = {
            "quantity": int(qty_text),
            "name": name,
            "price": price,
            "note": note,
        }
        if modifiers:
            item["modifiers"] = modifiers
        if discount_total < 0:
            item["discount"] = round(discount_total, 2)
        entries.append(item)

    return _dedupe_items(entries), order_note


def parse_order_text(raw_text: str) -> ParsedOrderText:
    """
    1) Order ID via `#([A-Z0-9\\-]+)`
    2) Items via `(\\d+)\\s*x?\\s*([a-zA-Z\\s]+)` with UI word filter
    3) Total via RM / Total patterns (optional)
    """
    text = _clean_raw_text(raw_text)
    if not text:
        return ParsedOrderText(
            order_id="Unknown",
            items=[],
            total_price=None,
            source_text="",
            status="INCOMPLETE_SCRAPE",
            calculated_total=0.0,
            grand_total=None,
            order_amount=None,
            promotion_subsidy=None,
        )

    booking_id_match = _RE_BOOKING_ID.search(text)
    booking_id = booking_id_match.group(1) if booking_id_match else None

    # 1. Extract Order ID (e.g., #896 or GF-123). Skip system references like
    # #MANUAL-1778206947272 — the Shopee branch below will pick the real queue id
    # via `_extract_shopee_order_id`, and the Grab branch handles its own GF-* match.
    order_id = "Unknown"
    for raw_match in _RE_ORDER_REF.finditer(text):
        candidate = (raw_match.group(1) or raw_match.group(2) or "").strip()
        if not candidate:
            continue
        if _is_blocked_shopee_token(candidate) and not candidate.upper().startswith("GF-"):
            continue
        order_id = candidate
        break

    # Grab identification rule: any text containing GF-*
    is_grab = bool(_RE_GRAB_ORDER_ID.search(text))
    if is_grab:
        # For Grab, always use GF-* token as order_id (ignore booking IDs entirely).
        grab_id_match = _RE_GRAB_ORDER_ID.search(text)
        if grab_id_match:
            order_id = grab_id_match.group(0).upper()
        grab_items, order_note = _parse_grab_stream(text)
        grand_total = _extract_grand_total(text)
        subtotal = None
        subtotal_match = _RE_GRAB_SUBTOTAL.search(text)
        if subtotal_match:
            subtotal = _normalize_price(subtotal_match.group(1))
        calculated_total = _calculate_items_total(grab_items)
        status = (
            "INCOMPLETE_SCRAPE"
            if grand_total is not None and calculated_total + 0.01 < grand_total
            else "COMPLETE_SCRAPE"
        )
        return ParsedOrderText(
            order_id=order_id,
            items=grab_items,
            total_price=grand_total,
            currency="MYR",
            order_note=order_note,
            source_text=text,
            status=status,
            calculated_total=calculated_total,
            grand_total=grand_total,
            subtotal=subtotal,
            booking_id=booking_id,
        )

    # Shopee identification rule: text contains #<digits> (e.g. #972, #72, #18).
    shopee_order_id = _extract_shopee_order_id(text)
    if shopee_order_id:
        order_id = shopee_order_id

    try:
        lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
        start_idx = 0
        has_start_anchor = False
        for idx, line in enumerate(lines):
            low = line.lower()
            if _RE_SECTION_START.search(line):
                start_idx = idx + 1
                has_start_anchor = True
                break
            if low == "nicole":
                start_idx = idx + 1
                has_start_anchor = True
                break

        end_idx = len(lines)
        for idx in range(start_idx, len(lines)):
            if _RE_SECTION_END.search(lines[idx]):
                end_idx = idx
                break

        if not has_start_anchor:
            for idx, line in enumerate(lines):
                if _RE_ITEM_START.match(line):
                    start_idx = idx
                    break

        if shopee_order_id:
            order_list = _parse_shopee_items_lines(lines, start_idx, end_idx)
        else:
            order_list = []
            current_item: dict[str, Any] | None = None
            pending_name_lines: list[str] = []

            for line in lines[start_idx:end_idx]:
                try:
                    item_match = _RE_ITEM_START.match(line)
                    if item_match:
                        if current_item:
                            if pending_name_lines and current_item.get("price") is None:
                                stitched = " ".join(
                                    chunk.strip() for chunk in pending_name_lines if chunk.strip()
                                )
                                if stitched:
                                    current_item["name"] = f'{current_item["name"]} {stitched}'.strip()
                            order_list.append(current_item)
                        qty_text, name_text = item_match.groups()
                        cleaned_name = name_text.strip()
                        if cleaned_name.lower() in _UI_ITEM_BLOCKLIST:
                            current_item = None
                            pending_name_lines = []
                            continue
                        current_item = {
                            "quantity": int(qty_text),
                            "name": cleaned_name,
                            "price": None,
                            "note": "",
                        }
                        pending_name_lines = []
                        continue

                    price_match = _RE_PRICE_ONLY.match(line)
                    if price_match and current_item:
                        price = _normalize_price(price_match.group(1))
                        if price is not None:
                            if pending_name_lines:
                                stitched = " ".join(
                                    chunk.strip() for chunk in pending_name_lines if chunk.strip()
                                )
                                if stitched:
                                    current_item["name"] = f'{current_item["name"]} {stitched}'.strip()
                                pending_name_lines = []
                            current_item["price"] = price
                            order_list.append(current_item)
                            current_item = None
                        continue

                    note_match = _RE_NOTE_LINE.match(line)
                    if note_match and current_item:
                        note_symbol, note_body = note_match.groups()
                        note_text = f"{note_symbol} {note_body.strip()}".strip()
                        if note_text:
                            existing_note = current_item.get("note", "")
                            current_item["note"] = f"{existing_note}\n{note_text}".strip()
                        continue

                    if current_item and current_item.get("price") is None:
                        if _is_noise_between_name_and_price(line):
                            continue
                        pending_name_lines.append(line.strip())
                except Exception as line_err:
                    logger.warning("Generic parse line skipped: %s | line=%r", line_err, line)
                    continue

            if current_item:
                if pending_name_lines and current_item.get("price") is None:
                    stitched = " ".join(chunk.strip() for chunk in pending_name_lines if chunk.strip())
                    if stitched:
                        current_item["name"] = f'{current_item["name"]} {stitched}'.strip()
                order_list.append(current_item)

            order_list = _dedupe_items(order_list)

        order_amount = _extract_order_amount(text)
        promotion_subsidy = _extract_promotion_subsidy(text)
        parsed_total = _extract_grand_total(text)
        final_total = _resolve_shopee_payable_total(text, order_amount, promotion_subsidy)
        if final_total is None:
            if parsed_total is not None:
                final_total = parsed_total
            else:
                final_total = order_amount

        calculated_total = _calculate_items_total(order_list)
        if shopee_order_id and order_amount is not None:
            status = (
                "COMPLETE_SCRAPE"
                if abs(calculated_total - order_amount) <= 0.02
                else "INCOMPLETE_SCRAPE"
            )
        elif final_total is not None and calculated_total + 0.01 < final_total:
            status = "INCOMPLETE_SCRAPE"
        else:
            status = "COMPLETE_SCRAPE"

        return ParsedOrderText(
            order_id=order_id,
            items=order_list,
            total_price=final_total,
            currency="MYR",
            order_note="",
            source_text=text,
            status=status,
            calculated_total=calculated_total,
            grand_total=final_total,
            subtotal=order_amount if shopee_order_id else None,
            booking_id=booking_id,
            order_amount=order_amount if shopee_order_id else None,
            promotion_subsidy=promotion_subsidy if shopee_order_id else None,
        )
    except Exception as e:
        logger.exception("Shopee parse fallback: %s", e)
        fallback_total = _extract_grand_total(text) or _extract_order_amount(text)
        return ParsedOrderText(
            order_id=order_id,
            items=[],
            total_price=fallback_total,
            currency="MYR",
            order_note="",
            source_text=text,
            status="INCOMPLETE_SCRAPE",
            calculated_total=0.0,
            grand_total=fallback_total,
            subtotal=None,
            booking_id=booking_id,
            order_amount=None,
            promotion_subsidy=None,
        )


# Example:
# Input:  "Order #896. 2x Nasi Lemak. 1x Teh Tarik. Confirm Order"
# Output: order_id "896", items quantity+name for Nasi Lemak & Teh Tarik; Confirm filtered if matched as name-only line
