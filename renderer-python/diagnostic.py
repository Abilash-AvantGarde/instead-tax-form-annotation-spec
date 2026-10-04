"""Mirrors renderer/.../Diagnostic.java's shape (level/fieldId/code/message) and print
format, so diagnostic output reads the same across both renderers for a side-by-side check -
not shared code, just a convergent convention.
"""
from dataclasses import dataclass


@dataclass
class Diagnostic:
    level: str  # "WARNING" | "ERROR"
    field_id: str
    code: str
    message: str

    def __str__(self):
        return f"[{self.level}] {self.code} ({self.field_id}): {self.message}"

    @staticmethod
    def warning(field_id, code, message):
        return Diagnostic("WARNING", field_id, code, message)

    @staticmethod
    def error(field_id, code, message):
        return Diagnostic("ERROR", field_id, code, message)
