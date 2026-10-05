package com.instead.annotation.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instead.annotation.Diagnostic;
import com.instead.annotation.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the dependents-table expansion loop specifically: this is the feature the brief's
 * "deeply nested data" requirement hinges on, and the one most likely to be declared in a
 * schema but silently skipped by a renderer that doesn't actually implement it.
 */
class FieldPlannerTableTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Field nameColumn() {
        Field f = new Field();
        f.id = "name";
        f.type = FieldType.text;
        f.box = new Box(1, 100, 300, 80, 10);
        f.value = new ValueBinding();
        f.value.path = "$.firstName";
        f.format = new FormatSpec();
        return f;
    }

    private Field tableField(int maxInstances) {
        Field table = new Field();
        table.id = "dependents";
        table.type = FieldType.table;
        table.axis = "columns";
        table.itemsPath = "$.dependents";
        table.maxInstances = maxInstances;
        table.pitch = new Pitch();
        table.pitch.dx = 90;
        table.pitch.dy = 0;
        table.columns = List.of(nameColumn());
        return table;
    }

    private JsonNode dataWithDependents(int count) throws Exception {
        StringBuilder deps = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) deps.append(",");
            deps.append("{\"firstName\": \"Dep").append(i).append("\"}");
        }
        deps.append("]");
        return mapper.readTree("{ \"dependents\": " + deps + " }");
    }

    @Test
    void eachInstanceIsDrawnAtADistinctNonOverlappingOffset() throws Exception {
        RenderPlan plan = new FieldPlanner().plan(List.of(tableField(4)), dataWithDependents(3));

        assertEquals(3, plan.draws.size());
        assertEquals("Dep0", plan.draws.get(0).text());
        assertEquals("Dep1", plan.draws.get(1).text());
        assertEquals("Dep2", plan.draws.get(2).text());

        assertEquals(100.0, plan.draws.get(0).box().x());
        assertEquals(190.0, plan.draws.get(1).box().x());
        assertEquals(280.0, plan.draws.get(2).box().x());
    }

    @Test
    void instancesBeyondMaxInstancesAreNotDrawnAndProduceAWarning() throws Exception {
        RenderPlan plan = new FieldPlanner().plan(List.of(tableField(4)), dataWithDependents(5));

        assertEquals(4, plan.draws.size());
        assertTrue(plan.diagnostics.stream().anyMatch(d -> d.code().equals("OVERFLOW_TABLE_INSTANCES")));
        assertTrue(plan.diagnostics.stream().noneMatch(d -> d.level() == Diagnostic.Level.ERROR));
    }

    @Test
    void zeroInstancesDrawsNothingAndRaisesNoDiagnostic() throws Exception {
        RenderPlan plan = new FieldPlanner().plan(List.of(tableField(4)), dataWithDependents(0));

        assertEquals(0, plan.draws.size());
        assertTrue(plan.diagnostics.isEmpty());
    }

    @Test
    void axisDeclaredAsColumnsButPitchMovesOnlyVerticallyWarns() throws Exception {
        Field table = tableField(4);
        table.pitch.dx = 0;
        table.pitch.dy = 20;

        RenderPlan plan = new FieldPlanner().plan(List.of(table), dataWithDependents(1));

        assertTrue(plan.diagnostics.stream().anyMatch(d -> d.code().equals("TABLE_AXIS_PITCH_MISMATCH")));
    }

    // ---- metamorphic property: table expansion == hand-unrolled field groups -------------

    private Field ssnColumn() {
        Field f = new Field();
        f.id = "ssn";
        f.type = FieldType.text;
        f.box = new Box(1, 100, 320, 80, 10);
        f.value = new ValueBinding();
        f.value.path = "$.ssn";
        f.format = new FormatSpec();
        return f;
    }

    private Field multiColumnTableField(int maxInstances) {
        Field table = tableField(maxInstances);
        table.columns = List.of(nameColumn(), ssnColumn());
        return table;
    }

    /** The same two columns, manually copy-pasted and offset by hand for instance {@code index}. */
    private List<Field> handUnrolledInstance(int index) {
        double dx = 90.0 * index;
        Field name = nameColumn();
        name.id = "name" + index;
        name.box = new Box(1, 100 + dx, 300, 80, 10);
        Field ssn = ssnColumn();
        ssn.id = "ssn" + index;
        ssn.box = new Box(1, 100 + dx, 320, 80, 10);
        return List.of(name, ssn);
    }

    private JsonNode dataWithDependentsAndSsns(int count) throws Exception {
        StringBuilder deps = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) deps.append(",");
            deps.append("{\"firstName\": \"Dep").append(i)
                    .append("\", \"ssn\": \"00000000").append(i).append("\"}");
        }
        deps.append("]");
        return mapper.readTree("{ \"dependents\": " + deps + " }");
    }

    @Test
    void tableExpansionProducesExactlyTheDrawsOfHandUnrolledFieldGroups() throws Exception {
        int n = 3;
        JsonNode data = dataWithDependentsAndSsns(n);

        RenderPlan tablePlan = new FieldPlanner().plan(List.of(multiColumnTableField(4)), data);

        List<Field> unrolledFields = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            JsonNode instanceScope = data.get("dependents").get(i);
            for (Field column : handUnrolledInstance(i)) {
                // Each hand-unrolled field is planned against its own instance's scope directly,
                // the same way the table's recursion passes instanceScope into planField.
                RenderPlan instancePlan = new FieldPlanner().plan(List.of(column), instanceScope);
                assertEquals(1, instancePlan.draws.size());
                unrolledFields.add(column);
            }
        }

        RenderPlan unrolledPlan = new RenderPlan();
        for (int i = 0; i < n; i++) {
            JsonNode instanceScope = data.get("dependents").get(i);
            for (Field column : handUnrolledInstance(i)) {
                unrolledPlan.draws.addAll(new FieldPlanner().plan(List.of(column), instanceScope).draws);
            }
        }

        // Table draws are grouped per-instance (name0, ssn0, name1, ssn1, ...); the hand-unrolled
        // list is built in the same order, so the two plans must match draw-for-draw: same text,
        // same box, at every position.
        assertEquals(unrolledPlan.draws.size(), tablePlan.draws.size());
        for (int i = 0; i < tablePlan.draws.size(); i++) {
            ResolvedDraw fromTable = tablePlan.draws.get(i);
            ResolvedDraw fromUnrolled = unrolledPlan.draws.get(i);
            assertEquals(fromUnrolled.text(), fromTable.text(),
                    "draw " + i + " text differs between table expansion and hand-unrolled fields");
            assertEquals(fromUnrolled.box().x(), fromTable.box().x(), 0.0001,
                    "draw " + i + " x differs");
            assertEquals(fromUnrolled.box().y(), fromTable.box().y(), 0.0001,
                    "draw " + i + " y differs");
        }
    }
}
