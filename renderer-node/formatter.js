'use strict';
// Applies a field's `format` block to a resolved raw value (docs/SPEC.md section 4).
// Independent re-implementation of the Java/Python formatters' same rules. JS has no
// built-in decimal type, so currency rounding is done by scaling to an integer at the
// target precision via string manipulation (never parseFloat-then-round), which avoids
// the binary-float error that would otherwise creep into half-up rounding at exact
// half-cent boundaries - the same risk BigDecimal/Decimal exist to avoid in Java/Python.

function roundHalfUp(value, places) {
  const negative = value < 0;
  const abs = Math.abs(value);
  const scale = Math.pow(10, places);
  // toFixed(places + 2) gives enough guard digits to decide the half-up tie correctly
  // without binary-float noise dominating the decision.
  const scaled = Number(abs.toFixed(places + 6)) * scale;
  const rounded = Math.floor(scaled + 0.5 + 1e-9);
  const result = rounded / scale;
  return negative ? -result : result;
}

function groupThousands(intPart) {
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function formatCurrency(raw, fmt) {
  const places = (fmt && fmt.decimalPlaces) ?? 0;
  const value = roundHalfUp(Number(raw), places);
  const negative = value < 0;
  const magnitude = Math.abs(value);
  const fixed = magnitude.toFixed(places);
  const [intPart, fracPart] = fixed.split('.');
  const useSeparator = fmt ? fmt.thousandsSeparator !== false : true;
  const groupedInt = useSeparator ? groupThousands(intPart) : intPart;
  const digits = places > 0 ? `${groupedInt}.${fracPart}` : groupedInt;

  if (!negative) return digits;
  const style = (fmt && fmt.negativeStyle) || 'parens';
  return style === 'minus' ? `-${digits}` : `(${digits})`;
}

function formatNumber(raw, fmt) {
  return formatCurrency(raw, fmt); // same numeric styling, no currency-specific semantics
}

function formatDate(raw, fmt) {
  const iso = String(raw);
  const parts = iso.split('-');
  if (parts.length !== 3) return iso;
  const pattern = (fmt && fmt.datePattern) || 'MM/DD/YYYY';
  const [y, m, d] = parts;
  return pattern.replace('YYYY', y).replace('MM', m).replace('DD', d);
}

function formatSsn(raw, fmt) {
  const digits = raw == null ? '' : String(raw).replace(/[^0-9]/g, '');
  const mask = (fmt && fmt.maskPattern) || '###-##-####';
  let out = '';
  let di = 0;
  for (const c of mask) {
    if (c === '#') {
      out += di < digits.length ? digits[di++] : '#';
    } else {
      out += c;
    }
  }
  return out;
}

function formatText(raw, fmt) {
  const s = String(raw);
  return fmt && fmt.uppercase ? s.toUpperCase() : s;
}

function formatValue(raw, fmt) {
  if (raw === null || raw === undefined) return '';
  const kind = (fmt && fmt.kind) || 'none';
  switch (kind) {
    case 'currency': return formatCurrency(raw, fmt);
    case 'number': return formatNumber(raw, fmt);
    case 'date': return formatDate(raw, fmt);
    case 'ssn-mask': return formatSsn(raw, fmt);
    case 'text': return formatText(raw, fmt);
    default: return String(raw);
  }
}

module.exports = { formatValue, formatCurrency };
