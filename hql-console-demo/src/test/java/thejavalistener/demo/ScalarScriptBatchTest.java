package thejavalistener.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import thejavalistener.hqlconsole.engine.HqlQueryRunner;

@SpringBootTest
class ScalarScriptBatchTest
{
	@Autowired private HqlQueryRunner runner;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@Test
	void canReadOneDateAndUseItWithDayArithmeticInTheSameScript()
	{
		runner.executeBatch(List.of(
				"$fecha = SELECT l.fechaPublicacion FROM Libro l WHERE l.titulo = 'Ficciones'",
				"INSERT INTO Libro (titulo, fechaPublicacion) VALUES ('Copia con fecha', $fecha + 10)"));

		assertEquals(LocalDate.of(1944,1,11),singleDate("Copia con fecha"));
	}

	@Test
	void todayArithmeticWorksForConsoleInsert()
	{
		runner.execute("INSERT INTO Libro (titulo, fechaPublicacion) VALUES ('Ayer de consola', TODAY - 1)");
		assertEquals(LocalDate.now().minusDays(1),singleDate("Ayer de consola"));
	}

	@Test
	void canUseASelectedNumberInAConsoleUpdate()
	{
		runner.executeBatch(List.of(
				"$precio = SELECT l.precio FROM Libro l WHERE l.titulo = 'Ficciones'",
				"UPDATE Libro l SET l.precio = $precio + 10 WHERE l.titulo = 'El Aleph'"));

		assertEquals(new BigDecimal("15010.00"),singlePrice("El Aleph"));
	}

	@Test
	void aBadScalarSelectRollsBackEarlierWrites()
	{
		IllegalArgumentException error=assertThrows(IllegalArgumentException.class,() -> runner.executeBatch(List.of(
				"INSERT INTO Libro (titulo) VALUES ('No debe quedar')",
				"$fecha = SELECT l.fechaPublicacion FROM Libro l WHERE l.titulo = 'No existe'")));
		assertTrue(error.getMessage().contains("sentencia 2"));
		assertEquals(0L,count("No debe quedar"));
	}

	@Test
	void rejectsMoreThanOneRowAndEntitiesAsScalarValues()
	{
		assertThrows(IllegalArgumentException.class,() -> runner.executeBatch(List.of(
				"$fecha = SELECT l.fechaPublicacion FROM Libro l",
				"INSERT INTO Libro (titulo) VALUES ('No llega')")));
		assertThrows(IllegalArgumentException.class,() -> runner.executeBatch(List.of(
				"$libro = SELECT l FROM Libro l WHERE l.titulo = 'Ficciones'",
				"INSERT INTO Libro (titulo) VALUES ('Tampoco llega')")));
	}

	private LocalDate singleDate(String title)
	{
		EntityManager em=entityManagerFactory.createEntityManager();
		try { return em.createQuery("select l.fechaPublicacion from Libro l where l.titulo = :title",LocalDate.class)
				.setParameter("title",title).getSingleResult(); }
		finally { em.close(); }
	}

	private BigDecimal singlePrice(String title)
	{
		EntityManager em=entityManagerFactory.createEntityManager();
		try { return em.createQuery("select l.precio from Libro l where l.titulo = :title",BigDecimal.class)
				.setParameter("title",title).getSingleResult(); }
		finally { em.close(); }
	}

	private long count(String title)
	{
		EntityManager em=entityManagerFactory.createEntityManager();
		try { return em.createQuery("select count(l) from Libro l where l.titulo = :title",Long.class)
				.setParameter("title",title).getSingleResult(); }
		finally { em.close(); }
	}
}
