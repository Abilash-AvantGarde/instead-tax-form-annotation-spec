'use strict';
// Mirrors the Java/Python Diagnostic shape and print format so output reads the same
// across all three renderers for a side-by-side comparison - convention, not shared code.

class Diagnostic {
  constructor(level, fieldId, code, message) {
    this.level = level;
    this.fieldId = fieldId;
    this.code = code;
    this.message = message;
  }

  toString() {
    return `[${this.level}] ${this.code} (${this.fieldId}): ${this.message}`;
  }

  static warning(fieldId, code, message) {
    return new Diagnostic('WARNING', fieldId, code, message);
  }

  static error(fieldId, code, message) {
    return new Diagnostic('ERROR', fieldId, code, message);
  }
}

module.exports = { Diagnostic };
