"""Resolver for the restricted JSONPath-lite grammar in docs/SPEC.md section 3.

path    := "$" segment*
segment := "." identifier | "[" index "]"
index   := "*" | integer

Independently implemented from scratch against the spec text, not ported from the
Java PathResolver - different tokenizing approach (split into segments up front
instead of a regex Matcher loop) but the same resolved semantics.
"""
import re

_SEGMENT = re.compile(r"\.([a-zA-Z_][a-zA-Z0-9_]*)|\[(\*|[0-9]+)\]")


def _segments(path):
    if not path.startswith("$"):
        raise ValueError(f"Path must start with '$': {path}")
    rest = path[1:]
    pos = 0
    segs = []
    while pos < len(rest):
        m = _SEGMENT.match(rest, pos)
        if not m:
            raise ValueError(f"Invalid path syntax at offset {pos} in: {path}")
        segs.append(("field", m.group(1)) if m.group(1) is not None else ("index", m.group(2)))
        pos = m.end()
    return segs


def resolve_all(root, path):
    """Returns every value path resolves to - zero, one, or many (wildcard)."""
    current = [root]
    for kind, token in _segments(path):
        nxt = []
        if kind == "field":
            for node in current:
                if isinstance(node, dict) and token in node:
                    nxt.append(node[token])
        else:
            for node in current:
                if not isinstance(node, list):
                    continue
                if token == "*":
                    nxt.extend(node)
                else:
                    i = int(token)
                    if i < len(node):
                        nxt.append(node[i])
        current = nxt
    return current


def resolve_one(root, path):
    all_vals = resolve_all(root, path)
    return all_vals[0] if all_vals else None
