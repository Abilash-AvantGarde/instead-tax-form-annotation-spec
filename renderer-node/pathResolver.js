'use strict';
// Resolver for the restricted JSONPath-lite grammar (docs/SPEC.md section 3):
//   path    := "$" segment*
//   segment := "." identifier | "[" index "]"
//   index   := "*" | integer
//
// Independently implemented a third time (after Java and Python) against the spec text
// alone - tokenizes with a sticky regex and walks segment by segment, structurally
// different from both other implementations but with identical resolved semantics.

const SEGMENT = /\.([a-zA-Z_][a-zA-Z0-9_]*)|\[(\*|[0-9]+)\]/y;

function segments(path) {
  if (!path.startsWith('$')) {
    throw new Error(`Path must start with '$': ${path}`);
  }
  const rest = path.slice(1);
  const out = [];
  SEGMENT.lastIndex = 0;
  let pos = 0;
  while (pos < rest.length) {
    SEGMENT.lastIndex = pos;
    const m = SEGMENT.exec(rest);
    if (!m || m.index !== pos) {
      throw new Error(`Invalid path syntax at offset ${pos} in: ${path}`);
    }
    if (m[1] !== undefined) {
      out.push({ kind: 'field', token: m[1] });
    } else {
      out.push({ kind: 'index', token: m[2] });
    }
    pos = SEGMENT.lastIndex;
  }
  return out;
}

function resolveAll(root, path) {
  let current = [root];
  for (const { kind, token } of segments(path)) {
    const next = [];
    if (kind === 'field') {
      for (const node of current) {
        if (node && typeof node === 'object' && !Array.isArray(node) && Object.prototype.hasOwnProperty.call(node, token)) {
          next.push(node[token]);
        }
      }
    } else {
      for (const node of current) {
        if (!Array.isArray(node)) continue;
        if (token === '*') {
          next.push(...node);
        } else {
          const i = parseInt(token, 10);
          if (i < node.length) next.push(node[i]);
        }
      }
    }
    current = next;
  }
  return current;
}

function resolveOne(root, path) {
  const all = resolveAll(root, path);
  return all.length ? all[0] : null;
}

module.exports = { resolveAll, resolveOne };
