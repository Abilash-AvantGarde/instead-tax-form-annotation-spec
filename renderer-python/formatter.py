"""Applies a field's `format` block to a resolved raw value, per docs/SPEC.md section 4.

Independent re-implementation of the same rules as renderer/.../ValueFormatter.java:
IRS whole-dollar half-up rounding, thousands separators, parens for negatives, SSN comb
masking, text/date passthroughs. Uses Decimal (not float) for the same reason the Java
side uses BigDecimal - float can't represent currency exactly and half-up rounding on a
float is not trustworthy at the cent boundary.
"""
import re
from decimal import Decimal, ROUND_HALF_UP, ROUND_HALF_EVEN, ROUND_FLOOR, ROUND_CEILING

_ROUNDING = {
    "half-up": ROUND_HALF_UP,
    "half-down": ROUND_HALF_UP,  # not distinguished in v1 data; half-up is the only mode the schema allows
    "half-even": ROUND_HALF_EVEN,
    "floor": ROUND_FLOOR,
    "ceiling": ROUND_CEILING,
}


def _to_decimal(raw):
    if isinstance(raw, Decimal):
        return raw
    if isinstance(raw, bool):
        return Decimal(1 if raw else 0)
    if isinstance(raw, (int, float)):
        return Decimal(str(raw))
    return Decimal(str(raw))


def format_currency(raw, fmt):
    places = (fmt or {}).get("decimalPlaces", 0)
    mode = _ROUNDING.get((fmt or {}).get("roundingMode", "half-up"), ROUND_HALF_UP)
    value = _to_decimal(raw)
    quantum = Decimal(1).scaleb(-places)
    rounded = value.quantize(quantum, rounding=mode)
    negative = rounded < 0
    magnitude = abs(rounded)

    use_separator = (fmt or {}).get("thousandsSeparator", True)
    int_part, _, frac_part = f"{magnitude:f}".partition(".")
    if use_separator:
        int_part = f"{int(int_part):,}"
    digits = int_part if places == 0 else f"{int_part}.{frac_part.ljust(places, '0')[:places]}"

    if not negative:
        return digits
    style = (fmt or {}).get("negativeStyle", "parens")
    return f"-{digits}" if style == "minus" else f"({digits})"


def format_number(raw, fmt):
    return format_currency(raw, fmt)  # same numeric styling, no currency-specific semantics


def format_date(raw, fmt):
    iso = str(raw)
    parts = iso.split("-")
    if len(parts) != 3:
        return iso
    pattern = (fmt or {}).get("datePattern", "MM/DD/YYYY")
    y, m, d = parts
    return pattern.replace("YYYY", y).replace("MM", m).replace("DD", d)


def format_ssn(raw, fmt):
    digits = re.sub(r"[^0-9]", "", str(raw)) if (raw is not None) else ""
    mask = (fmt or {}).get("maskPattern", "###-##-####")
    out = []
    di = 0
    for c in mask:
        if c == "#":
            out.append(digits[di] if di < len(digits) else "#")
            di += 1
        else:
            out.append(c)
    return "".join(out)


def format_text(raw, fmt):
    s = str(raw)
    return s.upper() if (fmt or {}).get("uppercase", False) else s


def format_value(raw, fmt, field_type):
    if raw is None:
        return ""
    kind = (fmt or {}).get("kind", "none")
    if kind == "currency":
        return format_currency(raw, fmt)
    if kind == "number":
        return format_number(raw, fmt)
    if kind == "date":
        return format_date(raw, fmt)
    if kind == "ssn-mask":
        return format_ssn(raw, fmt)
    if kind == "text":
        return format_text(raw, fmt)
    return str(raw)
