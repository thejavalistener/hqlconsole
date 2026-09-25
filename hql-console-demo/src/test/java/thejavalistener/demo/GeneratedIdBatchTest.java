package thejavalistener.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import thejavalistener.hqlconsole.engine.HqlQueryRunner;

@SpringBootTest
class GeneratedIdBatchTest
{
	@Autowired
	private HqlQueryRunner runner;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void canRepeatParentAndChildBatchWithoutKnowingGeneratedIds()
	{
		for(int i=0;i<2;i++)
		{
			runner.executeBatch(List.of(
					"$autor = INSERT INTO Autor (nombre) VALUES ('Autor de lote "+i+"')",
					"INSERT INTO Libro (titulo, autor) VALUES ('Libro de lote "+i+"', $autor)"));
		}

		EntityManager em=entityManagerFactory.createEntityManager();
		try
		{
			Long children=em.createQuery("select count(l) from Libro l where l.titulo like 'Libro de lote %' and l.autor is not null",Long.class)
					.getSingleResult();
			assertEquals(2L,children);
		}
		finally
		{
			em.close();
		}
	}

	@Test
	void rejectsGeneratedIdReferencesOutsideABatch()
	{
		IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
				() -> runner.execute("INSERT INTO Libro (titulo, autor) VALUES ('Fuera de lote', $autor)"));
		assertTrue(error.getMessage().contains("$autor")&&error.getMessage().contains("lote"));
	}
}
