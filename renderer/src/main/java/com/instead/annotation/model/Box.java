package com.instead.annotation.model;

/**
 * A rectangle on one page of the PDF, in points, measured from the TOP-LEFT corner of
 * that page (see docs/SPEC.md section 2 for the full coordinate-system contract).
 *
 * @param page   1-based page index.
 * @param x      distance from the left edge of the page, in points.
 * @param y      distance from the TOP edge of the page, in points.
 * @param width  box width in points.
 * @param height box height in points.
 */
public record Box(int page, double x, double y, double width, double height) {
}
