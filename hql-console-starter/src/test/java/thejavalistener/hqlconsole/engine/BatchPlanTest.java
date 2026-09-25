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
}
