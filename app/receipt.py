"""Turn each engine's output into one receipt shape and score it for bill splitting.

Plain OCR engines go through the same geometry and keyword rules, so a difference
between them comes from the OCR and not from a different parser.
"""
import re
from difflib import SequenceMatcher
from itertools import combinations
from statistics import median

MAX_AMOUNT_DIGITS = 8
COLUMN_TOLERANCE = 0.1
EXACT_TOLERANCE = 0.011
MAX_DROPPED_ROWS = 3
MAX_ITEMS_TO_SEARCH = 30
MIN_ROWS_ABOVE_SUBTOTAL = 2
COMPANY_HEIGHT_RATIO = 0.75
AMOUNT = r"-?\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?|-?\d+(?:[.,]\d{1,2})?"
TRAILING_AMOUNT = re.compile(rf"(?:^|[\s:：xX×*@$¥￥€£]|Rp|RM)({AMOUNT})\s*(?:[-元#*^]|\b[A-Z]\b)?\s*$", re.I)
LEADING_QTY = re.compile(r"^\s*(\d{1,2})\s*[xX×*]?\s+(?=\S)")
TRAILING_QTY = re.compile(r"\s+[xX×*]\s*(\d{1,2})\s*$")
DATE_OR_TIME = re.compile(r"\d{1,4}[-/.:]\d{1,2}[-/.:]\d{1,4}|\d{1,2}:\d{2}")
QTY_IN_ROW = re.compile(r"(?:^|\s)(?:qty\s*)?(\d{1,2})\s*[xX×*@]", re.I)
MEASURE_START = re.compile(r"^\s*(?:\d+\.\d+|\d+[A-Za-z]|qty\b)", re.I)
HAS_WORD = re.compile(r"[^\W\d_]{3,}|[一-鿿]")
ITEM_MARKER = re.compile(r"^[#^*~>\s]+")
SKU_PREFIX = re.compile(r"^(\d{5,})\s+(?=\S)")
AT_PRICE = re.compile(rf"\s*@\s*[$¥￥€£]?\s*(?:{AMOUNT})\s*$")
HAS_NAME = re.compile(r"[^\W\d_]{2,}|[一-鿿]")

DATE = re.compile(
    r"(?<!\d)(?:(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})|(\d{1,2})[/.-](\d{1,2})[/.-](\d{4}|\d{2})"
    r"|\d{1,2}\s?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)['’\s]?\d{2,4}"
    r"|\d{4}年\d{1,2}月\d{1,2}日)",
    re.I,
)
TIME = re.compile(r"(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?:\s?[ap]m)?", re.I)
CENTS_AT_END = re.compile(r"[.,]\d{2}\s*$")
LEGAL_NAME = re.compile(r"sdn\.?\s*bhd|pty\.?\s*ltd|\bp/l\b|\blimited\b|\bltd\b|\binc\b|\bllc\b|\bgmbh\b|有限公司|株式会社", re.I)

SUBTOTAL = re.compile(r"sub\s*-?\s*total|小\s*[计計]|소\s*계", re.I)
TOTAL = re.compile(
    r"grand\s*total|\bt[o0]tal\b|\bttl\b|amount\s*due|balance\s*due|合\s*[计計]|总\s*计|總\s*計|应[付收]|應[付收]|实[付收]|實[付收]|합\s*계|총\s*액|받을\s*금액",
    re.I,
)
NOT_MONEY = re.compile(
    r"total\s*(?:item|qty|quantity)|item\(?s\)?\s*:|qty\(?s\)?\s*:|tax\s*invoice|\babn\b|\bacn\b|数量|件数",
    re.I,
)
SHOP_HEADER = re.compile(r"tax\s*invoice|\babn\b|\bacn\b", re.I)
TOTAL_WITH_GST = re.compile(r"total\W+(?:\w+\W+)?incl\w*\W+(?:of\W+)?gst\W*@", re.I)
GST_INCLUDED = re.compile(r"incl\w*\W+(?:\w+\W+)?gst|gst\W+(?:\w+\W+)?incl|total\W+gst|gst\W+total|tax\W+incl|tax\W+in\W+t(?:o?ta?l)\b", re.I)
TAX = re.compile(r"\btax\b|\bvat\b|\bgst\b|\bhst\b|\bpb-?\s*1\b|\bppn\b|税|稅", re.I)
SERVICE = re.compile(r"service|surcharge|\bsvc\b|\bs/c\b|\btip\b|gratuity|服务费|服務費|小费|小費", re.I)
DISCOUNT = re.compile(r"discount|\bdisc\b|voucher|coupon|优惠|優惠|折扣|减免", re.I)
PAYMENT = re.compile(r"\bcash\b|change|kembali|tunai|\bcard\b|visa|master|debit|credit|eftpos|\bamex\b|paypass|paywave|tendered|rounding|you\s*saved|savings|现金|現金|找零|刷卡|支付宝|微信|お?預り?|お?釣|현금|카드|거스름", re.I)
SKIP = re.compile(r"\bdescription\b|\bamount\b|\bcovers?\b|\babn\b|\bacn\b|tax\s*invoice|\bterminal\b|\bstore\b|rewards|flybuys|\bpoints\b|\btel\b|\bphone\b|\bph\b|\btable\b|\bcashier\b|\bguest\b|\bbill\b|\border\b|电话|電話|桌号|收银", re.I)


def to_number(token):
    """Read an amount, treating 12.500 and 1,250 as thousands and 12,50 as decimals."""
    if token is None:
        return None
    token = re.sub(r"[^\d.,-]", "", str(token))
    match = re.fullmatch(r"(-?\d{1,3}(?:[.,]\d{3})+)([.,]\d{1,2})?", token)
    try:
        if match:
            whole = re.sub(r"[.,]", "", match.group(1))
            return float(whole + (match.group(2) or "").replace(",", "."))
        return float(token.replace(",", "."))
    except ValueError:
        return None


def group_rows(lines):
    """Join boxes printed on the same line; lines without boxes stay as they are."""
    boxed = [line for line in lines if line.get("box")]
    if not boxed:
        return [(line["text"], None, [line]) for line in lines if line["text"].strip()]
    tolerance = 0.6 * median(line["box"][3] - line["box"][1] for line in boxed)
    rows = []
    for line in sorted(boxed, key=lambda item: item["box"][1] + item["box"][3]):
        center = (line["box"][1] + line["box"][3]) / 2
        if rows and abs(center - rows[-1]["center"]) <= tolerance:
            rows[-1]["cells"].append(line)
        else:
            rows.append({"center": center, "cells": [line]})
    return [
        (
            " ".join(cell["text"] for cell in sorted(row["cells"], key=lambda item: item["box"][0])),
            max(cell["box"][2] for cell in row["cells"]),
            sorted(row["cells"], key=lambda item: item["box"][0]),
        )
        for row in rows
    ]


def trailing_amount(row):
    if DATE_OR_TIME.search(row):
        return None, row
    match = TRAILING_AMOUNT.search(row)
    if not match:
        return None, row
    if len(re.sub(r"\D", "", match.group(1))) > MAX_AMOUNT_DIGITS:
        return None, row
    return to_number(match.group(1)), row[: match.start(1)].rstrip(" :：-@xX×*$¥￥€£")


def price_column(rows, lines):
    """Left limit of the right-aligned amount column, or None when there is no such column."""
    boxes = [line["box"] for line in lines if line.get("box")]
    if not boxes:
        return None
    tolerance = COLUMN_TOLERANCE * (max(box[2] for box in boxes) - min(box[0] for box in boxes))
    edges = sorted(
        (
            right for row, right, _ in rows
            if trailing_amount(row)[0] is not None
            and not (AT_PRICE.search(row) or SKIP.search(row) or NOT_MONEY.search(row))
        ),
        reverse=True,
    )
    for edge in edges:
        if sum(edge - tolerance <= other <= edge for other in edges) >= 2:
            return edge - tolerance
    return None


def empty():
    return {
        "items": [], "dropped": [], "subtotal": None, "tax": None, "service": None,
        "discount": None, "total": None, "company": "", "date": "", "time": "",
    }


def find_date(text):
    """First plausible date in the text and what follows it; phone numbers fail the range check."""
    for match in DATE.finditer(text):
        if match.group(1):
            valid = 1900 < int(match.group(1)) < 2100 and int(match.group(2)) <= 12 and int(match.group(3)) <= 31
        elif match.group(4):
            first, second = int(match.group(4)), int(match.group(5))
            valid = first <= 31 and second <= 31 and min(first, second) <= 12
        else:
            valid = True
        if valid:
            return match.group(0), text[match.end():]
    return "", ""


def find_time(text):
    match = TIME.search(text)
    return match.group(0) if match else ""


def line_height(line):
    return line["box"][3] - line["box"][1] if line.get("box") else 0


def read_company(rows, items):
    """Takes a legal name from the header, else the tallest text there, usually the logo."""
    block = []
    first_item = items[0]["name"] if items else None
    for row, _, cells in rows:
        if (find_date(row)[0] or find_time(row) or SHOP_HEADER.search(row) or SKIP.search(row)
                or CENTS_AT_END.search(row) or TOTAL.search(row) or SUBTOTAL.search(row)
                or (first_item and first_item in row)):
            break
        if HAS_NAME.search(row):
            block.append((row, cells))
    for row, _ in block:
        if LEGAL_NAME.search(row):
            return row
    if not block:
        return ""
    heights = [max(line_height(cell) for cell in cells) for _, cells in block]
    tallest = max(heights)
    if tallest == 0:
        return block[0][0]
    first = last = heights.index(tallest)
    limit = COMPANY_HEIGHT_RATIO * tallest
    while first > 0 and heights[first - 1] >= limit:
        first -= 1
    while last < len(block) - 1 and heights[last + 1] >= limit:
        last += 1
    words = [
        cell["text"] for _, cells in block[first:last + 1] for cell in cells
        if line_height(cell) >= limit and HAS_NAME.search(cell["text"])
    ]
    return " ".join(words)


def read_header(rows, items):
    date, time = "", ""
    for index, (row, _, _) in enumerate(rows):
        date, rest = find_date(row)
        if date:
            # The time may print on a later row; rows above the date could be a phone's clock.
            candidates = [rest] + [later for later, _, _ in rows[index + 1:]]
            time = next((found for found in map(find_time, candidates) if found), "")
            break
    return {"company": read_company(rows, items), "date": date, "time": time}


def join_spec(spec, text):
    return f"{spec}, {text}" if spec else text


def split_sku(text):
    """Separates a leading stock number from the description it belongs to."""
    match = SKU_PREFIX.match(text)
    if match and HAS_NAME.search(text[match.end():]):
        return match.group(1), text[match.end():]
    return "", text


def parse_lines(lines):
    result = empty()
    rows = group_rows(lines)
    column = price_column(rows, lines)
    # The item being described: name, sku, spec, and where its name row starts.
    pending = None
    boxes = [line["box"] for line in lines if line.get("box")]
    indent = median(box[3] - box[1] for box in boxes) if boxes else None

    def continues(text, left):
        """A row belongs to the item above when indented under it or unlike a name."""
        if not pending:
            return False
        indented = left is not None and pending["left"] is not None and left > pending["left"] + indent
        return indented or not HAS_WORD.search(text) or bool(MEASURE_START.match(text))
    pending_field = None

    def add_item(name, qty, price, sku="", spec=""):
        result["items"].append({"name": name, "qty": qty, "price": price, "sku": sku, "spec": spec})

    for row, right, cells in rows:
        left = cells[0]["box"][0] if cells[0].get("box") else None
        amount, label = trailing_amount(row)
        if pending_field and amount is not None and not HAS_NAME.search(label):
            if result[pending_field] is None:
                result[pending_field] = abs(amount)
            pending_field = None
            continue
        pending_field = None
        if PAYMENT.search(row):
            pending = None
            continue
        field = None
        if NOT_MONEY.search(row):
            # Anything priced above the shop header is a misread logo or address.
            if SHOP_HEADER.search(row) and result["total"] is None and result["subtotal"] is None:
                result["items"].clear()
            pending = None
            continue
        if TOTAL_WITH_GST.search(row):
            field = "total"
        elif GST_INCLUDED.search(row):
            field = "tax"
        elif SUBTOTAL.search(row):
            field = "subtotal"
        elif TOTAL.search(row):
            field = "total"
        elif DISCOUNT.search(row):
            field = "discount"
        elif SERVICE.search(row):
            field = "service"
        elif TAX.search(row):
            field = "tax"
        if field:
            pending = None
            if amount is None:
                pending_field = field
            elif result[field] is None:
                result[field] = abs(amount)
            continue
        if result["total"] is not None or result["subtotal"] is not None or SKIP.search(row):
            pending = None
            continue
        # Only the amount column prices an item; a unit price beside the name is description.
        if AT_PRICE.search(row):
            row = AT_PRICE.sub("", row)
            amount = None
        elif amount is not None and column is not None and right < column:
            row = label
            amount = None
        if amount is None:
            text = ITEM_MARKER.sub("", row).strip()
            if not HAS_NAME.search(text):
                continue
            if continues(text, left):
                pending["spec"] = join_spec(pending["spec"], text)
            else:
                sku, name = split_sku(text)
                pending = {"name": name, "sku": sku, "spec": "", "left": left}
            continue
        if amount < 0:
            result["discount"] = (result["discount"] or 0) + abs(amount)
            pending = None
            continue
        text = ITEM_MARKER.sub("", label).strip()
        if continues(text, left):
            # The amount closes the item above; its row is a detail of that item.
            count = QTY_IN_ROW.search(text)
            qty = int(count.group(1)) if count else 1
            spec = pending["spec"]
            if HAS_WORD.search(text) or qty > 1:
                spec = join_spec(spec, text.rstrip(" ="))
            add_item(pending["name"], qty, amount, pending["sku"], spec)
            pending = None
            continue
        pending = None
        if not HAS_NAME.search(text):
            continue
        sku, name = split_sku(text)
        qty = 1
        match = LEADING_QTY.match(name) or TRAILING_QTY.search(name)
        if match:
            qty = int(match.group(1))
            name = (LEADING_QTY.sub("", name) if LEADING_QTY.match(name)
                    else TRAILING_QTY.sub("", name)).strip()
        add_item(name, qty, amount, sku)
    if result["total"] is None:
        result["total"] = result["subtotal"]
    reconcile(result)
    result.update(read_header(rows, result["items"]))
    return result


def as_list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def from_cord(parsed):
    """Read the CORD layout shared by Donut's output and the dataset's ground truth."""
    result = empty()
    if not isinstance(parsed, dict):
        return result
    for menu in as_list(parsed.get("menu")):
        if not isinstance(menu, dict) or not menu.get("nm"):
            continue
        name, count, price = menu.get("nm"), menu.get("cnt"), menu.get("price")
        if isinstance(name, list):
            name = " ".join(str(part) for part in name)
        qty = to_number(re.sub(r"[xX]", "", str(count))) if isinstance(count, str) else None
        result["items"].append({
            "name": str(name),
            "qty": int(qty) if qty else 1,
            "price": to_number(price) if isinstance(price, str) else None,
        })
    sub = next(iter(as_list(parsed.get("sub_total"))), {})
    total = next(iter(as_list(parsed.get("total"))), {})
    sub = sub if isinstance(sub, dict) else {}
    total = total if isinstance(total, dict) else {}
    result["subtotal"] = amount_of(sub.get("subtotal_price"))
    result["tax"] = amount_of(sub.get("tax_price"))
    result["service"] = amount_of(sub.get("service_price"))
    result["discount"] = amount_of(sub.get("discount_price"))
    result["total"] = amount_of(total.get("total_price"))
    return result


def amount_of(value):
    value = value[0] if isinstance(value, list) and value else value
    number = to_number(value) if isinstance(value, str) else None
    return abs(number) if number is not None else None


FIELD_MAP = {
    "SUB_TOTAL.SUBTOTAL_PRICE": "subtotal",
    "SUB_TOTAL.TAX_PRICE": "tax",
    "SUB_TOTAL.SERVICE_PRICE": "service",
    "SUB_TOTAL.DISCOUNT_PRICE": "discount",
    "TOTAL.TOTAL_PRICE": "total",
}


def from_entities(entities):
    """Group LayoutLMv3's labelled words into items; each MENU.NM opens a new item."""
    result = empty()
    current = None
    for entity in entities:
        field, text = entity["field"], entity["text"]
        if field == "MENU.NM":
            current = {"name": text, "qty": 1, "price": None}
            result["items"].append(current)
        elif field == "MENU.CNT" and current:
            count = to_number(re.sub(r"[xX]", "", text))
            current["qty"] = int(count) if count else 1
        elif field == "MENU.PRICE" and current:
            current["price"] = to_number(text)
        elif field in FIELD_MAP and result[FIELD_MAP[field]] is None:
            result[FIELD_MAP[field]] = amount_of(text)
    return result


def close(a, b):
    if a is None or b is None or b == 0:
        return False
    return abs(a - b) <= max(0.01, abs(b) * 0.005)


def exact(a, b):
    return a is not None and b is not None and b != 0 and abs(a - b) <= EXACT_TOLERANCE


def totals_agree(receipt, prices, near):
    """True when these prices add up to the printed subtotal or total."""
    items = sum(prices)
    fees = items + (receipt["service"] or 0) - (receipt["discount"] or 0)
    # Australian GST is already inside item prices; elsewhere tax is added on top.
    return (near(items, receipt["subtotal"]) or near(items, receipt["total"])
            or near(fees, receipt["total"]) or near(fees + (receipt["tax"] or 0), receipt["total"]))


def sum_check(receipt):
    """True when the item prices add up to the printed subtotal or total."""
    prices = [item["price"] for item in receipt["items"] if item["price"] is not None]
    if not prices or (receipt["total"] is None and receipt["subtotal"] is None):
        return False
    return totals_agree(receipt, prices, close)


def reconcile(receipt):
    """Trusts the printed total: when items overshoot it, moves the excess rows to dropped."""
    items = receipt["items"]
    if len(items) < 2 or sum_check(receipt):
        return
    if receipt["total"] is None and receipt["subtotal"] is None:
        return
    prices = [item["price"] for item in items]
    if len(items) <= MAX_ITEMS_TO_SEARCH:
        for size in range(1, min(MAX_DROPPED_ROWS, len(items) - 1) + 1):
            fits = [
                rows for rows in combinations(range(len(items)), size)
                if totals_agree(receipt, [p for i, p in enumerate(prices) if i not in rows], exact)
            ]
            if fits:
                # Subtotals follow the items, so with a choice the later rows go.
                rows = max(fits, key=sum)
                receipt["dropped"] = [items[i] for i in rows]
                receipt["items"] = [item for i, item in enumerate(items) if i not in rows]
                return
    # A row equal to everything above it is a subtotal, whatever the OCR made of its label.
    kept = []
    running = 0
    for index, item in enumerate(items):
        if index >= MIN_ROWS_ABOVE_SUBTOTAL and exact(item["price"], running):
            receipt["dropped"].append(item)
            if receipt["subtotal"] is None:
                receipt["subtotal"] = item["price"]
        else:
            kept.append(item)
            running += item["price"]
    receipt["items"] = kept


def match_items(receipt, truth):
    """Pair items with labelled truth; a pair needs both price and name to agree."""
    extra = list(receipt["items"])
    matched = []
    missed = []
    for expected in truth["items"]:
        for item in extra:
            same_name = SequenceMatcher(
                None, item["name"].lower(), expected["name"].lower(),
            ).ratio() >= 0.6
            if same_name and close(item["price"], expected["price"]):
                extra.remove(item)
                matched.append(item)
                break
        else:
            missed.append(expected)
    return matched, missed, extra


def score(receipt, truth):
    """Compare with labelled truth; an item counts when price and name both agree."""
    matched = len(match_items(receipt, truth)[0])
    denominator = len(receipt["items"]) + len(truth["items"])
    total_ok = close(receipt["total"], truth["total"]) if truth["total"] is not None else None
    if not truth["items"]:
        return {"total_ok": total_ok}
    return {
        "total_ok": total_ok,
        "items_expected": len(truth["items"]),
        "items_matched": matched,
        "item_f1": round(2 * matched / denominator, 3) if denominator else None,
    }
