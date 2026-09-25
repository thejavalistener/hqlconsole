package thejavalistener.hqlconsole.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BatchPlanTest
{
	@Test
	void acceptsMixedDmlInTheirOriginalOrder()
	{
		BatchPlan plan=BatchPlan.parse(List.of(
				"DELETE FROM Empleado",
				"UPDATE Departamento SET nombre='Ventas'",
				"INSERT INTO Etiqueta (nombre) VALUES ('nueva')"));

		assertFalse(plan.autoCommit());
		assertEquals(List.of("DELETE FROM Empleado", "UPDATE Departamento SET nombre='Ventas'",
				"INSERT INTO Etiqueta (nombre) VALUES ('nueva')"),plan.statements());
	}

	@Test
	void acceptsAutocommitOnlyAsTheFirstBatchDirective()
	{
		BatchPlan plan=BatchPlan.parse(List.of("SET AUTOCOMMIT ON", "DELETE FROM Empleado"));

		assertTrue(plan.autoCommit());
		assertEquals(List.of("DELETE FROM Empleado"),plan.statements());
	}

	@Test
	void rejectsReadsAndMisplacedAutocommit()
	{
		IllegalArgumentException read=assertThrows(IllegalArgumentException.class,
				() -> BatchPlan.parse(List.of("DELETE FROM Empleado", "SELECT e FROM Empleado e")));
		assertTrue(read.getMessage().contains("no es INSERT, UPDATE ni DELETE"));

		IllegalArgumentException misplaced=assertThrows(IllegalArgumentException.class,
				() -> BatchPlan.parse(List.of("DELETE FROM Empleado", "SET AUTOCOMMIT ON")));
		assertTrue(misplaced.getMessage().contains("primera sentencia"));
	}

	@Test
	void keepsGeneratedIdDeclarationsAndAllowsTheirLaterReferences()
	{
		BatchPlan plan=BatchPlan.parse(List.of(
				"$departamento = INSERT INTO Departamento (nombre) VALUES ('Sistemas')",
				"INSERT INTO Empleado (nombre, departamento) VALUES ('Ana', $departamento)"));

		assertEquals(List.of("INSERT INTO Departamento (nombre) VALUES ('Sistemas')",
				"INSERT INTO Empleado (nombre, departamento) VALUES ('Ana', $departamento)"),plan.statements());
		assertEquals(List.of("departamento"),List.copyOf(plan.variables().keySet()));
		assertEquals("departamento",plan.entries().get(0).generatedIdVariable());
	}

	@Test
	void rejectsUndefinedOrRedefinedGeneratedIdVariables()
	{
		IllegalArgumentException undefined=assertThrows(IllegalArgumentException.class,() -> BatchPlan.parse(List.of(
				"INSERT INTO Empleado (nombre, departamento) VALUES ('Ana', $departamento)",
				"DELETE FROM Empleado")));
		assertTrue(undefined.getMessage().contains("$departamento")&&undefined.getMessage().contains("definida"));

		IllegalArgumentException redefined=assertThrows(IllegalArgumentException.class,() -> BatchPlan.parse(List.of(
				"$departamento = INSERT INTO Departamento (nombre) VALUES ('Sistemas')",
				"$departamento = INSERT INTO Departamento (nombre) VALUES ('Ventas')")));
		assertTrue(redefined.getMessage().contains("$departamento")&&redefined.getMessage().contains("declarada"));
	}

	@Test
	void acceptsOnlyConsoleInsertValuesAsGeneratedIdDeclaration()
	{
		IllegalArgumentException error=assertThrows(IllegalArgumentException.class,() -> BatchPlan.parse(List.of(
				"$departamento = INSERT INTO Departamento (nombre) SELECT nombre FROM Departamento",
				"DELETE FROM Empleado")));
		assertTrue(error.getMessage().contains("INSERT ... VALUES"));
		assertTrue(BatchPlan.isGeneratedIdDeclaration("$departamento = INSERT INTO Departamento VALUES nombre='Sistemas'"));
	}
}
