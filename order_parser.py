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
_RE_SECTION_START = re.compile(
    r"(?i)\b(order\s*summary|items?|item\s*list|your\s*items?|order\s*items?)\b"
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
    r"(?i)\bpromotion\s*subsidy\b[^\n\r]{0,40}?(-?)\s*(?:RM|MYR)?\s*([\d]{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)"
)
_RE_META_LINE = re.compile(
    r"(?i)^\s*(?:add-?on|addon|modifier|option|note|special\s+instruction|remarks?)\b[:：-]?\s*$"
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

    def to_json(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "booking_id": self.booking_id,
            "items": self.items,
            "calculated_total": self.calculated_total,
            "grand_total": self.grand_total,
            "subtotal": self.subtotal,
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
        m = _RE_PROMOTION_SUBSIDY_RM.search(line)
        if not m:
            continue
        sign, amount_text = m.groups()
        amount = _normalize_price(amount_text)
        if amount is None:
            continue
        # Subsidy should reduce payable total.
        return -abs(amount) if sign == "-" else -abs(amount)
    return None


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

        order_list: list[dict[str, Any]] = []
        current_item: dict[str, Any] | None = None
        pending_name_lines: list[str] = []

        for line in lines[start_idx:end_idx]:
            try:
                item_match = _RE_ITEM_START.match(line)
                if item_match:
                    # Buffer pattern:
                    # keep current item pending until we see a price line, then append.
                    if current_item:
                        if pending_name_lines and current_item.get("price") is None:
                            stitched = " ".join(chunk.strip() for chunk in pending_name_lines if chunk.strip())
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
                            stitched = " ".join(chunk.strip() for chunk in pending_name_lines if chunk.strip())
                            if stitched:
                                current_item["name"] = f'{current_item["name"]} {stitched}'.strip()
                            pending_name_lines = []
                        current_item["price"] = price
                        # price paired, now finalize item
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
                logger.warning("Shopee parse line skipped: %s | line=%r", line_err, line)
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
        if order_amount is not None and promotion_subsidy is not None:
            final_total = round(order_amount + promotion_subsidy, 2)
        elif parsed_total is not None:
            final_total = parsed_total
        else:
            final_total = order_amount

        calculated_total = _calculate_items_total(order_list)
        status = (
            "INCOMPLETE_SCRAPE"
            if final_total is not None and calculated_total + 0.01 < final_total
            else "COMPLETE_SCRAPE"
        )

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
            subtotal=None,
            booking_id=booking_id,
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
        )


# Example:
# Input:  "Order #896. 2x Nasi Lemak. 1x Teh Tarik. Confirm Order"
# Output: order_id "896", items quantity+name for Nasi Lemak & Teh Tarik; Confirm filtered if matched as name-only line
