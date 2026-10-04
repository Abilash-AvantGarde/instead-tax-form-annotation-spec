'use strict';
// The "plan" half of the pipeline (docs/SPEC.md section 6, steps 3-4). Third independent
// implementation of the same rules as FieldPlanner.java / planner.py - the per-field-type
// dispatch shape falls directly out of the spec (table/radio-group/checkbox/scalar each
// need distinct handling), not from shared code.

const { Diagnostic } = require('./diagnostic');
const { formatValue } = require('./formatter');
const { resolveAll, resolveOne } = require('./pathResolver');

const ELLIPSIS = '...';

const VALID_TYPES = new Set(['text', 'currency', 'number', 'date', 'ssn', 'checkbox', 'radio-group', 'table']);

function planFields(fields, data, widthOf) {
  const plan = { draws: [], diagnostics: [] };
  for (const field of fields) {
    planField(field, data, plan, 0, 0, widthOf);
  }
  return plan;
}

function hasErrors(plan) {
  return plan.diagnostics.some((d) => d.level === 'ERROR');
}

function planField(field, scope, plan, dx, dy, widthOf) {
  if (!VALID_TYPES.has(field.type)) {
    throw new Error(`field '${field.id || '?'}' has unknown type '${field.type}'; ` +
      `expected one of ${[...VALID_TYPES].sort().join(', ')}`);
  }
  switch (field.type) {
    case 'table': return planTable(field, scope, plan, widthOf);
    case 'radio-group': return planRadioGroup(field, scope, plan);
    case 'checkbox': return planCheckbox(field, scope, plan, dx, dy);
    default: return planScalar(field, scope, plan, dx, dy, widthOf);
  }
}

function offsetBox(box, dx, dy) {
  if (dx === 0 && dy === 0) return box;
  return { ...box, x: box.x + dx, y: box.y + dy };
}

function isTruthy(value) {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  if (typeof value === 'string') return value.trim() !== '' && value.trim().toLowerCase() !== 'false';
  return value !== null && value !== undefined;
}

function valuesEqual(a, b) {
  if (a === null || a === undefined || b === null || b === undefined) return a === b;
  if (typeof a === 'number' || typeof b === 'number') return Number(a) === Number(b);
  return String(a) === String(b);
}

function resolveValue(field, scope, plan) {
  const binding = field.value;
  const fieldId = field.id;
  if (!binding) {
    if (field.required) {
      plan.diagnostics.push(Diagnostic.error(fieldId, 'MISSING_BINDING', 'Field has no value binding but is required.'));
    }
    return { present: false };
  }

  const path = binding.path;
  const hasWildcard = path.includes('[*]');
  const nodes = resolveAll(scope, path);

  let value;
  if (hasWildcard) {
    if (!binding.aggregate) {
      plan.diagnostics.push(Diagnostic.error(fieldId, 'MISSING_AGGREGATE',
        `Path '${path}' contains a wildcard but no aggregate was declared.`));
      return { present: false };
    }
    value = aggregate(nodes, binding.aggregate, binding.joinSeparator || ', ');
  } else {
    value = nodes.length ? nodes[0] : null;
  }

  if (value === null || value === undefined) {
    if (Object.prototype.hasOwnProperty.call(binding, 'default')) {
      value = binding.default;
    } else if (field.required) {
      plan.diagnostics.push(Diagnostic.error(fieldId, 'MISSING_REQUIRED_VALUE',
        `Required value not found at path '${path}' and no default was declared.`));
      return { present: false };
    } else {
      plan.diagnostics.push(Diagnostic.warning(fieldId, 'MISSING_OPTIONAL_VALUE',
        `Optional value not found at path '${path}'; left blank.`));
      return { present: false };
    }
  }
  return { present: true, value };
}

function aggregate(nodes, kind, joinSep) {
  switch (kind) {
    case 'sum':
      return nodes.reduce((acc, n) => (typeof n === 'number' ? acc + n : acc), 0);
    case 'count':
      return nodes.filter((n) => n !== null && n !== undefined).length;
    case 'first':
      return nodes.length ? nodes[0] : null;
    case 'join':
      return nodes.filter((n) => n !== null && n !== undefined).map(String).join(joinSep);
    default:
      return null;
  }
}

function fitFontSize(field, text, box, plan, widthOf) {
  const size = field.fontSize || 9;
  if (!text || widthOf(text, size) <= box.width) return size;
  const overflow = field.overflow || 'shrink';
  const fieldId = field.id;
  if (overflow === 'shrink') {
    const minSize = field.minFontSize || 6;
    let shrunk = size;
    while (shrunk > minSize && widthOf(text, shrunk) > box.width) {
      shrunk -= 0.5;
    }
    if (widthOf(text, shrunk) > box.width) {
      plan.diagnostics.push(Diagnostic.warning(fieldId, 'OVERFLOW_SHRINK_FLOOR',
        `Text '${text}' still does not fit box width ${box.width} at minFontSize ${minSize}; drawing at floor size anyway.`));
    }
    return shrunk;
  }
  if (overflow === 'truncate') return size; // truncation happens separately
  plan.diagnostics.push(Diagnostic.error(fieldId, 'OVERFLOW_ERROR',
    `Text '${text}' (width ${widthOf(text, size).toFixed(1)}pt) does not fit box width ${box.width}pt and overflow policy is 'error'.`));
  return null;
}

function truncateToFit(text, field, box, plan, widthOf) {
  const size = field.fontSize || 9;
  if (widthOf(text, size) <= box.width) return text;
  let kept = text;
  while (kept.length && widthOf(kept + ELLIPSIS, size) > box.width) {
    kept = kept.slice(0, -1);
  }
  plan.diagnostics.push(Diagnostic.warning(field.id, 'OVERFLOW_TRUNCATED',
    `Text truncated to fit box width ${box.width}pt.`));
  return kept + ELLIPSIS;
}

function ssnDigitCountMatchesMask(value, format) {
  const mask = (format && format.maskPattern) || '###-##-####';
  const expectedDigits = (mask.match(/#/g) || []).length;
  const digits = String(value).replace(/[^0-9]/g, '');
  return digits.length === expectedDigits;
}

function planScalar(field, scope, plan, dx, dy, widthOf) {
  const resolved = resolveValue(field, scope, plan);
  if (!resolved.present) return;
  const ssnValueIsBlank = resolved.value === null || resolved.value === undefined || String(resolved.value).trim() === '';
  if (field.type === 'ssn' && !ssnValueIsBlank && !ssnDigitCountMatchesMask(resolved.value, field.format)) {
    const mask = (field.format && field.format.maskPattern) || '###-##-####';
    plan.diagnostics.push(Diagnostic.error(field.id, 'VALIDATION_FAILED',
      `Value '${resolved.value}' does not have the digit count required by mask '${mask}'.`));
    return;
  }
  let text = formatValue(resolved.value, field.format, field.type);
  const box = offsetBox(field.box, dx, dy);
  const fontSize = fitFontSize(field, text, box, plan, widthOf);
  if (fontSize === null) return;
  if ((field.overflow || 'shrink') === 'truncate') {
    text = truncateToFit(text, field, box, plan, widthOf);
  }
  plan.draws.push({ fieldId: field.id, box, text, fontSize, align: field.align || 'right' });
}

function planCheckbox(field, scope, plan, dx, dy) {
  const resolved = resolveValue(field, scope, plan);
  const truthy = resolved.present && isTruthy(resolved.value);
  const fmt = field.format || {};
  const mark = truthy ? (fmt.trueText ?? 'X') : (fmt.falseText ?? '');
  if (!mark) return;
  const box = offsetBox(field.box, dx, dy);
  plan.draws.push({ fieldId: field.id, box, text: mark, fontSize: field.fontSize || 9, align: 'center' });
}

function planRadioGroup(field, scope, plan) {
  const fieldId = field.id;
  const options = field.options || [];
  if (!options.length) {
    plan.diagnostics.push(Diagnostic.error(fieldId, 'RADIO_GROUP_NO_OPTIONS', 'radio-group field has no options declared.'));
    return;
  }
  const node = field.value ? resolveOne(scope, field.value.path) : null;
  if (node === null || node === undefined) {
    if (field.required) {
      const path = field.value ? field.value.path : '<none>';
      plan.diagnostics.push(Diagnostic.error(fieldId, 'MISSING_REQUIRED_VALUE', `Required radio-group value not found at path ${path}`));
    }
    return;
  }
  const matches = options.filter((o) => valuesEqual(o.matchValue, node));
  if (matches.length === 0) {
    plan.diagnostics.push(Diagnostic.error(fieldId, 'RADIO_GROUP_NO_MATCH', `No option.matchValue equals resolved value '${node}'.`));
    return;
  }
  if (matches.length > 1) {
    plan.diagnostics.push(Diagnostic.error(fieldId, 'RADIO_GROUP_MULTIPLE_MATCHES', `More than one option.matchValue equals resolved value '${node}' - ambiguous.`));
    return;
  }
  const option = matches[0];
  plan.draws.push({ fieldId: `${fieldId}.${option.id}`, box: option.box, text: option.mark || 'X', fontSize: field.fontSize || 9, align: 'center' });
}

function planTable(field, scope, plan, widthOf) {
  const fieldId = field.id;
  const { itemsPath, columns, pitch, maxInstances } = field;
  if (!(itemsPath && columns && pitch && maxInstances)) {
    plan.diagnostics.push(Diagnostic.error(fieldId, 'TABLE_INCOMPLETE', 'table field is missing itemsPath/columns/pitch/maxInstances.'));
    return;
  }

  let items = resolveAll(scope, itemsPath);
  // itemsPath without a wildcard resolves to the single array node itself; unwrap it.
  if (items.length === 1 && Array.isArray(items[0])) {
    items = items[0];
  }

  if (field.axis) {
    const axisMatchesPitch = field.axis === 'columns' ? pitch.dx !== 0 : pitch.dy !== 0;
    if (!axisMatchesPitch) {
      plan.diagnostics.push(Diagnostic.warning(fieldId, 'TABLE_AXIS_PITCH_MISMATCH',
        `axis='${field.axis}' but pitch does not move along that axis (dx=${pitch.dx}, dy=${pitch.dy}).`));
    }
  }

  const drawCount = Math.min(items.length, maxInstances);
  for (let i = 0; i < drawCount; i++) {
    const instanceScope = items[i];
    const dx = pitch.dx * i;
    const dy = pitch.dy * i;
    for (const column of columns) {
      planField(column, instanceScope, plan, dx, dy, widthOf);
    }
  }

  if (items.length > maxInstances) {
    const excess = items.length - maxInstances;
    plan.diagnostics.push(Diagnostic.warning(fieldId, 'OVERFLOW_TABLE_INSTANCES',
      `Data has ${items.length} instances but only ${maxInstances} slots are printed on the form; ${excess} instance(s) were not drawn.`));
  }
}

module.exports = { planFields, hasErrors };
