"""The "plan" half of the pipeline (docs/SPEC.md section 6, steps 3-4): resolve each
field's value binding, aggregate, apply default/required rules, format, and decide
overflow handling - all before any PDF is opened. Independently re-implemented against
the spec text; structured the same as FieldPlanner.java (one function per field type)
because that structure falls directly out of the spec, not because code was shared.
"""
from decimal import Decimal

from diagnostic import Diagnostic
from formatter import format_value
from path_resolver import resolve_all, resolve_one
from text_measure import width_of

ELLIPSIS = "..."

VALID_TYPES = {"text", "currency", "number", "date", "ssn", "checkbox", "radio-group", "table"}


class RenderPlan:
    def __init__(self):
        self.draws = []  # list of dict(field_id, box, text, font_size, align)
        self.diagnostics = []

    def has_errors(self):
        return any(d.level == "ERROR" for d in self.diagnostics)


def plan_fields(fields, data):
    plan = RenderPlan()
    for field in fields:
        _plan_field(field, data, plan, 0, 0)
    return plan


def _plan_field(field, scope, plan, dx, dy):
    ftype = field["type"]
    if ftype not in VALID_TYPES:
        raise ValueError(f"field '{field.get('id', '?')}' has unknown type '{ftype}'; "
                          f"expected one of {sorted(VALID_TYPES)}")
    if ftype == "table":
        _plan_table(field, scope, plan)
    elif ftype == "radio-group":
        _plan_radio_group(field, scope, plan)
    elif ftype == "checkbox":
        _plan_checkbox(field, scope, plan, dx, dy)
    else:
        _plan_scalar(field, scope, plan, dx, dy)


def _offset_box(box, dx, dy):
    if dx == 0 and dy == 0:
        return box
    return {**box, "x": box["x"] + dx, "y": box["y"] + dy}


def _is_truthy(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, Decimal):
        return value != 0
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip() != "" and value.strip().lower() != "false"
    return value is not None


def _values_equal(a, b):
    if a is None or b is None:
        return a is b
    if isinstance(a, (int, float, Decimal)) and isinstance(b, (int, float, Decimal)):
        return Decimal(str(a)) == Decimal(str(b))
    return str(a) == str(b)


def _resolve(field, scope, plan):
    """Returns the raw resolved value, or None (with a diagnostic already recorded,
    except for the legitimate "optional + no default" blank case, which also records
    a warning) - mirrors FieldPlanner.resolve()'s three-way missing/default/required logic.
    """
    binding = field.get("value")
    field_id = field["id"]
    if binding is None:
        if field.get("required"):
            plan.diagnostics.append(Diagnostic.error(field_id, "MISSING_BINDING",
                "Field has no value binding but is required."))
        return None

    path = binding["path"]
    has_wildcard = "[*]" in path
    nodes = resolve_all(scope, path)

    if has_wildcard:
        aggregate = binding.get("aggregate")
        if aggregate is None:
            plan.diagnostics.append(Diagnostic.error(field_id, "MISSING_AGGREGATE",
                f"Path '{path}' contains a wildcard but no aggregate was declared."))
            return None
        value = _aggregate(nodes, aggregate, binding.get("joinSeparator", ", "))
    else:
        value = nodes[0] if nodes else None

    if value is None:
        if "default" in binding:
            value = binding["default"]
        elif field.get("required"):
            plan.diagnostics.append(Diagnostic.error(field_id, "MISSING_REQUIRED_VALUE",
                f"Required value not found at path '{path}' and no default was declared."))
            return None
        else:
            plan.diagnostics.append(Diagnostic.warning(field_id, "MISSING_OPTIONAL_VALUE",
                f"Optional value not found at path '{path}'; left blank."))
            return None
    return value


def _aggregate(nodes, kind, join_sep):
    if kind == "sum":
        total = Decimal(0)
        for n in nodes:
            if isinstance(n, (int, float, Decimal)) and not isinstance(n, bool):
                total += Decimal(str(n))
        return total
    if kind == "count":
        return Decimal(sum(1 for n in nodes if n is not None))
    if kind == "first":
        return nodes[0] if nodes else None
    if kind == "join":
        return join_sep.join(str(n) for n in nodes if n is not None)
    return None


def _fit_font_size(field, text, box, plan):
    """Returns the font size to draw at, or None if overflow='error' and it doesn't fit."""
    size = field.get("fontSize", 9)
    if not text or width_of(text, size) <= box["width"]:
        return size
    overflow = field.get("overflow", "shrink")
    field_id = field["id"]
    if overflow == "shrink":
        min_size = field.get("minFontSize", 6)
        shrunk = size
        while shrunk > min_size and width_of(text, shrunk) > box["width"]:
            shrunk -= 0.5
        if width_of(text, shrunk) > box["width"]:
            plan.diagnostics.append(Diagnostic.warning(field_id, "OVERFLOW_SHRINK_FLOOR",
                f"Text '{text}' still does not fit box width {box['width']} at minFontSize {min_size}; drawing at floor size anyway."))
        return shrunk
    if overflow == "truncate":
        return size  # truncation happens separately
    # error
    plan.diagnostics.append(Diagnostic.error(field_id, "OVERFLOW_ERROR",
        f"Text '{text}' (width {width_of(text, size):.1f}pt) does not fit box width {box['width']}pt and overflow policy is 'error'."))
    return None


def _truncate_to_fit(text, field, box, plan):
    size = field.get("fontSize", 9)
    if width_of(text, size) <= box["width"]:
        return text
    kept = text
    while kept and width_of(kept + ELLIPSIS, size) > box["width"]:
        kept = kept[:-1]
    plan.diagnostics.append(Diagnostic.warning(field["id"], "OVERFLOW_TRUNCATED",
        f"Text truncated to fit box width {box['width']}pt."))
    return kept + ELLIPSIS


def _ssn_digit_count_matches_mask(value, fmt):
    mask = (fmt or {}).get("maskPattern", "###-##-####")
    expected_digits = mask.count("#")
    digits = "".join(c for c in str(value) if c.isdigit())
    return len(digits) == expected_digits


def _plan_scalar(field, scope, plan, dx, dy):
    value = _resolve(field, scope, plan)
    if value is None:
        return
    ssn_value_is_blank = value is None or str(value).strip() == ""
    if field["type"] == "ssn" and not ssn_value_is_blank and not _ssn_digit_count_matches_mask(value, field.get("format")):
        mask = (field.get("format") or {}).get("maskPattern", "###-##-####")
        plan.diagnostics.append(Diagnostic.error(field["id"], "VALIDATION_FAILED",
            f"Value '{value}' does not have the digit count required by mask '{mask}'."))
        return
    text = format_value(value, field.get("format"), field["type"])
    box = _offset_box(field["box"], dx, dy)
    font_size = _fit_font_size(field, text, box, plan)
    if font_size is None:
        return
    if field.get("overflow", "shrink") == "truncate":
        text = _truncate_to_fit(text, field, box, plan)
    plan.draws.append({"field_id": field["id"], "box": box, "text": text,
                        "font_size": font_size, "align": field.get("align", "right")})


def _plan_checkbox(field, scope, plan, dx, dy):
    value = _resolve(field, scope, plan)
    truthy = value is not None and _is_truthy(value)
    fmt = field.get("format") or {}
    mark = fmt.get("trueText", "X") if truthy else fmt.get("falseText", "")
    if not mark:
        return
    box = _offset_box(field["box"], dx, dy)
    plan.draws.append({"field_id": field["id"], "box": box, "text": mark,
                        "font_size": field.get("fontSize", 9), "align": "center"})


def _plan_radio_group(field, scope, plan):
    field_id = field["id"]
    options = field.get("options") or []
    if not options:
        plan.diagnostics.append(Diagnostic.error(field_id, "RADIO_GROUP_NO_OPTIONS",
            "radio-group field has no options declared."))
        return
    binding = field.get("value")
    node = resolve_one(scope, binding["path"]) if binding else None
    if node is None:
        if field.get("required"):
            path = binding["path"] if binding else "<none>"
            plan.diagnostics.append(Diagnostic.error(field_id, "MISSING_REQUIRED_VALUE",
                f"Required radio-group value not found at path {path}"))
        return
    matches = [o for o in options if _values_equal(o["matchValue"], node)]
    if len(matches) == 0:
        plan.diagnostics.append(Diagnostic.error(field_id, "RADIO_GROUP_NO_MATCH",
            f"No option.matchValue equals resolved value '{node}'."))
        return
    if len(matches) > 1:
        plan.diagnostics.append(Diagnostic.error(field_id, "RADIO_GROUP_MULTIPLE_MATCHES",
            f"More than one option.matchValue equals resolved value '{node}' - ambiguous."))
        return
    option = matches[0]
    plan.draws.append({"field_id": f"{field_id}.{option['id']}", "box": option["box"],
                        "text": option.get("mark", "X"), "font_size": field.get("fontSize", 9),
                        "align": "center"})


def _plan_table(field, scope, plan):
    field_id = field["id"]
    items_path = field.get("itemsPath")
    columns = field.get("columns")
    pitch = field.get("pitch")
    max_instances = field.get("maxInstances")
    if not (items_path and columns and pitch and max_instances):
        plan.diagnostics.append(Diagnostic.error(field_id, "TABLE_INCOMPLETE",
            "table field is missing itemsPath/columns/pitch/maxInstances."))
        return

    items = resolve_all(scope, items_path)
    # itemsPath without a wildcard resolves to the single array node itself; unwrap it.
    if len(items) == 1 and isinstance(items[0], list):
        items = items[0]

    axis = field.get("axis")
    if axis:
        axis_matches_pitch = pitch["dx"] != 0 if axis == "columns" else pitch["dy"] != 0
        if not axis_matches_pitch:
            plan.diagnostics.append(Diagnostic.warning(field_id, "TABLE_AXIS_PITCH_MISMATCH",
                f"axis='{axis}' but pitch does not move along that axis (dx={pitch['dx']}, dy={pitch['dy']})."))

    draw_count = min(len(items), max_instances)
    for i in range(draw_count):
        instance_scope = items[i]
        dx = pitch["dx"] * i
        dy = pitch["dy"] * i
        for column in columns:
            _plan_field(column, instance_scope, plan, dx, dy)

    if len(items) > max_instances:
        excess = len(items) - max_instances
        plan.diagnostics.append(Diagnostic.warning(field_id, "OVERFLOW_TABLE_INSTANCES",
            f"Data has {len(items)} instances but only {max_instances} slots are printed on the form; {excess} instance(s) were not drawn."))
